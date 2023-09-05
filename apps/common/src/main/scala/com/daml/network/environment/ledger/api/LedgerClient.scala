package com.daml.network.environment.ledger.api

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.akka.ClientAdapter
import com.daml.ledger.api.v1.*
import com.daml.ledger.api.v1.admin.*
import com.daml.ledger.javaapi.data.{
  CreateUserRequest,
  CreateUserResponse,
  GetLedgerEndResponse,
  GetUserRequest,
  GrantUserRightsRequest,
  LedgerOffset,
  ListUsersRequest,
  ListUserRightsRequest,
  ListUserRightsResponse,
  RevokeUserRightsRequest,
  TransactionTree,
  User,
}
import com.daml.ledger.javaapi.data.codegen.{ContractId, HasCommands}
import com.daml.network.auth.AuthToken
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.util.DisclosedContracts
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.data.PartyDetails
import com.digitalasset.canton.ledger.api.auth.client.LedgerCallCredentials
import com.digitalasset.canton.ledger.client.GrpcChannel
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.Pretty
import com.daml.ledger.api.v1 as lapiv1
import com.daml.ledger.api.v2 as lapi
import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.{ByteString, Duration, FieldMask}
import io.grpc.{Channel, StatusRuntimeException, Status as GrpcStatus}
import io.grpc.stub.{AbstractStub, StreamObserver}

import java.io.Closeable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

sealed abstract class DedupConfig

final case object NoDedup extends DedupConfig

final case class DedupOffset(offset: String) extends DedupConfig

final case class DedupDuration(duration: Duration) extends DedupConfig

/** Ledger client built on top of the Java bindings. The Java equivalent of
  * com.daml.ledger.client.LedgerClient.
  * The methods here expose the underlying gRPC methods more or less directly with
  * two adjustments:
  * 1. They translate to/from types in javaapi.data.
  * 2. They convert to futures and akka sources for akka.
  * All functionality built on top of that is part of CNLedgerConnection.
  */
private[environment] class LedgerClient(
    channel: Channel,
    getToken: () => Future[Option[AuthToken]],
)(implicit
    esf: ExecutionSequencerFactory,
    ec: ExecutionContext,
    elc: ErrorLoggingContext,
) extends Closeable {
  import LedgerClient.{CompletionStreamResponse, GetUpdatesRequest, SubmitAndWaitFor}

  private def withCredentials[T <: AbstractStub[T]](
      stub: T
  ): Future[T] = {
    getToken().map { token =>
      token.fold(stub) { token =>
        stub.withCallCredentials(new LedgerCallCredentials(token.accessToken))
      }
    }
  }

  private val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
    CommandServiceGrpc.newStub(channel)
  private val transactionServiceStub: TransactionServiceGrpc.TransactionServiceStub =
    TransactionServiceGrpc.newStub(channel)
  private val packageServiceStub: PackageServiceGrpc.PackageServiceStub =
    PackageServiceGrpc.newStub(channel)
  private val packageManagementServiceStub
      : PackageManagementServiceGrpc.PackageManagementServiceStub =
    PackageManagementServiceGrpc.newStub(channel)
  private val partyManagementServiceStub: PartyManagementServiceGrpc.PartyManagementServiceStub =
    PartyManagementServiceGrpc.newStub(channel)
  private val userManagementServiceStub: UserManagementServiceGrpc.UserManagementServiceStub =
    UserManagementServiceGrpc.newStub(channel)
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
      : lapiv1.admin.identity_provider_config_service.IdentityProviderConfigServiceGrpc.IdentityProviderConfigServiceStub =
    lapiv1.admin.identity_provider_config_service.IdentityProviderConfigServiceGrpc.stub(channel)

  private def wrapFuture[T](
      f: (StreamObserver[T] => Unit)
  )(implicit elc: ErrorLoggingContext): Future[T] = {
    val futureObserver = new LedgerClient.FutureObserver[T]
    f(futureObserver)
    futureObserver.promise.future
  }

  private def toSource[T](f: Future[Source[T, NotUsed]]) =
    Source.futureSource(f).mapMaterializedValue(_ => NotUsed)

  override def close(): Unit = GrpcChannel.close(channel)

  def ledgerEnd(): Future[ParticipantOffset.Value.Absolute] = {
    val req = lapi.state_service.GetLedgerEndRequest()
    for {
      stub <- withCredentials(stateServiceStub)
      offset <- stub.getLedgerEnd(req).map { resp =>
        val participantOffset =
          resp.offset
            .getOrElse(throw new RuntimeException("Ledger end should return absolute value"))
        val absolute = participantOffset.value.absolute
          .getOrElse(throw new RuntimeException("Ledger end should return absolute value"))

        ParticipantOffset.Value.Absolute(absolute)
      }
    } yield offset
  }

  def participantLedgerEnd(): Future[LedgerOffset] = {
    val req = TransactionServiceOuterClass.GetLedgerEndRequest.newBuilder.build
    for {
      stub <- withCredentials(transactionServiceStub)
      res <- wrapFuture(stub.getLedgerEnd(req, _))
        .map(GetLedgerEndResponse.fromProto(_).getOffset)
    } yield res
  }

  def activeContracts(
      request: lapi.state_service.GetActiveContractsRequest
  ): Source[lapi.state_service.GetActiveContractsResponse, NotUsed] =
    toSource(
      for {
        stub <- withCredentials(stateServiceStub)
      } yield ClientAdapter
        .serverStreaming(request, stub.getActiveContracts)
    )

  def tryGetTransactionTreeByEventId(
      parties: Seq[String],
      id: String,
  ): Future[TransactionTree] = {
    val req = TransactionServiceOuterClass.GetTransactionByEventIdRequest.newBuilder
      .setEventId(id)
      .addAllRequestingParties(parties.asJava)
      .build()
    for {
      stub <- withCredentials(transactionServiceStub)
      res <- wrapFuture(stub.getTransactionByEventId(req, _)).map { resp =>
        TransactionTree.fromProto(resp.getTransaction)
      }
    } yield res
  }

  def updates(request: GetUpdatesRequest): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    toSource(
      for {
        stub <- withCredentials(updateServiceStub)
      } yield ClientAdapter
        .serverStreaming(request.toProto, stub.getUpdateTrees)
        .map(GetTreeUpdatesResponse.fromProto)
    )
  }

  def submitAndWait[Z](
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[HasCommands],
      disclosedContracts: DisclosedContracts,
      waitFor: SubmitAndWaitFor[Z],
  )(implicit ec: ExecutionContext): Future[Z] = {
    val commandsBuilder = CommandsOuterClass.Commands.newBuilder
      .setWorkflowId(workflowId)
      .setCommandId(commandId)
      .setApplicationId(applicationId)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .addAllCommands {
        HasCommands.toCommands(commands.asJava).asScala.map(_.toProtoCommand).asJava
      }
      .addAllDisclosedContracts(disclosedContracts.toLedgerApiDisclosedContracts.asJava)
    deduplicationConfig match {
      case DedupOffset(offset) =>
        commandsBuilder.setDeduplicationOffset(offset)
      case DedupDuration(duration) =>
        commandsBuilder.setDeduplicationDuration(duration)
      case NoDedup =>
    }

    val request = CommandServiceOuterClass.SubmitAndWaitRequest
      .newBuilder()
      .setCommands(commandsBuilder.build)
      .build()

    for {
      stub <- withCredentials(commandServiceStub)
      res <- wrapFuture(
        waitFor.stubSubmit(stub, request, _)
      ).map(waitFor.mapResponse)
    } yield res
  }

  def listPackages()(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val request = PackageServiceOuterClass.ListPackagesRequest.newBuilder().build()
    for {
      stub <- withCredentials(packageServiceStub)
      res <- wrapFuture(stub.listPackages(request, _))
        .map(_.getPackageIdsList.asScala.toSeq)
    } yield res
  }

  def uploadDarFile(darFile: ByteString)(implicit ec: ExecutionContext): Future[Unit] = {
    val request = PackageManagementServiceOuterClass.UploadDarFileRequest
      .newBuilder()
      .setDarFile(darFile)
      .build
    for {
      stub <- withCredentials(packageManagementServiceStub)
      res <- wrapFuture(stub.uploadDarFile(request, _)).map(_ => ())
    } yield res
  }

  def listUsersProto(
      pageToken: Option[String],
      pageSize: Int = 100,
      identityProviderId: Option[String] = None,
  )(implicit
      ec: ExecutionContext
  ): Future[(Seq[UserManagementServiceOuterClass.User], Option[String])] = {
    val requestBuilder = new ListUsersRequest(pageToken.toJava, pageSize).toProto.toBuilder
    identityProviderId.foreach(requestBuilder.setIdentityProviderId(_))
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.listUsers(requestBuilder.build, _))

    } yield (
      res.getUsersList().asScala.toSeq,
      Option.when(res.getNextPageToken() != "")(res.getNextPageToken()),
    )
  }

  def listUsers(pageToken: Option[String], pageSize: Int = 100)(implicit
      ec: ExecutionContext
  ): Future[(Seq[User], Option[String])] =
    listUsersProto(pageToken, pageSize).map { case (users, nextPage) =>
      (users.map(User.fromProto(_)), nextPage)
    }

  def getUserProto(
      userId: String,
      identityProviderId: Option[String],
  )(implicit ec: ExecutionContext): Future[UserManagementServiceOuterClass.User] = {
    val requestBuilder = new GetUserRequest(userId).toProto.toBuilder
    identityProviderId.foreach(requestBuilder.setIdentityProviderId(_))
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.getUser(requestBuilder.build, _)).map(_.getUser)
    } yield res
  }

  def getUser(userId: String, identityProviderId: Option[String])(implicit
      ec: ExecutionContext
  ): Future[User] =
    getUserProto(userId, identityProviderId).map(User.fromProto(_))

  def getOrCreateUser(
      user: User,
      initialRights: Seq[User.Right],
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext
  ): Future[User] = {
    getUser(user.getId(), identityProviderId).recoverWith {
      case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
        createUser(user, initialRights, identityProviderId)
    }
  }

  def getParties(
      parties: Seq[PartyId]
  )(implicit ec: ExecutionContext): Future[Seq[PartyDetails]] = {
    import com.daml.ledger.api.v1.admin.party_management_service.PartyDetails as ScalaPartyDetails
    val requestBuilder = PartyManagementServiceOuterClass.GetPartiesRequest.newBuilder
    requestBuilder.addAllParties(parties.map(_.toProtoPrimitive).asJava)
    for {
      stub <- withCredentials(partyManagementServiceStub)
      res <- wrapFuture(stub.getParties(requestBuilder.build, _)).map(r =>
        r.getPartyDetailsList.asScala.toSeq.map(details =>
          PartyDetails.fromProtoPartyDetails(ScalaPartyDetails.fromJavaProto(details))
        )
      )
    } yield res
  }

  def createUser(user: User, initialRights: Seq[User.Right], identityProviderId: Option[String])(
      implicit ec: ExecutionContext
  ): Future[User] = {
    val request = initialRights match {
      case hd +: tl => new CreateUserRequest(user, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("createUser requires at least one right")
    }
    val requestBuilder = request.toBuilder
    val userBuilder = request.getUser.toBuilder
    identityProviderId.foreach(
      userBuilder.setIdentityProviderId(_)
    )
    requestBuilder.setUser(userBuilder.build)
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.createUser(requestBuilder.build, _)).map(r =>
        CreateUserResponse.fromProto(r).getUser
      )
    } yield res
  }

  def setUserPrimaryParty(
      userId: String,
      primaryParty: PartyId,
      identityProviderId: Option[String],
  )(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    for {
      user <- getUserProto(userId, identityProviderId)
      newUser = user.toBuilder.setPrimaryParty(primaryParty.toProtoPrimitive).build
      _ <- updateUser(newUser, FieldMask.newBuilder.addPaths("primary_party").build)
    } yield ()
  }

  def updateUser(user: UserManagementServiceOuterClass.User, mask: FieldMask): Future[Unit] = {
    val requestBuilder = admin.UserManagementServiceOuterClass.UpdateUserRequest.newBuilder()
    requestBuilder.setUser(user)
    requestBuilder.setUpdateMask(mask)
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.updateUser(requestBuilder.build, _))
    } yield res
  }.map(_ => ())

  def listUserRights(userId: String)(implicit
      ec: ExecutionContext
  ): Future[Seq[User.Right]] = {
    val request = new ListUserRightsRequest(userId).toProto
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.listUserRights(request, _)).map(r =>
        ListUserRightsResponse.fromProto(r).getRights.asScala.toSeq
      )
    } yield res
  }

  def grantUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    val request = rights match {
      case hd +: tl => new GrantUserRightsRequest(userId, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("grantUserRights requires at least one right")
    }
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.grantUserRights(request, _)).map(_ => ())
    } yield res
  }

  def revokeUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    val request = rights match {
      case hd +: tl => new RevokeUserRightsRequest(userId, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("revokeUserRights requires at least one right")
    }
    for {
      stub <- withCredentials(userManagementServiceStub)
      res <- wrapFuture(stub.revokeUserRights(request, _)).map(_ => ())
    } yield res
  }

  def submitReassignment(
      applicationId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: LedgerClient.ReassignmentCommand,
  ): Future[Unit] =
    for {
      stub <- withCredentials(commandSubmissionServiceStub)
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
      begin: Option[ParticipantOffset],
  ): Source[CompletionStreamResponse, NotUsed] =
    toSource(
      for {
        stub <- withCredentials(multidomainCompletionServiceStub)
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
  ): Future[Map[DomainAlias, DomainId]] = {
    val req = lapi.state_service.GetConnectedDomainsRequest(
      party = party.toProtoPrimitive
    )
    for {
      stub <- withCredentials(stateServiceStub)
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
  ): Future[Unit] = {
    for {
      stub <- withCredentials(identityProviderConfigServiceStub)
      _ <- stub.createIdentityProviderConfig(
        lapiv1.admin.identity_provider_config_service.CreateIdentityProviderConfigRequest(
          Some(
            lapiv1.admin.identity_provider_config_service.IdentityProviderConfig(
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

  private def filterForParty(partyId: PartyId): lapi.transaction_filter.TransactionFilter = {

    val filters = com.daml.ledger.api.v1.transaction_filter.Filters(
      Some(com.daml.ledger.api.v1.transaction_filter.InclusiveFilters(Nil, Nil))
    )

    lapi.transaction_filter.TransactionFilter(
      Map(partyId.toLf -> filters)
    )

  }

  final case class GetUpdatesRequest(
      begin: ParticipantOffset,
      end: Option[ParticipantOffset],
      party: PartyId,
  ) {
    private[LedgerClient] def toProto: lapi.update_service.GetUpdatesRequest =
      lapi.update_service.GetUpdatesRequest(
        beginExclusive = Some(begin),
        endInclusive = end,
        filter = Some(filterForParty(party)),
      )
  }

  private[environment] sealed abstract class SubmitAndWaitFor[+Z] {
    import SubmitAndWaitFor.*
    private[LedgerClient] type RawResponse
    private[LedgerClient] val stubSubmit: StubSubmit[RawResponse]
    private[LedgerClient] val mapResponse: RawResponse => Z
  }

  private[environment] object SubmitAndWaitFor {
    import com.daml.ledger.api.v1.CommandServiceOuterClass as CSOC
    import com.daml.ledger.javaapi.data as jdata

    val CompletionOffset: SubmitAndWaitFor[String] =
      impl((_: CSOC.SubmitAndWaitForTransactionIdResponse).getCompletionOffset)(
        _.submitAndWaitForTransactionId(_, _)
      )

    val Transaction: SubmitAndWaitFor[jdata.Transaction] =
      impl { response: CSOC.SubmitAndWaitForTransactionResponse =>
        jdata.Transaction.fromProto(response.getTransaction)
      }(_.submitAndWaitForTransaction(_, _))

    val TransactionTree: SubmitAndWaitFor[jdata.TransactionTree] =
      impl { response: CSOC.SubmitAndWaitForTransactionTreeResponse =>
        jdata.TransactionTree.fromProto(response.getTransaction)
      }(_.submitAndWaitForTransactionTree(_, _))

    private type StubSubmit[R] = (
        CommandServiceGrpc.CommandServiceStub,
        CSOC.SubmitAndWaitRequest,
        StreamObserver[R],
    ) => Unit

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

  object GetTreeUpdatesResponse {

    import lapi.update_service.GetUpdateTreesResponse.Update as TU

    private[LedgerClient] def fromProto(
        proto: lapi.update_service.GetUpdateTreesResponse
    ): GetTreeUpdatesResponse = {
      proto.update match {
        case TU.TransactionTree(tree) =>
          // TODO(#5713) Avoid having to convert to the old Java bindings type.
          import io.scalaland.chimney.dsl.*
          val treeV1 = tree.into[com.daml.ledger.api.v1.transaction.TransactionTree].transform

          val update = TransactionTreeUpdate(
            TransactionTree fromProto scalapbToJava(treeV1)(_.companion)
          )
          GetTreeUpdatesResponse(update, DomainId.tryFromString(tree.domainId))

        case TU.Reassignment(x) =>
          val domainIdP = x.event match {
            case lapi.reassignment.Reassignment.Event.Empty =>
              sys.error("uninitialized update service result (event)")
            case lapi.reassignment.Reassignment.Event.UnassignedEvent(unassign) => unassign.source
            case lapi.reassignment.Reassignment.Event.AssignedEvent(assign) => assign.target
          }
          GetTreeUpdatesResponse(
            ReassignmentUpdate(Reassignment.fromProto(x)),
            DomainId.tryFromString(domainIdP),
          )

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

  final case class CompletionStreamResponse(laterOffset: LedgerOffset, completion: Completion)

  object CompletionStreamResponse {
    def fromProto(
        spb: lapi.command_completion_service.CompletionStreamResponse
    ): CompletionStreamResponse = {
      val offset = (for {
        checkpoint <- spb.checkpoint
        offset <- checkpoint.offset
      } yield offset)
        .getOrElse(throw new IllegalArgumentException("missing offset in CompletionStreamResponse"))
      // ignoring checkpoint.record_time
      CompletionStreamResponse(
        LedgerOffset fromProto scalapbToJava(offset)(_.companion),
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
      status: GrpcStatus,
      errorDetails: Seq[ErrorDetail],
  ) {
    def matchesSubmission(applicationId: String, commandId: String, submissionId: String): Boolean =
      this.applicationId == applicationId &&
        this.commandId == commandId &&
        this.submissionId == submissionId
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
