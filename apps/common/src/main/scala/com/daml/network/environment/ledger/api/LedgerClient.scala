package com.daml.network.environment.ledger.api

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.akka.ClientAdapter
import com.daml.ledger.api.v1.*
import com.daml.ledger.api.v1.admin.*
import com.daml.ledger.javaapi.data.{
  Command,
  CreateUserRequest,
  CreateUserResponse,
  GetLedgerEndResponse,
  GetUserRequest,
  GrantUserRightsRequest,
  LedgerOffset,
  ListUserRightsRequest,
  ListUserRightsResponse,
  RevokeUserRightsRequest,
  Transaction,
  TransactionTree,
  User,
}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.util.DisclosedContracts
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.ledger.api.auth.client.LedgerCallCredentials
import com.digitalasset.canton.ledger.client.GrpcChannel
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.participant.protocol.v0.multidomain
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.{ByteString, Duration}
import com.google.protobuf.empty.Empty
import io.grpc.{Channel, Deadline, StatusRuntimeException, Status as GrpcStatus}
import io.grpc.stub.{AbstractStub, StreamObserver}

import java.io.Closeable
import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

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
class LedgerClient(channel: Channel, token: Option[String])(implicit
    esf: ExecutionSequencerFactory,
    ec: ExecutionContext,
    elc: ErrorLoggingContext,
) extends Closeable {

  private def getReducedDeadline(): Deadline = Deadline.after(10, TimeUnit.SECONDS)

  import LedgerClient.{CompletionStreamResponse, GetUpdatesRequest}

  private val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
    // TODO(#5319) remove deadline after Canton properly produces a response
    withCredentials(CommandServiceGrpc.newStub(channel), token)
  private val transactionServiceStub: TransactionServiceGrpc.TransactionServiceStub =
    withCredentials(TransactionServiceGrpc.newStub(channel), token)
  private val packageServiceStub: PackageServiceGrpc.PackageServiceStub =
    withCredentials(PackageServiceGrpc.newStub(channel), token)
  private val packageManagementServiceStub
      : PackageManagementServiceGrpc.PackageManagementServiceStub =
    withCredentials(PackageManagementServiceGrpc.newStub(channel), token)
  private val partyManagementServiceStub: PartyManagementServiceGrpc.PartyManagementServiceStub =
    withCredentials(PartyManagementServiceGrpc.newStub(channel), token)
  private val userManagementServiceStub: UserManagementServiceGrpc.UserManagementServiceStub =
    withCredentials(UserManagementServiceGrpc.newStub(channel), token)
  private val updateServiceStub: multidomain.UpdateServiceGrpc.UpdateServiceStub =
    withCredentials(multidomain.UpdateServiceGrpc.stub(channel), token)
  private val transferSubmissionServiceStub
      : multidomain.TransferSubmissionServiceGrpc.TransferSubmissionServiceStub =
    withCredentials(multidomain.TransferSubmissionServiceGrpc.stub(channel), token)
  private val multidomainCompletionServiceStub
      : multidomain.CommandCompletionServiceGrpc.CommandCompletionServiceStub =
    withCredentials(multidomain.CommandCompletionServiceGrpc.stub(channel), token)
  private val stateServiceStub: multidomain.StateServiceGrpc.StateServiceStub =
    withCredentials(multidomain.StateServiceGrpc.stub(channel), token)

  private def wrapFuture[T](
      f: (StreamObserver[T] => Unit)
  )(implicit elc: ErrorLoggingContext): Future[T] = {
    val futureObserver = new LedgerClient.FutureObserver[T]
    f(futureObserver)
    futureObserver.promise.future
  }

  private def withCredentials[T <: AbstractStub[T]](
      stub: T,
      token: Option[String],
  ): T = {
    token.fold(stub)(token => stub.withCallCredentials(new LedgerCallCredentials(token)))
  }

  override def close(): Unit = GrpcChannel.close(channel)

  def ledgerEnd(): Future[LedgerOffset.Absolute] = {
    val req = multidomain.GetLedgerEndRequest()
    stateServiceStub.getLedgerEnd(req).map { resp =>
      new LedgerOffset.Absolute(resp.offset)
    }
  }

  def participantLedgerEnd(): Future[LedgerOffset] = {
    val req = TransactionServiceOuterClass.GetLedgerEndRequest.newBuilder.build
    wrapFuture(
      transactionServiceStub.getLedgerEnd(req, _)
    ).map(GetLedgerEndResponse.fromProto(_).getOffset)
  }

  def activeContracts(
      request: multidomain.GetActiveContractsRequest
  ): Source[multidomain.GetActiveContractsResponse, NotUsed] =
    ClientAdapter
      .serverStreaming(request, stateServiceStub.getActiveContracts)

  def tryGetTransactionTreeByEventId(
      parties: Seq[String],
      id: String,
  ): Future[TransactionTree] = {
    val req = TransactionServiceOuterClass.GetTransactionByEventIdRequest.newBuilder
      .setEventId(id)
      .addAllRequestingParties(parties.asJava)
      .build()
    wrapFuture(
      transactionServiceStub
        .getTransactionByEventId(req, _)
    ).map { resp =>
      TransactionTree.fromProto(resp.getTransaction)
    }
  }

  def updates(request: GetUpdatesRequest): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    ClientAdapter
      .serverStreaming(request.toProto, updateServiceStub.getTreeUpdates)
      .map(GetTreeUpdatesResponse.fromProto)
  }

  private def submitAndWaitRequest(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationOffsetOrDuration: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
  ): CommandServiceOuterClass.SubmitAndWaitRequest = {
    val commandsBuilder = CommandsOuterClass.Commands.newBuilder
    commandsBuilder
      .setWorkflowId(workflowId)
      .setCommandId(commandId)
      .setApplicationId(applicationId)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .addAllCommands(commands.map(_.toProtoCommand).asJava)
      .addAllDisclosedContracts(disclosedContracts.toLedgerApiDisclosedContracts.asJava)
    deduplicationOffsetOrDuration match {
      case DedupOffset(offset) =>
        commandsBuilder.setDeduplicationOffset(offset)
      case DedupDuration(duration) =>
        commandsBuilder.setDeduplicationDuration(duration)
      case NoDedup =>
    }

    CommandServiceOuterClass.SubmitAndWaitRequest
      .newBuilder()
      .setCommands(commandsBuilder.build)
      .build()
  }

  def submitAndWait(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
  )(implicit ec: ExecutionContext): Future[String] = {
    val request = submitAndWaitRequest(
      workflowId,
      applicationId,
      commandId,
      deduplicationConfig,
      actAs,
      readAs,
      commands,
      disclosedContracts,
    )
    wrapFuture(
      commandServiceStub
        .withDeadline(getReducedDeadline())
        .submitAndWaitForTransactionId(request, _)
    ).map(response => response.getCompletionOffset)
  }

  def submitAndWaitForTransaction(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
  )(implicit ec: ExecutionContext): Future[Transaction] = {
    val request = submitAndWaitRequest(
      workflowId,
      applicationId,
      commandId,
      deduplicationConfig,
      actAs,
      readAs,
      commands,
      disclosedContracts,
    )
    wrapFuture(
      commandServiceStub.withDeadline(getReducedDeadline()).submitAndWaitForTransaction(request, _)
    ).map(response => Transaction.fromProto(response.getTransaction))
  }

  def submitAndWaitForTransactionTree(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
      disclosedContracts: DisclosedContracts,
  )(implicit ec: ExecutionContext): Future[TransactionTree] = {
    val request = submitAndWaitRequest(
      workflowId,
      applicationId,
      commandId,
      deduplicationConfig,
      actAs,
      readAs,
      commands,
      disclosedContracts,
    )
    wrapFuture(
      commandServiceStub
        .withDeadline(getReducedDeadline())
        .submitAndWaitForTransactionTree(request, _)
    ).map(response => TransactionTree.fromProto(response.getTransaction))
  }

  def listPackages()(implicit ec: ExecutionContext): Future[Seq[String]] = {
    val request = PackageServiceOuterClass.ListPackagesRequest.newBuilder().build()
    wrapFuture(packageServiceStub.listPackages(request, _))
      .map(_.getPackageIdsList.asScala.toSeq)
  }

  def uploadDarFile(darFile: ByteString)(implicit ec: ExecutionContext): Future[Unit] = {
    val request = PackageManagementServiceOuterClass.UploadDarFileRequest
      .newBuilder()
      .setDarFile(darFile)
      .build
    wrapFuture(packageManagementServiceStub.uploadDarFile(request, _)).map(_ => ())
  }

  def getUser(userId: String)(implicit ec: ExecutionContext): Future[User] = {
    val request = new GetUserRequest(userId).toProto
    wrapFuture(userManagementServiceStub.getUser(request, _)).map(r => User.fromProto(r.getUser))
  }

  def getOrCreateUser(user: User, initialRights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[User] = {
    getUser(user.getId()).recoverWith {
      case e: StatusRuntimeException if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
        createUser(user, initialRights)
    }
  }

  def allocateParty(hint: Option[String], displayName: Option[String])(implicit
      ec: ExecutionContext
  ): Future[String] = {
    val requestBuilder = PartyManagementServiceOuterClass.AllocatePartyRequest
      .newBuilder()
    hint.foreach(requestBuilder.setPartyIdHint(_))
    displayName.foreach(requestBuilder.setDisplayName(_))
    wrapFuture(
      partyManagementServiceStub
        .allocateParty(requestBuilder.build, _)
    )
      .map(_.getPartyDetails.getParty)
  }

  def createUser(user: User, initialRights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[User] = {

    val request = initialRights match {
      case hd +: tl => new CreateUserRequest(user, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("createUser requires at least one right")
    }
    wrapFuture(userManagementServiceStub.createUser(request, _)).map(r =>
      CreateUserResponse.fromProto(r).getUser
    )
  }

  def listUserRights(userId: String)(implicit
      ec: ExecutionContext
  ): Future[Seq[User.Right]] = {
    val request = new ListUserRightsRequest(userId).toProto
    wrapFuture(userManagementServiceStub.listUserRights(request, _)).map(r =>
      ListUserRightsResponse.fromProto(r).getRights.asScala.toSeq
    )
  }

  def grantUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    val request = rights match {
      case hd +: tl => new GrantUserRightsRequest(userId, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("grantUserRights requires at least one right")
    }
    wrapFuture(userManagementServiceStub.grantUserRights(request, _)).map(_ => ())
  }

  def revokeUserRights(userId: String, rights: Seq[User.Right])(implicit
      ec: ExecutionContext
  ): Future[Unit] = {
    val request = rights match {
      case hd +: tl => new RevokeUserRightsRequest(userId, hd, tl: _*).toProto
      case _ => throw new IllegalArgumentException("revokeUserRights requires at least one right")
    }
    wrapFuture(userManagementServiceStub.revokeUserRights(request, _)).map(_ => ())
  }

  def submitTransfer(
      applicationId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: LedgerClient.TransferCommand,
  ): Future[Unit] =
    transferSubmissionServiceStub
      .withDeadline(getReducedDeadline())
      .submit(
        LedgerClient
          .TransferSubmitRequest(
            applicationId,
            commandId,
            submissionId,
            submitter,
            command,
          )
          .toProto
      )
      .map((_: Empty) => ())

  def completions(
      applicationId: String,
      parties: Seq[PartyId],
      begin: Option[LedgerOffset],
  ): Source[CompletionStreamResponse, NotUsed] =
    ClientAdapter.serverStreaming(
      multidomain.CompletionStreamRequest(
        ledgerId = "",
        applicationId = applicationId,
        parties = parties.map(_.toProtoPrimitive),
        offset = begin map (lo => ledger_offset.LedgerOffset.fromJavaProto(lo.toProto)),
      ),
      multidomainCompletionServiceStub.completionStream,
    ) map CompletionStreamResponse.fromProto

  def getConnectedDomains(
      party: PartyId
  ): Future[Map[DomainAlias, DomainId]] = {
    val req = multidomain.GetConnectedDomainsRequest(
      party = party.toProtoPrimitive
    )
    stateServiceStub.getConnectedDomains(req).map { resp =>
      resp.connectedDomains.map { cd =>
        DomainAlias.tryCreate(cd.domainAlias) -> DomainId.tryFromString(cd.domainId)
      }.toMap
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
      begin: LedgerOffset,
      end: Option[LedgerOffset],
      party: PartyId,
  ) {

    import com.daml.ledger.api.v1.ledger_offset as sclo

    private[LedgerClient] def toProto: multidomain.GetUpdatesRequest =
      multidomain.GetUpdatesRequest(
        ledgerId = "",
        begin = Some(sclo.LedgerOffset.fromJavaProto(begin.toProto)),
        end = end.map(lc => sclo.LedgerOffset.fromJavaProto(lc.toProto)),
        party = party.toLf,
      )
  }

  final case class GetTreeUpdatesResponse(
      update: TreeUpdate,
      domainId: DomainId,
  )

  object GetTreeUpdatesResponse {

    import multidomain.GetTreeUpdatesResponse.Update as TU

    private[LedgerClient] def fromProto(
        proto: multidomain.GetTreeUpdatesResponse
    ): GetTreeUpdatesResponse =
      GetTreeUpdatesResponse(
        proto.update match {
          case TU.TransactionTree(tree) =>
            TransactionTreeUpdate(TransactionTree fromProto scalapbToJava(tree)(_.companion))
          case TU.Transfer(x) =>
            TransferUpdate(Transfer.fromProto(x))
          case TU.Empty => sys.error("uninitialized update service result")
        },
        DomainId.tryFromString(proto.domainId),
      )
  }

  sealed abstract class TransferCommand extends Product with Serializable

  object TransferCommand {
    final case class Out(
        contractId: ContractId[_],
        source: DomainId,
        target: DomainId,
    ) extends TransferCommand {
      def toProto: multidomain.TransferOutCommand =
        multidomain.TransferOutCommand(
          contractId = contractId.contractId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }

    final case class In(
        transferOutId: String,
        source: DomainId,
        target: DomainId,
    ) extends TransferCommand {
      def toProto: multidomain.TransferInCommand =
        multidomain.TransferInCommand(
          transferOutId = transferOutId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }
  }

  final case class TransferSubmitRequest(
      applicationId: String,
      commandId: String,
      submissionId: String,
      submitter: PartyId,
      command: TransferCommand,
  ) {
    def toProto: multidomain.SubmitRequest = {
      val baseCommand = multidomain.TransferCommand(
        applicationId = applicationId,
        commandId = commandId,
        submissionId = submissionId,
        submitter = submitter.toProtoPrimitive,
      )
      val updatedCommand = command match {
        case out: TransferCommand.Out =>
          baseCommand.withTransferOutCommand(out.toProto)
        case in: TransferCommand.In =>
          baseCommand.withTransferInCommand(in.toProto)
      }
      multidomain.SubmitRequest(
        Some(updatedCommand)
      )
    }
  }

  final case class CompletionStreamResponse(laterOffset: LedgerOffset, completion: Completion)

  object CompletionStreamResponse {
    def fromProto(spb: multidomain.CompletionStreamResponse): CompletionStreamResponse = {
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
    def fromProto(spb: completion.Completion): Completion = {
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
