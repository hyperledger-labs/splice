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
import com.daml.ledger.api.v2.interactive.interactive_submission_service.InteractiveSubmissionServiceGrpc
import com.daml.ledger.api.v2.command_service.CommandServiceGrpc
import com.daml.ledger.api.v2.offset_checkpoint.OffsetCheckpoint.toJavaProto
import com.daml.ledger.api.v2.package_reference.PackageReference
import com.daml.ledger.api.v2.package_service.{ListPackagesRequest, PackageServiceGrpc}
import com.daml.ledger.javaapi.data.{
  Command,
  CreateUserResponse,
  ListUserRightsResponse,
  OffsetCheckpoint,
  User,
}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.User.Right
import org.lfdecentralizedtrust.splice.auth.AuthToken
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionFilter
import org.lfdecentralizedtrust.splice.util.DisclosedContracts
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.parties.PartyDetails
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.ledger.client.GrpcChannel
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
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

final case class DedupOffset(offset: Long) extends DedupConfig {
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
  ): Future[Long] = {
    val req = lapi.state_service.GetLedgerEndRequest()
    for {
      stub <- withCredentialsAndTraceContext(stateServiceStub)
      resp <- stub.getLedgerEnd(req)
    } yield resp.offset
  }

  def latestPrunedOffset()(implicit
      traceContext: TraceContext
  ): Future[Long] = {
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

  def updates(
      request: GetUpdatesRequest
  )(implicit tc: TraceContext): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    toSource(
      for {
        stub <- withCredentialsAndTraceContext(updateServiceStub)
      } yield ClientAdapter
        .serverStreaming(request.toProto, stub.getUpdates)
        .mapConcat(GetTreeUpdatesResponse.fromProto)
    )
  }

  def submitAndWait[Z](
      synchronizerId: String,
      userId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
      waitFor: SubmitAndWaitFor[Z],
      deadline: Option[NonNegativeFiniteDuration] = None,
      preferredPackageIds: Seq[String] = Seq.empty,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Z] = {
    val commandsBuilder = CommandsOuterClass.Commands.newBuilder
      .setSynchronizerId(synchronizerId)
      .setCommandId(commandId)
      .setUserId(userId)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .addAllCommands {
        commands.map(_.toProtoCommand).asJava
      }
      .addAllPackageIdSelectionPreference(preferredPackageIds.asJava)
      .addAllDisclosedContracts(disclosedContracts.toLedgerApiDisclosedContracts.asJava)
    deduplicationConfig match {
      case DedupOffset(offset) =>
        // Canton does not allow a zero offset (ledger begin) so just go for
        // not specfying anything which means max deduplication duration.
        if (offset > 0) {
          commandsBuilder.setDeduplicationOffset(offset)
        }
      case DedupDuration(duration) =>
        commandsBuilder.setDeduplicationDuration(duration)
      case NoDedup =>
    }

    val request = CommandServiceOuterClass.SubmitAndWaitForTransactionRequest
      .newBuilder()
      .setCommands(commandsBuilder.build)
      .setTransactionFormat(
        transaction_filter.TransactionFormat.toJavaProto(
          transaction_filter.TransactionFormat(
            eventFormat = Some(
              transaction_filter.EventFormat(
                filtersByParty = actAs
                  .map(p =>
                    p -> com.daml.ledger.api.v2.transaction_filter.Filters(
                      Seq(
                        com.daml.ledger.api.v2.transaction_filter.CumulativeFilter(
                          com.daml.ledger.api.v2.transaction_filter.CumulativeFilter.IdentifierFilter
                            .WildcardFilter(
                              com.daml.ledger.api.v2.transaction_filter.WildcardFilter(false)
                            )
                        )
                      )
                    )
                  )
                  .toMap
              )
            ),
            transactionShape = transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
          )
        )
      )
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
      synchronizerId: Option[String],
      userId: String,
      commandId: String,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
      verboseHashing: Boolean,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[lapi.interactive.interactive_submission_service.PrepareSubmissionResponse] = {
    for {
      stub <- withCredentialsAndTraceContext(interactiveSubmissionServiceStub)
      result <- stub.prepareSubmission(
        lapi.interactive.interactive_submission_service.PrepareSubmissionRequest(
          commands = commands.map(c => lapi.commands.Command.fromJavaProto(c.toProtoCommand)),
          disclosedContracts = disclosedContracts.toLedgerApiDisclosedContracts.map(
            lapi.commands.DisclosedContract.fromJavaProto(_)
          ),
          synchronizerId = synchronizerId.getOrElse(""),
          userId = userId,
          commandId = commandId,
          actAs = actAs,
          readAs = readAs,
          verboseHashing = verboseHashing,
        )
      )
    } yield result
  }

  def executeSubmission(
      preparedTransaction: interactive.interactive_submission_service.PreparedTransaction,
      partySignatures: Map[PartyId, LedgerClient.Signature],
      userId: String,
      submissionId: String,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[lapi.interactive.interactive_submission_service.ExecuteSubmissionResponse] =
    for {
      stub <- withCredentialsAndTraceContext(interactiveSubmissionServiceStub)
      result <- stub.executeSubmission(
        lapi.interactive.interactive_submission_service.ExecuteSubmissionRequest(
          preparedTransaction = Some(preparedTransaction),
          partySignatures = Some(
            lapi.interactive.interactive_submission_service
              .PartySignatures(partySignatures.toList.map { case (party, signature) =>
                lapi.interactive.interactive_submission_service.SinglePartySignatures(
                  party.toProtoPrimitive,
                  Seq(
                    lapi.interactive.interactive_submission_service.Signature(
                      lapi.interactive.interactive_submission_service.SignatureFormat.SIGNATURE_FORMAT_RAW,
                      signature.signature,
                      signature.signedBy.toProtoPrimitive,
                      lapi.interactive.interactive_submission_service.SigningAlgorithmSpec.SIGNING_ALGORITHM_SPEC_ED25519,
                    )
                  ),
                )
              })
          ),
          userId = userId,
          submissionId = submissionId,
          hashingSchemeVersion =
            lapi.interactive.interactive_submission_service.HashingSchemeVersion.HASHING_SCHEME_VERSION_V2,
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

  def listUsersProto(
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
      isDeactivated: Boolean,
      annotations: Map[String, String],
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[User] = {
    getUser(user.getId(), identityProviderId).recoverWith {
      case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
        createUser(user, initialRights, isDeactivated, annotations, identityProviderId)
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
      isDeactivated: Boolean,
      annotations: Map[String, String],
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
            .withIsDeactivated(isDeactivated)
            .withIdentityProviderId(identityProviderId.getOrElse(""))
            .withMetadata(
              object_meta.ObjectMeta.fromJavaProto(
                ObjectMetaOuterClass.ObjectMeta.newBuilder
                  .putAllAnnotations(annotations.asJava)
                  .build
              )
            )
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
    case as: Right.CanExecuteAs =>
      v1User.Right.defaultInstance.withCanExecuteAs(v1User.Right.CanExecuteAs(as.party))
    case _: Right.IdentityProviderAdmin =>
      v1User.Right.defaultInstance.withIdentityProviderAdmin(
        v1User.Right.IdentityProviderAdmin()
      )
    case _: Right.ParticipantAdmin =>
      v1User.Right.defaultInstance.withParticipantAdmin(v1User.Right.ParticipantAdmin())
    case _: Right.CanReadAsAnyParty =>
      v1User.Right.defaultInstance.withCanReadAsAnyParty(v1User.Right.CanReadAsAnyParty())
    case _: Right.CanExecuteAsAnyParty =>
      v1User.Right.defaultInstance.withCanExecuteAsAnyParty(v1User.Right.CanExecuteAsAnyParty())
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

  def listUserRights(userId: String, identityProviderId: Option[String] = None)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[User.Right]] = {
    val request = v1User.ListUserRightsRequest(userId, identityProviderId.getOrElse(""))
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
      userId: String,
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
              userId,
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
      userId: String,
      parties: Seq[PartyId],
      begin: Long,
  )(implicit tc: TraceContext): Source[CompletionStreamResponse, NotUsed] =
    toSource(
      for {
        stub <- withCredentialsAndTraceContext(multidomainCompletionServiceStub)
      } yield ClientAdapter.serverStreaming(
        lapi.command_completion_service.CompletionStreamRequest(
          userId = userId,
          parties = parties.map(_.toProtoPrimitive),
          beginExclusive = begin,
        ),
        stub.completionStream,
      ) map CompletionStreamResponse.fromProto
    )

  def getConnectedDomains(
      party: PartyId
  )(implicit tc: TraceContext): Future[Map[SynchronizerAlias, SynchronizerId]] = {
    val req = lapi.state_service.GetConnectedSynchronizersRequest(
      party = party.toProtoPrimitive
    )
    for {
      stub <- withCredentialsAndTraceContext(stateServiceStub)
      res <- stub.getConnectedSynchronizers(req).map { resp =>
        resp.connectedSynchronizers.map { cd =>
          SynchronizerAlias.tryCreate(cd.synchronizerAlias) -> SynchronizerId.tryFromString(
            cd.synchronizerId
          )
        }.toMap
      }
    } yield res
  }

  def listIdentityProviderConfigs(
  )(implicit
      tc: TraceContext
  ): Future[Seq[identity_provider_config_service.IdentityProviderConfig]] = {
    for {
      stub <- withCredentialsAndTraceContext(identityProviderConfigServiceStub)
      res <- stub
        .listIdentityProviderConfigs(
          identity_provider_config_service.ListIdentityProviderConfigsRequest()
        )
    } yield res.identityProviderConfigs
  }

  def createIdentityProviderConfig(
      id: String,
      isDeactivated: Boolean,
      jwksUrl: String,
      issuer: String,
      audience: String,
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      stub <- withCredentialsAndTraceContext(identityProviderConfigServiceStub)
      _ <- stub.createIdentityProviderConfig(
        identity_provider_config_service.CreateIdentityProviderConfigRequest(
          Some(
            identity_provider_config_service.IdentityProviderConfig(
              identityProviderId = id,
              isDeactivated = isDeactivated,
              jwksUrl = jwksUrl,
              issuer = issuer,
              audience = audience,
            )
          )
        )
      )
    } yield ()
  }

  def getSupportedPackageVersion(
      synchronizerId: SynchronizerId,
      packageRequirements: Seq[(String, Seq[PartyId])],
      vettingAsOfTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Seq[PackageReference]] = {
    for {
      stub <- withCredentialsAndTraceContext(interactiveSubmissionServiceStub)
      response <- stub.getPreferredPackages(
        lapi.interactive.interactive_submission_service.GetPreferredPackagesRequest(
          packageVettingRequirements = packageRequirements.map { case (pkg, parties) =>
            lapi.interactive.interactive_submission_service.PackageVettingRequirement(
              parties = parties.map(_.toProtoPrimitive),
              packageName = pkg,
            )
          },
          synchronizerId = synchronizerId.toProtoPrimitive,
          vettingValidAt = Some(vettingAsOfTime.toProtoTimestamp),
        )
      )
    } yield {
      response.packageReferences
    }
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
      begin: Long,
      end: Option[Long],
      filter: IngestionFilter,
  ) {
    private[LedgerClient] def toProto: lapi.update_service.GetUpdatesRequest = {
      val eventFormat = filter.toEventFormat
      lapi.update_service.GetUpdatesRequest(
        beginExclusive = begin,
        endInclusive = end,
        updateFormat = Some(
          transaction_filter.UpdateFormat(
            includeTransactions = Some(
              transaction_filter.TransactionFormat(
                eventFormat = Some(eventFormat),
                transactionShape =
                  transaction_filter.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS,
              )
            ),
            includeReassignments = Some(eventFormat),
          )
        ),
      )
    }
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
      impl((r: CSOC.SubmitAndWaitResponse) => r.getCompletionOffset)(
        { case (stub, r, ec) =>
          stub
            .submitAndWait(
              command_service.SubmitAndWaitRequest.fromJavaProto(
                CSOC.SubmitAndWaitRequest.newBuilder().setCommands(r.getCommands).build
              )
            )
            .map(r => command_service.SubmitAndWaitResponse.toJavaProto(r))(ec)
        }
      )

    val TransactionTree: SubmitAndWaitFor[jdata.Transaction] =
      impl((response: CSOC.SubmitAndWaitForTransactionResponse) =>
        jdata.Transaction.fromProto(response.getTransaction)
      ) {
        { case (stub, r, ec) =>
          stub
            .submitAndWaitForTransaction(
              command_service.SubmitAndWaitForTransactionRequest.fromJavaProto(r)
            )
            .map(r => command_service.SubmitAndWaitForTransactionResponse.toJavaProto(r))(ec)
        }
      }

    private type StubSubmit[R] = (
        CommandServiceGrpc.CommandServiceStub,
        CSOC.SubmitAndWaitForTransactionRequest,
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
      updateOrCheckpoint: TreeUpdateOrOffsetCheckpoint
  )
  def lapiTreeToJavaTree(
      tree: lapi.transaction.Transaction
  ): com.daml.ledger.javaapi.data.Transaction = {
    val treeProto = scalapbToJava(tree)(_.companion)
    com.daml.ledger.javaapi.data.Transaction.fromProto(treeProto)
  }

  object GetTreeUpdatesResponse {

    import lapi.update_service.GetUpdatesResponse.Update as TU

    private[splice] def fromProto(
        proto: lapi.update_service.GetUpdatesResponse
    ): Option[GetTreeUpdatesResponse] = {
      proto.update match {
        case TU.Transaction(tree) =>
          val javaTree = lapiTreeToJavaTree(tree)
          val update = TransactionTreeUpdate(javaTree)
          Some(
            GetTreeUpdatesResponse(
              TreeUpdateOrOffsetCheckpoint.Update(
                update,
                SynchronizerId.tryFromString(tree.synchronizerId),
              )
            )
          )

        case TU.Reassignment(x) =>
          // TODO(DACH-NY/canton-network-internal#361) Support reassignment batching
          val event: lapi.reassignment.ReassignmentEvent = x.events match {
            case Seq(event) => event
            case events =>
              throw GrpcStatus.INTERNAL
                .withDescription(s"Reassignment batching is not currently supported: $events")
                .asRuntimeException
          }
          val synchronizerIdP = event.event match {
            case lapi.reassignment.ReassignmentEvent.Event.Empty =>
              sys.error("uninitialized update service result (event)")
            case lapi.reassignment.ReassignmentEvent.Event.Unassigned(unassign) => unassign.source
            case lapi.reassignment.ReassignmentEvent.Event.Assigned(assign) => assign.target
          }
          Some(
            GetTreeUpdatesResponse(
              TreeUpdateOrOffsetCheckpoint.Update(
                ReassignmentUpdate(Reassignment.fromProto(x)),
                SynchronizerId.tryFromString(synchronizerIdP),
              )
            )
          )

        case TU.OffsetCheckpoint(offset) =>
          Some(
            GetTreeUpdatesResponse(
              TreeUpdateOrOffsetCheckpoint.Checkpoint(
                OffsetCheckpoint.fromProto(toJavaProto(offset))
              )
            )
          )

        case TU.TopologyTransaction(_) => None

        case TU.Empty => sys.error("uninitialized update service result (update)")
      }
    }
  }

  sealed abstract class ReassignmentCommand extends Product with Serializable

  object ReassignmentCommand {
    final case class Unassign(
        contractId: ContractId[_],
        source: SynchronizerId,
        target: SynchronizerId,
    ) extends ReassignmentCommand {
      def toProto: lapi.reassignment_commands.UnassignCommand =
        lapi.reassignment_commands.UnassignCommand(
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
        source: SynchronizerId,
        target: SynchronizerId,
    ) extends ReassignmentCommand {
      def toProto: lapi.reassignment_commands.AssignCommand =
        lapi.reassignment_commands.AssignCommand(
          reassignmentId = unassignId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }
  }

  final case class ReassignmentSubmitRequest(
      userId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: ReassignmentCommand,
  ) {
    def toProto: lapi.command_submission_service.SubmitReassignmentRequest = {
      val commands = lapi.reassignment_commands.ReassignmentCommands(
        userId = userId,
        commandId = commandId,
        submissionId = submissionId,
        submitter = submitter.toProtoPrimitive,
        commands = Seq(
          command match {
            case unassign: ReassignmentCommand.Unassign =>
              lapi.reassignment_commands.ReassignmentCommand(
                lapi.reassignment_commands.ReassignmentCommand.Command
                  .UnassignCommand(unassign.toProto)
              )
            case assign: ReassignmentCommand.Assign =>
              lapi.reassignment_commands.ReassignmentCommand(
                lapi.reassignment_commands.ReassignmentCommand.Command.AssignCommand(assign.toProto)
              )
          }
        ),
      )
      lapi.command_submission_service.SubmitReassignmentRequest(
        Some(commands)
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

  import com.digitalasset.base.error.utils.ErrorDetails
  import ErrorDetails.ErrorDetail

  final case class Completion(
      userId: String,
      commandId: String,
      submissionId: String,
      updateId: String,
      status: GrpcStatus,
      errorDetails: Seq[ErrorDetail],
  ) {
    def matchesSubmission(userId: String, commandId: String, submissionId: String): Boolean =
      this.userId == userId &&
        commandId == this.commandId &&
        submissionId == this.submissionId
  }

  object Completion {
    def fromProto(spb: lapi.completion.Completion): Completion = {
      // ignoring transactionId, actAs, deduplicationPeriod

      val (grpcStatus, errors) =
        spb.status map parseStatusScalapb getOrElse ((GrpcStatus.Code.UNKNOWN.toStatus, Seq.empty))

      Completion(
        userId = spb.userId,
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
