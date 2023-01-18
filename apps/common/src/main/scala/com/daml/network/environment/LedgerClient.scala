package com.daml.network.environment

import com.digitalasset.canton.research.participant.multidomain.{
  transfer as xfr,
  update_service as upsvc,
}
import com.digitalasset.canton.topology.{DomainId, PartyId}
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
import com.daml.ledger.javaapi.data.{
  Command,
  CreateUserRequest,
  CreateUserResponse,
  GetActiveContractsRequest,
  GetActiveContractsResponse,
  GetTransactionTreesResponse,
  GetTransactionsRequest,
  GetTransactionsResponse,
  GetUserRequest,
  GrantUserRightsRequest,
  LedgerOffset,
  ListUserRightsRequest,
  ListUserRightsResponse,
  Transaction,
  TransactionTree,
  User,
}
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.util.ErrorUtil
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
  val updateServiceStub: upsvc.UpdateServiceGrpc.UpdateServiceStub =
    withCredentials(upsvc.UpdateServiceGrpc.stub(channel), token)

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

  def ledgerEnd(): Future[LedgerOffset] = {
    val req = TransactionServiceOuterClass.GetLedgerEndRequest.newBuilder().build()
    wrapFuture(transactionServiceStub.getLedgerEnd(req, _)).map { resp =>
      LedgerOffset.fromProto(resp.getOffset)
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

  def transactions(
      request: GetTransactionsRequest
  ): Source[GetTransactionsResponse, NotUsed] = {
    ClientAdapter
      .serverStreaming(request.toProto, transactionServiceStub.getTransactions)
      .map(GetTransactionsResponse.fromProto)
  }

  def updates(
      request: LedgerClient.GetUpdatesRequest
  ): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    ClientAdapter
      .serverStreaming(request.toProto, updateServiceStub.getTreeUpdates)
      .map(LedgerClient.GetTreeUpdatesResponse.fromProto)
  }

  def transactionTrees(
      request: GetTransactionsRequest
  ): Source[GetTransactionTreesResponse, NotUsed] = {
    ClientAdapter
      .serverStreaming(request.toProto, transactionServiceStub.getTransactionTrees)
      .map(GetTransactionTreesResponse.fromProto)
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

  final case class GetTreeUpdatesResponse(updates: Seq[Either[TransactionTree, xfr.Transfer]]) {
    // TODO(M3-18) remove
    private[environment] def discardTransfers: Seq[TransactionTree] =
      updates collect { case Left(t) => t }
  }

  object GetTreeUpdatesResponse {
    import upsvc.TreeUpdate.TreeUpdate as TU

    private[LedgerClient] def fromProto(
        proto: upsvc.GetTreeUpdatesResponse
    ): GetTreeUpdatesResponse =
      GetTreeUpdatesResponse(proto.updates map {
        _.treeUpdate match {
          case TU.TransactionTree(tree) =>
            Left(TransactionTree fromProto tree.companion.toJavaProto(tree))
          case TU.Transfer(x) => Right(x)
          case TU.Empty => sys.error("uninitialized update service result")
        }
      })
  }
}
