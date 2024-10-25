// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.grpc.AuthCallCredentials
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.pekko.ClientAdapter
import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.*
import com.daml.ledger.api.v2.admin.{user_management_service as v1User, *}
import com.daml.ledger.api.v2.admin.package_management_service.{
  PackageManagementServiceGrpc,
  UploadDarFileRequest,
}
import com.daml.ledger.api.v2.admin.party_management_service.{
  GetPartiesRequest,
  PartyManagementServiceGrpc,
}
import com.daml.ledger.api.v2.interactive_submission_service.InteractiveSubmissionServiceGrpc
import com.daml.ledger.api.v2.command_service.CommandServiceGrpc
import com.daml.ledger.api.v2.package_service.{ListPackagesRequest, PackageServiceGrpc}
import com.daml.ledger.javaapi.data.{Command, CreateUserResponse, ListUserRightsResponse, User}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.User.Right
import org.lfdecentralizedtrust.splice.auth.AuthToken
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionFilter
import org.lfdecentralizedtrust.splice.util.DisclosedContracts
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.data.PartyDetails
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.ledger.client.GrpcChannel
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.{ByteString, Duration}
import com.google.protobuf.field_mask.FieldMask
import io.grpc.{Channel, StatusRuntimeException, Status as GrpcStatus}
import io.grpc.stub.{AbstractStub, StreamObserver}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.io.Closeable
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

sealed abstract class DedupConfig extends PrettyPrinting {}

final case object NoDedup extends DedupConfig {
  override def pretty = prettyOfObject[this.type]
}

final case class DedupOffset(offset: Option[Long]) extends DedupConfig {
  override def pretty = prettyOfClass(
    param("offset", _.offset)
  )
}

final case class DedupDuration(duration: Duration) extends DedupConfig {
  override def pretty = {
    import com.digitalasset.canton.ledger.api.util.DurationConversion
    prettyOfClass(
      param(
        "duration",
        p =>
          DurationConversion.fromProto(
            com.google.protobuf.duration.Duration.fromJavaProto(p.duration)
          ),
      )
    )
  }
}

/** Ledger client built on top of the Java bindings. The Java equivalent of
  * com.daml.ledger.client.LedgerClient.
  * The methods here expose the underlying gRPC methods more or less directly with
  * two adjustments:
  * 1. They translate to/from types in javaapi.data.
  * 2. They convert to futures and akka sources for akka.
  * All functionality built on top of that is part of SpliceLedgerConnection.
  */
private[environment] class LedgerClient(
    channel: Channel,
    expectedTokenUser: String,
    getToken: () => Future[Option[AuthToken]],
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    esf: ExecutionSequencerFactory,
    ec: ExecutionContext,
) extends Closeable
    with NamedLogging {
  import LedgerClient.{CompletionStreamResponse, GetUpdatesRequest, SubmitAndWaitFor}

  private def checkTokenUser(
      token: AuthToken
  )(implicit tc: TraceContext): Unit = {
    token.user.foreach(actualTokenUser => {
      if (actualTokenUser != expectedTokenUser) {
        logger.error(
          s"Token user $actualTokenUser does not match expected user $expectedTokenUser. Check your application configuration to make sure the auth-config setting is correct."
        )
      }
    })
  }

  private def withCredentialsAndTraceContext[T <: AbstractStub[T]](
      stub: T
  )(implicit tc: TraceContext): Future[T] = {
    getToken().map { token =>
      token.fold(stub) { token =>
        checkTokenUser(token)
        TraceContextGrpc.addTraceContextToCallOptions(
          stub
            .withCallCredentials(new AuthCallCredentials(token.accessToken))
        )
      }
    }
  }
  private val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
    CommandServiceGrpc.stub(channel)
  private val packageServiceStub: PackageServiceGrpc.PackageServiceStub =
    PackageServiceGrpc.stub(channel)
  private val packageManagementServiceStub
      : PackageManagementServiceGrpc.PackageManagementServiceStub =
    PackageManagementServiceGrpc.stub(channel)
  private val partyManagementServiceStub: PartyManagementServiceGrpc.PartyManagementServiceStub =
    PartyManagementServiceGrpc.stub(channel)
  private val userManagementServiceStub
      : v1User.UserManagementServiceGrpc.UserManagementServiceStub =
    v1User.UserManagementServiceGrpc.stub(channel)
  private val updateServiceStub: lapi.update_service.UpdateServiceGrpc.UpdateServiceStub =
    lapi.update_service.UpdateServiceGrpc.stub(channel)
  private val commandSubmissionServiceStub
      : lapi.command_submission_service.CommandSubmissionServiceGrpc.CommandSubmissionServiceStub =
    lapi.command_submission_service.CommandSubmissionServiceGrpc.stub(channel)
  private val multidomainCompletionServiceStub
      : lapi.command_completion_service.CommandCompletionServiceGrpc.CommandCompletionServiceStub =
    lapi.command_completion_service.CommandCompletionServiceGrpc.stub(channel)
  private val stateServiceStub: lapi.state_service.StateServiceGrpc.StateServiceStub =
    lapi.state_service.StateServiceGrpc.stub(channel)
  private val identityProviderConfigServiceStub
      : identity_provider_config_service.IdentityProviderConfigServiceGrpc.IdentityProviderConfigServiceStub =
    identity_provider_config_service.IdentityProviderConfigServiceGrpc.stub(channel)
  private val interactiveSubmissionServiceStub
      : InteractiveSubmissionServiceGrpc.InteractiveSubmissionServiceStub =
    InteractiveSubmissionServiceGrpc.stub(channel)

  private def toSource[T](f: Future[Source[T, NotUsed]]) =
    Source.futureSource(f).mapMaterializedValue(_ => NotUsed)

  override def close(): Unit = GrpcChannel.close(channel)

  def ledgerEnd()(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val req = lapi.state_service.GetLedgerEndRequest()
    for {
      stub <- withCredentialsAndTraceContext(stateServiceStub)
      resp <- stub.getLedgerEnd(req)
    } yield resp.offset
  }

  def latestPrunedOffset()(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val req = lapi.state_service.GetLatestPrunedOffsetsRequest()
    for {
      stub <- withCredentialsAndTraceContext(stateServiceStub)
      resp <- stub.getLatestPrunedOffsets(req)
    } yield resp.participantPrunedUpToInclusive
  }

  def activeContracts(
      request: lapi.state_service.GetActiveContractsRequest
  )(implicit tc: TraceContext): Source[lapi.state_service.GetActiveContractsResponse, NotUsed] =
    toSource(
      for {
        stub <- withCredentialsAndTraceContext(stateServiceStub)
      } yield ClientAdapter
        .serverStreaming(request, stub.getActiveContracts)
    )

  def tryGetTransactionTreeByEventId(
      parties: Seq[String],
      id: String,
  )(implicit traceContext: TraceContext): Future[com.daml.ledger.javaapi.data.TransactionTree] = {
    val req =
      lapi.update_service.GetTransactionByEventIdRequest(eventId = id, requestingParties = parties)
    for {
      stub <- withCredentialsAndTraceContext(updateServiceStub)
      res <- stub.getTransactionTreeByEventId(req).map { resp =>
        LedgerClient.lapiTreeToJavaTree(resp.getTransaction)
      }
    } yield res
  }

  def updates(
      request: GetUpdatesRequest
  )(implicit tc: TraceContext): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    toSource(
      for {
        stub <- withCredentialsAndTraceContext(updateServiceStub)
      } yield ClientAdapter
        .serverStreaming(request.toProto, stub.getUpdateTrees)
        .mapConcat(GetTreeUpdatesResponse.fromProto)
    )
  }

  def submitAndWait[Z](
      domainId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
      waitFor: SubmitAndWaitFor[Z],
      deadline: Option[NonNegativeFiniteDuration] = None,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Z] = {
    val commandsBuilder = CommandsOuterClass.Commands.newBuilder
      .setDomainId(domainId)
      .setCommandId(commandId)
      .setApplicationId(applicationId)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .addAllCommands {
        commands.map(_.toProtoCommand).asJava
      }
      .addAllDisclosedContracts(disclosedContracts.toLedgerApiDisclosedContracts.asJava)
    deduplicationConfig match {
      case DedupOffset(offsetO) =>
        // Canton does not allow an empty offset (ledger begin) so just go for
        // not specfying anything which means max deduplication duration.
        offsetO.foreach { offset =>
          commandsBuilder.setDeduplicationOffset(offset)
        }
      case DedupDuration(duration) =>
        commandsBuilder.setDeduplicationDuration(duration)
      case NoDedup =>
    }

    val request = CommandServiceOuterClass.SubmitAndWaitRequest
      .newBuilder()
      .setCommands(commandsBuilder.build)
      .build()
    for {
      stubWithCredsAndTraceContext <- withCredentialsAndTraceContext(commandServiceStub)
      stub = deadline
        .map(duration =>
          stubWithCredsAndTraceContext
            .withDeadlineAfter(duration.asJava.toMillis(), TimeUnit.MILLISECONDS)
        )
        .getOrElse(stubWithCredsAndTraceContext)
      res <-
        waitFor.stubSubmit(stub, request, ec).map(waitFor.mapResponse)
    } yield res
  }

  def prepareSubmission(
      domainId: Option[String],
      applicationId: String,
      commandId: String,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[lapi.interactive_submission_service.PrepareSubmissionResponse] = {
    for {
      stub <- withCredentialsAndTraceContext(interactiveSubmissionServiceStub)
      result <- stub.prepareSubmission(
        lapi.interactive_submission_service.PrepareSubmissionRequest(
          commands = commands.map(c => lapi.commands.Command.fromJavaProto(c.toProtoCommand)),
          disclosedContracts = disclosedContracts.toLedgerApiDisclosedContracts.map(
            lapi.commands.DisclosedContract.fromJavaProto(_)
          ),
          domainId = domainId.getOrElse(""),
          applicationId = applicationId,
          commandId = commandId,
          actAs = actAs,
          readAs = readAs,
        )
      )
    } yield result
  }

  def executeSubmission(
      preparedTransaction: interactive_submission_data.PreparedTransaction,
      partySignatures: Map[PartyId, LedgerClient.Signature],
      applicationId: String,
      submissionId: String,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[lapi.interactive_submission_service.ExecuteSubmissionResponse] =
    for {
      stub <- withCredentialsAndTraceContext(interactiveSubmissionServiceStub)
      result <- stub.executeSubmission(
        lapi.interactive_submission_service.ExecuteSubmissionRequest(
          preparedTransaction = Some(preparedTransaction),
          partiesSignatures =
            Some(lapi.interactive_submission_service.PartySignatures(partySignatures.toList.map {
              case (party, signature) =>
                lapi.interactive_submission_service.SinglePartySignatures(
                  party.toProtoPrimitive,
                  Seq(
                    lapi.interactive_submission_service.Signature(
                      lapi.interactive_submission_service.SignatureFormat.SIGNATURE_FORMAT_RAW,
                      signature.signature,
                      signature.signedBy.toProtoPrimitive,
                    )
                  ),
                )
            })),
          applicationId = applicationId,
          submissionId = submissionId,
        )
      )
    } yield result

  def listPackages()(implicit ec: ExecutionContext, tc: TraceContext): Future[Seq[String]] = {
    val request = ListPackagesRequest()
    for {
      stub <- withCredentialsAndTraceContext(packageServiceStub)
      res <- stub
        .listPackages(request)
        .map(_.packageIds)
    } yield res
  }

  def uploadDarFile(
      darFile: ByteString
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Unit] = {
    val request = UploadDarFileRequest(darFile)
    for {
      stub <- withCredentialsAndTraceContext(packageManagementServiceStub)
      res <- stub.uploadDarFile(request).map(_ => ())
    } yield res
  }

  private def listUsersProto(
      pageToken: Option[String],
      pageSize: Int,
      identityProviderId: Option[String] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(Seq[UserManagementServiceOuterClass.User], Option[String])] = {
    val requestBuilder =
      new v1User.ListUsersRequest(
        pageToken.getOrElse(""),
        pageSize,
        identityProviderId.getOrElse(""),
      )
    for {
      stub <- withCredentialsAndTraceContext(userManagementServiceStub)
      res <- stub.listUsers(requestBuilder)
    } yield (
      res.users.map(v1User.User.toJavaProto),
      Some(res.nextPageToken).filter(_.nonEmpty),
    )
  }

  def listUsers(pageToken: Option[String], pageSize: Int = 100)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(Seq[User], Option[String])] =
    listUsersProto(pageToken, pageSize).map { case (users, nextPage) =>
      (users.map(User.fromProto), nextPage)
    }

  def getUserProto(
      userId: String,
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[UserManagementServiceOuterClass.User] = {
    val requestBuilder = v1User.GetUserRequest(userId, identityProviderId.getOrElse(""))
    for {
      stub <- withCredentialsAndTraceContext(userManagementServiceStub)
      res <- stub.getUser(requestBuilder).map(u => v1User.User.toJavaProto(u.getUser))
    } yield res
  }

  def getUser(userId: String, identityProviderId: Option[String])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[User] =
    getUserProto(userId, identityProviderId).map(User.fromProto(_))

  def getOrCreateUser(
      user: User,
      initialRights: Seq[User.Right],
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[User] = {
    getUser(user.getId(), identityProviderId).recoverWith {
      case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
        createUser(user, initialRights, identityProviderId)
    }
  }

  def getParties(
      parties: Seq[PartyId]
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Seq[PartyDetails]] = {
    val request = GetPartiesRequest(parties.map(_.toProtoPrimitive))
    for {
      stub <- withCredentialsAndTraceContext(partyManagementServiceStub)
      res <- stub
        .getParties(request)
        .map(r => r.partyDetails.map(details => PartyDetails.fromProtoPartyDetails(details)))
    } yield res
  }

  def createUser(
      user: User,
      initialRights: Seq[User.Right],
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[User] = {
    if (initialRights.isEmpty) {
      throw new IllegalArgumentException("createUser requires at least one right")
    } else {
      val request = v1User.CreateUserRequest(
        Some(
          v1User.User
            .fromJavaProto(user.toProto)
            .withIdentityProviderId(identityProviderId.getOrElse(""))
        ),
        initialRights.map(javaRightToV1Right),
      )
      for {
        stub <- withCredentialsAndTraceContext(userManagementServiceStub)
        res <- stub
          .createUser(request)
          .map(r => CreateUserResponse.fromProto(v1User.CreateUserResponse.toJavaProto(r)).getUser)
      } yield res
    }
  }

  private def javaRightToV1Right(right: User.Right) = right match {
    case as: Right.CanActAs =>
      v1User.Right.defaultInstance.withCanActAs(v1User.Right.CanActAs(as.party))
    case as: Right.CanReadAs =>
      v1User.Right.defaultInstance.withCanReadAs(v1User.Right.CanReadAs(as.party))
    case _: Right.IdentityProviderAdmin =>
      v1User.Right.defaultInstance.withIdentityProviderAdmin(
        v1User.Right.IdentityProviderAdmin()
      )
    case _: Right.ParticipantAdmin =>
      v1User.Right.defaultInstance.withParticipantAdmin(v1User.Right.ParticipantAdmin())
    case unsupported => throw new IllegalArgumentException(s"unsupported right: $unsupported")

  }

  def setUserPrimaryParty(
      userId: String,
      primaryParty: PartyId,
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    for {
      user <- getUserProto(userId, identityProviderId)
      newUser = user.toBuilder.setPrimaryParty(primaryParty.toProtoPrimitive).build
      _ <- updateUser(newUser, FieldMask(Seq("primary_party")))
    } yield ()
  }

  def updateUser(user: UserManagementServiceOuterClass.User, mask: FieldMask)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val request = v1User.UpdateUserRequest(
      Some(v1User.User.fromJavaProto(user)),
      Some(mask),
    )
    for {
      stub <- withCredentialsAndTraceContext(userManagementServiceStub)
      res <- stub.updateUser(request)
    } yield res
  }.map(_ => ())

  def listUserRights(userId: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[User.Right]] = {
    val request = v1User.ListUserRightsRequest(userId)
    for {
      stub <- withCredentialsAndTraceContext(userManagementServiceStub)
      res <- stub
        .listUserRights(request)
        .map(r =>
          ListUserRightsResponse
            .fromProto(v1User.ListUserRightsResponse.toJavaProto(r))
            .getRights
            .asScala
            .toSeq
        )
    } yield res
  }

  def grantUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    if (rights.isEmpty) {
      throw new IllegalArgumentException("grantUserRights requires at least one right")
    } else {
      val request = v1User.GrantUserRightsRequest(
        userId,
        rights.map(javaRightToV1Right),
      )

      for {
        stub <- withCredentialsAndTraceContext(userManagementServiceStub)
        res <- stub.grantUserRights(request).map(_ => ())
      } yield res
    }
  }

  def revokeUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    if (rights.isEmpty) {
      throw new IllegalArgumentException("revokeUserRights requires at least one right")
    } else {
      val request = v1User.RevokeUserRightsRequest(
        userId,
        rights.map(javaRightToV1Right),
      )
      for {
        stub <- withCredentialsAndTraceContext(userManagementServiceStub)
        res <- stub.revokeUserRights(request).map(_ => ())
      } yield res
    }
  }

  def submitReassignment(
      applicationId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: LedgerClient.ReassignmentCommand,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      stub <- withCredentialsAndTraceContext(commandSubmissionServiceStub)
      res <- stub
        .submitReassignment(
          LedgerClient
            .ReassignmentSubmitRequest(
              applicationId,
              commandId,
              submissionId,
              submitter,
              command,
            )
            .toProto
        )
        .map((_: lapi.command_submission_service.SubmitReassignmentResponse) => ())
    } yield res

  def completions(
      applicationId: String,
      parties: Seq[PartyId],
      begin: Option[Long],
  )(implicit tc: TraceContext): Source[CompletionStreamResponse, NotUsed] =
    toSource(
      for {
        stub <- withCredentialsAndTraceContext(multidomainCompletionServiceStub)
      } yield ClientAdapter.serverStreaming(
        lapi.command_completion_service.CompletionStreamRequest(
          applicationId = applicationId,
          parties = parties.map(_.toProtoPrimitive),
          beginExclusive = begin,
        ),
        stub.completionStream,
      ) map CompletionStreamResponse.fromProto
    )

  def getConnectedDomains(
      party: PartyId
  )(implicit tc: TraceContext): Future[Map[DomainAlias, DomainId]] = {
    val req = lapi.state_service.GetConnectedDomainsRequest(
      party = party.toProtoPrimitive
    )
    for {
      stub <- withCredentialsAndTraceContext(stateServiceStub)
      res <- stub.getConnectedDomains(req).map { resp =>
        resp.connectedDomains.map { cd =>
          DomainAlias.tryCreate(cd.domainAlias) -> DomainId.tryFromString(cd.domainId)
        }.toMap
      }
    } yield res
  }

  def createIdentityProviderConfig(
      id: String,
      issuer: String,
      jwksUrl: String,
      audience: String,
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      stub <- withCredentialsAndTraceContext(identityProviderConfigServiceStub)
      _ <- stub.createIdentityProviderConfig(
        identity_provider_config_service.CreateIdentityProviderConfigRequest(
          Some(
            identity_provider_config_service.IdentityProviderConfig(
              identityProviderId = id,
              issuer = issuer,
              jwksUrl = jwksUrl,
              audience = audience,
            )
          )
        )
      )
    } yield ()
  }
}

object LedgerClient {
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  class FutureObserver[T](implicit elc: ErrorLoggingContext) extends StreamObserver[T] {
    val promise = Promise[T]()
    private var result: Option[T] = None

    override def onError(t: Throwable) = {
      promise.failure(t)
    }

    override def onNext(result: T) = {
      this.result = Some(result)
    }

    override def onCompleted() = {
      promise.success(
        result.getOrElse(
          ErrorUtil.internalError(
            new IllegalStateException("onComplete was called without prior call to onNext")
          )
        )
      )
    }
  }

  final case class GetUpdatesRequest(
      begin: String,
      end: Option[String],
      filter: IngestionFilter,
  ) {
    private[LedgerClient] def toProto: lapi.update_service.GetUpdatesRequest =
      lapi.update_service.GetUpdatesRequest(
        beginExclusive = begin,
        endInclusive = end.getOrElse(""),
        filter = Some(filter.toTransactionFilter),
      )
  }

  private[environment] sealed abstract class SubmitAndWaitFor[+Z] {
    import SubmitAndWaitFor.*
    private[LedgerClient] type RawResponse
    private[LedgerClient] val stubSubmit: StubSubmit[RawResponse]
    private[LedgerClient] val mapResponse: RawResponse => Z
  }

  private[environment] object SubmitAndWaitFor {
    import com.daml.ledger.api.v2.CommandServiceOuterClass as CSOC
    import com.daml.ledger.javaapi.data as jdata

    val CompletionOffset: SubmitAndWaitFor[Long] =
      impl((r: CSOC.SubmitAndWaitForUpdateIdResponse) => r.getCompletionOffset)(
        { case (stub, r, ec) =>
          stub
            .submitAndWaitForUpdateId(command_service.SubmitAndWaitRequest.fromJavaProto(r))
            .map(r => command_service.SubmitAndWaitForUpdateIdResponse.toJavaProto(r))(ec)
        }
      )

    val Transaction: SubmitAndWaitFor[jdata.Transaction] =
      impl((response: CSOC.SubmitAndWaitForTransactionResponse) =>
        jdata.Transaction.fromProto(response.getTransaction)
      ) {
        { case (stub, r, ec) =>
          stub
            .submitAndWaitForTransaction(command_service.SubmitAndWaitRequest.fromJavaProto(r))
            .map(r => command_service.SubmitAndWaitForTransactionResponse.toJavaProto(r))(ec)
        }
      }

    val TransactionTree: SubmitAndWaitFor[jdata.TransactionTree] =
      impl((response: CSOC.SubmitAndWaitForTransactionTreeResponse) =>
        jdata.TransactionTree.fromProto(response.getTransaction)
      ) {
        { case (stub, r, ec) =>
          stub
            .submitAndWaitForTransactionTree(command_service.SubmitAndWaitRequest.fromJavaProto(r))
            .map(r => command_service.SubmitAndWaitForTransactionTreeResponse.toJavaProto(r))(ec)
        }
      }

    private type StubSubmit[R] = (
        CommandServiceGrpc.CommandServiceStub,
        CSOC.SubmitAndWaitRequest,
        ExecutionContext,
    ) => Future[R]

    private[this] def impl[R, Z](mapResponse0: R => Z)(
        stubSubmit0: StubSubmit[R]
    ): SubmitAndWaitFor[Z] = new SubmitAndWaitFor[Z] {
      type RawResponse = R
      override val stubSubmit = stubSubmit0
      override val mapResponse = mapResponse0
    }
  }

  final case class GetTreeUpdatesResponse(
      update: TreeUpdate,
      domainId: DomainId,
  )
  def lapiTreeToJavaTree(
      tree: lapi.transaction.TransactionTree
  ): com.daml.ledger.javaapi.data.TransactionTree = {
    val treeProto = scalapbToJava(tree)(_.companion)
    com.daml.ledger.javaapi.data.TransactionTree.fromProto(treeProto)
  }

  object GetTreeUpdatesResponse {

    import lapi.update_service.GetUpdateTreesResponse.Update as TU

    private[splice] def fromProto(
        proto: lapi.update_service.GetUpdateTreesResponse
    ): Option[GetTreeUpdatesResponse] = {
      proto.update match {
        case TU.TransactionTree(tree) =>
          val javaTree = lapiTreeToJavaTree(tree)
          val update = TransactionTreeUpdate(javaTree)
          Some(GetTreeUpdatesResponse(update, DomainId.tryFromString(tree.domainId)))

        case TU.Reassignment(x) =>
          val domainIdP = x.event match {
            case lapi.reassignment.Reassignment.Event.Empty =>
              sys.error("uninitialized update service result (event)")
            case lapi.reassignment.Reassignment.Event.UnassignedEvent(unassign) => unassign.source
            case lapi.reassignment.Reassignment.Event.AssignedEvent(assign) => assign.target
          }
          Some(
            GetTreeUpdatesResponse(
              ReassignmentUpdate(Reassignment.fromProto(x)),
              DomainId.tryFromString(domainIdP),
            )
          )

        case TU.OffsetCheckpoint(_) => None

        case TU.Empty => sys.error("uninitialized update service result (update)")
      }
    }
  }

  sealed abstract class ReassignmentCommand extends Product with Serializable

  object ReassignmentCommand {
    final case class Unassign(
        contractId: ContractId[_],
        source: DomainId,
        target: DomainId,
    ) extends ReassignmentCommand {
      def toProto: lapi.reassignment_command.UnassignCommand =
        lapi.reassignment_command.UnassignCommand(
          contractId = contractId.contractId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }

    object Out {
      implicit val pretty: Pretty[Unassign] = {
        import Pretty.*
        prettyOfClass[Unassign](
          param("contractId", t => t.contractId),
          param("source", _.source),
          param("target", _.target),
        )
      }
    }

    final case class Assign(
        unassignId: String,
        source: DomainId,
        target: DomainId,
    ) extends ReassignmentCommand {
      def toProto: lapi.reassignment_command.AssignCommand =
        lapi.reassignment_command.AssignCommand(
          unassignId = unassignId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }
  }

  final case class ReassignmentSubmitRequest(
      applicationId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: ReassignmentCommand,
  ) {
    def toProto: lapi.command_submission_service.SubmitReassignmentRequest = {
      val baseCommand = lapi.reassignment_command.ReassignmentCommand(
        applicationId = applicationId,
        commandId = commandId,
        submissionId = submissionId,
        submitter = submitter.toProtoPrimitive,
      )
      val updatedCommand = command match {
        case unassign: ReassignmentCommand.Unassign =>
          baseCommand.withUnassignCommand(unassign.toProto)
        case assign: ReassignmentCommand.Assign =>
          baseCommand.withAssignCommand(assign.toProto)
      }
      lapi.command_submission_service.SubmitReassignmentRequest(
        Some(updatedCommand)
      )
    }
  }

  final case class Signature(
      signature: ByteString,
      signedBy: Fingerprint,
  )

  final case class CompletionStreamResponse(laterOffset: Long, completion: Completion)

  object CompletionStreamResponse {
    def fromProto(
        spb: lapi.command_completion_service.CompletionStreamResponse
    ): CompletionStreamResponse = {
      val offset: Long = spb.completionResponse match {
        case lapi.command_completion_service.CompletionStreamResponse.CompletionResponse
              .Completion(completion) =>
          completion.offset
        case lapi.command_completion_service.CompletionStreamResponse.CompletionResponse
              .OffsetCheckpoint(checkpoint) =>
          checkpoint.offset
        case lapi.command_completion_service.CompletionStreamResponse.CompletionResponse.Empty =>
          throw GrpcStatus.INTERNAL
            .withDescription(s"Unexpected completion response: ${spb.completionResponse}")
            .asRuntimeException
      }
      CompletionStreamResponse(
        offset,
        Completion.fromProto(spb.getCompletion),
      )
    }
  }

  import com.daml.error.utils.ErrorDetails
  import ErrorDetails.ErrorDetail

  final case class Completion(
      applicationId: String,
      commandId: String,
      submissionId: String,
      updateId: String,
      status: GrpcStatus,
      errorDetails: Seq[ErrorDetail],
  ) {
    def matchesSubmission(applicationId: String, commandId: String, submissionId: String): Boolean =
      this.applicationId == applicationId &&
        commandId == this.commandId &&
        submissionId == this.submissionId
  }

  object Completion {
    def fromProto(spb: lapi.completion.Completion): Completion = {
      // ignoring transactionId, actAs, deduplicationPeriod

      val (grpcStatus, errors) =
        spb.status map parseStatusScalapb getOrElse ((GrpcStatus.Code.UNKNOWN.toStatus, Seq.empty))

      Completion(
        applicationId = spb.applicationId,
        commandId = spb.commandId,
        submissionId = spb.submissionId,
        updateId = spb.updateId,
        status = grpcStatus,
        errorDetails = errors,
      )
    }
    @throws[IllegalStateException]
    private def parseStatusScalapb(
        spb: com.google.rpc.status.Status
    ): (GrpcStatus, Seq[ErrorDetail]) = {
      val jpb = scalapbToJava(spb)(_.companion)
      (GrpcStatus fromCodeValue jpb.getCode withDescription jpb.getMessage, ErrorDetails from jpb)
    }
  }

  @inline
  private def scalapbToJava[S, J](s: S)(companion: S => scalapb.JavaProtoSupport[_ >: S, J]): J =
    companion(s).toJavaProto(s)
}
