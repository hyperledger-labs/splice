package com.daml.network.environment

import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyInstances}
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.akka.ClientAdapter
import com.daml.ledger.api.auth.client.LedgerCallCredentials
import com.daml.ledger.api.v1.admin.{
  PackageManagementServiceGrpc,
  PackageManagementServiceOuterClass,
  PartyManagementServiceGrpc,
  PartyManagementServiceOuterClass,
  UserManagementServiceGrpc,
}
import com.daml.ledger.api.v1.{
  ActiveContractsServiceGrpc,
  CommandServiceGrpc,
  CommandServiceOuterClass,
  CommandsOuterClass,
  PackageServiceGrpc,
  PackageServiceOuterClass,
  TransactionServiceGrpc,
  TransactionServiceOuterClass,
}
import com.daml.ledger.client.GrpcChannel
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  Command,
  CreateUserRequest,
  CreateUserResponse,
  GetActiveContractsRequest,
  GetActiveContractsResponse,
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
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.research.participant.multidomain.{
  transfer as xfr,
  transfer_command as xfrcmd,
  transfer_submission_service as xfrsvc,
  update_service as upsvc,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.util.ErrorUtil
import com.google.protobuf.empty.Empty
import com.google.protobuf.{ByteString, Duration}
import io.grpc.Channel
import io.grpc.stub.{AbstractStub, StreamObserver}

import java.io.Closeable
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
  * All functionality built on top of that is part of CoinLedgerConnection.
  */
class LedgerClient(channel: Channel, token: Option[String])(implicit
    esf: ExecutionSequencerFactory,
    ec: ExecutionContext,
    elc: ErrorLoggingContext,
) extends Closeable {
  import LedgerClient.{GetUpdatesRequest, GetTreeUpdatesResponse, scalapbToJava}

  val activeContractsServiceStub: ActiveContractsServiceGrpc.ActiveContractsServiceStub =
    withCredentials(ActiveContractsServiceGrpc.newStub(channel), token)
  val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
    withCredentials(CommandServiceGrpc.newStub(channel), token)
  val transactionServiceStub: TransactionServiceGrpc.TransactionServiceStub =
    withCredentials(TransactionServiceGrpc.newStub(channel), token)
  val packageServiceStub: PackageServiceGrpc.PackageServiceStub =
    withCredentials(PackageServiceGrpc.newStub(channel), token)
  val packageManagementServiceStub: PackageManagementServiceGrpc.PackageManagementServiceStub =
    withCredentials(PackageManagementServiceGrpc.newStub(channel), token)
  val partyManagementServiceStub: PartyManagementServiceGrpc.PartyManagementServiceStub =
    withCredentials(PartyManagementServiceGrpc.newStub(channel), token)
  val userManagementServiceStub: UserManagementServiceGrpc.UserManagementServiceStub =
    withCredentials(UserManagementServiceGrpc.newStub(channel), token)
  private val updateServiceStub: upsvc.UpdateServiceGrpc.UpdateServiceStub =
    withCredentials(upsvc.UpdateServiceGrpc.stub(channel), token)
  private val transferSubmissionServiceStub
      : xfrsvc.TransferSubmissionServiceGrpc.TransferSubmissionServiceStub =
    withCredentials(xfrsvc.TransferSubmissionServiceGrpc.stub(channel), token)

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

  def ledgerEnd(domain: DomainId): Future[LedgerOffset] = {
    val req = upsvc.GetLedgerEndRequest(domainId = domain.toProtoPrimitive)
    updateServiceStub.getLedgerEnd(req).map { resp =>
      LedgerOffset.fromProto(scalapbToJava(resp.getOffset)(_.companion))
    }
  }

  def activeContracts(
      request: GetActiveContractsRequest
  ): Source[GetActiveContractsResponse, NotUsed] = {
    ClientAdapter
      .serverStreaming(request.toProto, activeContractsServiceStub.getActiveContracts)
      .map(GetActiveContractsResponse.fromProto)
  }

  def tryGetTransactionTreeById(
      parties: Seq[String],
      id: String,
  ): Future[TransactionTree] = {
    val req = TransactionServiceOuterClass.GetTransactionByIdRequest.newBuilder
      .setTransactionId(id)
      .addAllRequestingParties(parties.asJava)
      .build()
    wrapFuture(
      transactionServiceStub
        .getTransactionById(req, _)
    ).map { resp =>
      TransactionTree.fromProto(resp.getTransaction)
    }
  }

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

  def updates(request: GetUpdatesRequest): Source[GetTreeUpdatesResponse, NotUsed] = {
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
  ): CommandServiceOuterClass.SubmitAndWaitRequest = {
    val commandsBuilder = CommandsOuterClass.Commands.newBuilder
    commandsBuilder
      .setWorkflowId(workflowId)
      .setCommandId(commandId)
      .setApplicationId(applicationId)
      .addAllActAs(actAs.asJava)
      .addAllReadAs(readAs.asJava)
      .addAllCommands(commands.map(_.toProtoCommand).asJava)
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

  def submitAndWaitForTransaction(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
  )(implicit ec: ExecutionContext): Future[Transaction] = {
    val request = submitAndWaitRequest(
      workflowId,
      applicationId,
      commandId,
      deduplicationConfig,
      actAs,
      readAs,
      commands,
    )
    wrapFuture(commandServiceStub.submitAndWaitForTransaction(request, _)).map(response =>
      Transaction.fromProto(response.getTransaction)
    )
  }

  def submitAndWaitForTransactionTree(
      workflowId: String,
      applicationId: String,
      commandId: String,
      deduplicationConfig: DedupConfig,
      actAs: Seq[String],
      readAs: Seq[String],
      commands: Seq[Command],
  )(implicit ec: ExecutionContext): Future[TransactionTree] = {
    val request = submitAndWaitRequest(
      workflowId,
      applicationId,
      commandId,
      deduplicationConfig,
      actAs,
      readAs,
      commands,
    )
    wrapFuture(commandServiceStub.submitAndWaitForTransactionTree(request, _)).map(response =>
      TransactionTree.fromProto(response.getTransaction)
    )
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

  def allocateParty(hint: Option[String], displayName: Option[String])(implicit
      ec: ExecutionContext
  ): Future[String] = {
    val requestBuilder = PartyManagementServiceOuterClass.AllocatePartyRequest
      .newBuilder()
    hint.foreach(requestBuilder.setPartyIdHint(_))
    hint.foreach(requestBuilder.setDisplayName(_))
    wrapFuture(partyManagementServiceStub.allocateParty(requestBuilder.build, _))
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
      submitter: PartyId,
      command: LedgerClient.TransferCommand,
  ): Future[Unit] =
    transferSubmissionServiceStub
      .submit(
        LedgerClient
          .TransferSubmitRequest(
            applicationId,
            commandId,
            submitter,
            command,
          )
          .toProto
      )
      .map((_: Empty) => ())
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
      domainId: DomainId,
  ) {
    import com.daml.ledger.api.v1.ledger_offset as sclo

    private[LedgerClient] def toProto: upsvc.GetUpdatesRequest =
      upsvc.GetUpdatesRequest(
        ledgerId = "",
        begin = Some(sclo.LedgerOffset.fromJavaProto(begin.toProto)),
        end = end.map(lc => sclo.LedgerOffset.fromJavaProto(lc.toProto)),
        party = party.toLf,
        domainId = domainId.unwrap.toProtoPrimitive,
      )
  }

  final case class GetTreeUpdatesResponse(
      updates: Seq[GetTreeUpdatesResponse.TreeUpdate]
  ) {
    // TODO(M3-18) remove
    private[environment] def discardTransfers: Seq[TransactionTree] =
      updates collect { case GetTreeUpdatesResponse.TransactionTreeUpdate(t) => t }
  }
  object GetTreeUpdatesResponse {
    sealed abstract class TreeUpdate extends Product with Serializable

    final case class TransactionTreeUpdate(tree: TransactionTree) extends TreeUpdate
    final case class TransferUpdate(transfer: Transfer[TransferEvent]) extends TreeUpdate

    final case class Transfer[+E](
        updateId: String,
        offset: LedgerOffset.Absolute,
        submitter: PartyId,
        event: E & TransferEvent,
    ) extends PrettyPrinting {
      override def pretty: Pretty[this.type] =
        prettyOfClass(
          param("updateId", (x: this.type) => x.updateId)(PrettyInstances.prettyString),
          param("offset", (x: this.type) => x.offset.getOffset)(PrettyInstances.prettyString),
          param("submitter", _.submitter),
          param("event", _.event),
        )
    }
    object Transfer {
      private[LedgerClient] def fromProto(proto: xfr.Transfer): Transfer[TransferEvent] = {
        val offset = new LedgerOffset.Absolute(proto.offset)
        val submitter = PartyId.tryFromProtoPrimitive(proto.submitter)
        val event = proto.event match {
          case xfr.Transfer.Event.TransferOutEvent(out) => TransferEvent.Out.fromProto(out)
          case xfr.Transfer.Event.TransferInEvent(in) => TransferEvent.In.fromProto(in)
          case xfr.Transfer.Event.Empty =>
            throw new IllegalArgumentException("uninitialized transfer event")
        }
        Transfer(
          proto.updateId,
          offset,
          submitter,
          event,
        )
      }
    }

    sealed trait TransferEvent extends Product with Serializable with PrettyPrinting
    object TransferEvent {
      private case class TransferOutId(s: String) extends PrettyPrinting {
        override def pretty: Pretty[this.type] = prettyOfString(_.s)
      }
      final case class Out(
          transferOutId: String,
          contractId: ContractId[_],
          source: DomainId,
          target: DomainId,
      ) extends TransferEvent {
        def pretty: Pretty[this.type] =
          prettyOfClass(
            param("transferOutId", o => TransferOutId(o.transferOutId)),
            param("contractId", _.contractId),
            param("source", _.source),
            param("target", _.target),
          )
      }
      object Out {
        private[LedgerClient] def fromProto(proto: xfr.TransferredOutEvent): Out = {
          Out(
            transferOutId = proto.transferOutId,
            contractId = new ContractId(proto.contractId),
            source = DomainId.tryFromString(proto.source),
            target = DomainId.tryFromString(proto.target),
          )
        }
      }
      final case class In(
          source: DomainId,
          target: DomainId,
          transferOutId: String,
          createdEvent: CreatedEvent,
      ) extends TransferEvent {
        def pretty: Pretty[this.type] =
          prettyOfClass(
            param("source", _.source),
            param("target", _.target),
            param("transferOutId", i => TransferOutId(i.transferOutId)),
            param("createdEvent", _.createdEvent),
          )
      }
      object In {
        private[LedgerClient] def fromProto(proto: xfr.TransferredInEvent): In = {
          import com.daml.ledger.api.v1.{event as scalaEvent}
          In(
            source = DomainId.tryFromString(proto.source),
            target = DomainId.tryFromString(proto.target),
            transferOutId = proto.transferOutId,
            createdEvent =
              CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
          )
        }
      }
    }
    import upsvc.TreeUpdate.TreeUpdate as TU

    private[LedgerClient] def fromProto(
        proto: upsvc.GetTreeUpdatesResponse
    ): GetTreeUpdatesResponse =
      GetTreeUpdatesResponse(proto.updates map {
        _.treeUpdate match {
          case TU.TransactionTree(tree) =>
            TransactionTreeUpdate(TransactionTree fromProto scalapbToJava(tree)(_.companion))
          case TU.Transfer(x) => TransferUpdate(GetTreeUpdatesResponse.Transfer.fromProto(x))
          case TU.Empty => sys.error("uninitialized update service result")
        }
      })
  }

  sealed abstract class TransferCommand extends Product with Serializable

  object TransferCommand {
    final case class Out(
        contractId: ContractId[_],
        source: DomainId,
        target: DomainId,
    ) extends TransferCommand {
      def toProto: xfrcmd.TransferOutCommand =
        xfrcmd.TransferOutCommand(
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
      def toProto: xfrcmd.TransferInCommand =
        xfrcmd.TransferInCommand(
          transferOutId = transferOutId,
          source = source.toProtoPrimitive,
          target = target.toProtoPrimitive,
        )
    }
  }

  final case class TransferSubmitRequest(
      applicationId: String,
      commandId: String,
      submitter: PartyId,
      command: TransferCommand,
  ) {
    def toProto: xfrsvc.SubmitRequest = {
      val baseCommand = xfrcmd.TransferCommand(
        applicationId = applicationId,
        commandId = commandId,
        submitter = submitter.toProtoPrimitive,
      )
      val updatedCommand = command match {
        case out: TransferCommand.Out =>
          baseCommand.withTransferOutCommand(out.toProto)
        case in: TransferCommand.In =>
          baseCommand.withTransferInCommand(in.toProto)
      }
      xfrsvc.SubmitRequest(
        Some(updatedCommand)
      )
    }
  }

  @inline
  private def scalapbToJava[S, J](s: S)(companion: S => scalapb.JavaProtoSupport[_ >: S, J]): J =
    companion(s).toJavaProto(s)
}
