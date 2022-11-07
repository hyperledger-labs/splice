package com.daml.network.environment

import akka.NotUsed
import akka.actor.ActorSystem
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
  TransactionServiceGrpc,
  TransactionServiceOuterClass,
}
import com.daml.ledger.client.GrpcChannel
import com.daml.ledger.client.configuration.LedgerClientChannelConfiguration
import com.daml.ledger.javaapi.data.{
  Command,
  CreateUserRequest,
  CreateUserResponse,
  GetActiveContractsRequest,
  GetActiveContractsResponse,
  GetTransactionsRequest,
  GetTransactionsResponse,
  GetUserRequest,
  GrantUserRightsRequest,
  Transaction,
  TransactionTree,
  User,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import com.google.protobuf.ByteString
import io.grpc.Channel
import io.grpc.stub.{AbstractStub, StreamObserver}
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import java.io.Closeable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.jdk.CollectionConverters.*

trait JavaCoinLedgerClient extends FlagCloseableAsync {
  def applicationId: String
  def client: JavaCoinLedgerClient.LedgerClient
  def connection(
      workflowId: String,
      maxRetries: Int = 10,
  ): JavaCoinLedgerConnection with NamedLogging
  def timeouts: ProcessingTimeout

  def actorSystem: ActorSystem
  def executionContextExecutor: ExecutionContextExecutor
}

object JavaCoinLedgerClient {
  class FutureObserver[T] extends StreamObserver[T] {
    val promise = Promise[T]()

    override def onError(t: Throwable) = {
      promise.failure(t)
    }

    override def onNext(result: T) = {
      promise.success(result)
    }
    override def onCompleted() = {}
  }
  def wrapFuture[T](f: (StreamObserver[T] => Unit)): Future[T] = {
    val futureObserver = new FutureObserver[T]
    f(futureObserver)
    futureObserver.promise.future
  }
  private def withCredentials[T <: AbstractStub[T]](
      stub: T,
      token: Option[String],
  ): T = {
    token.fold(stub)(token => stub.withCallCredentials(new LedgerCallCredentials(token)))
  }

  class LedgerClient(channel: Channel, token: Option[String])(implicit
      esf: ExecutionSequencerFactory,
      ec: ExecutionContext,
  ) extends Closeable {
    val activeContractsServiceStub: ActiveContractsServiceGrpc.ActiveContractsServiceStub =
      withCredentials(ActiveContractsServiceGrpc.newStub(channel), token)
    val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
      withCredentials(CommandServiceGrpc.newStub(channel), token)
    val transactionServiceStub: TransactionServiceGrpc.TransactionServiceStub =
      withCredentials(TransactionServiceGrpc.newStub(channel), token)
    val packageManagementServiceStub: PackageManagementServiceGrpc.PackageManagementServiceStub =
      withCredentials(PackageManagementServiceGrpc.newStub(channel), token)
    val partyManagementServiceStub: PartyManagementServiceGrpc.PartyManagementServiceStub =
      withCredentials(PartyManagementServiceGrpc.newStub(channel), token)
    val userManagementServiceStub: UserManagementServiceGrpc.UserManagementServiceStub =
      withCredentials(UserManagementServiceGrpc.newStub(channel), token)

    override def close(): Unit = GrpcChannel.close(channel)

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

    private def submitAndWaitRequest(
        workflowId: String,
        applicationId: String,
        commandId: String,
        deduplicationOffset: Option[String],
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
      deduplicationOffset.foreach { off =>
        commandsBuilder.setDeduplicationOffset(off)
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
        deduplicationOffset: Option[String],
        actAs: Seq[String],
        readAs: Seq[String],
        commands: Seq[Command],
    )(implicit ec: ExecutionContext): Future[Transaction] = {
      val request = submitAndWaitRequest(
        workflowId,
        applicationId,
        commandId,
        deduplicationOffset,
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
        deduplicationOffset: Option[String],
        actAs: Seq[String],
        readAs: Seq[String],
        commands: Seq[Command],
    )(implicit ec: ExecutionContext): Future[TransactionTree] = {
      val request = submitAndWaitRequest(
        workflowId,
        applicationId,
        commandId,
        deduplicationOffset,
        actAs,
        readAs,
        commands,
      )
      wrapFuture(commandServiceStub.submitAndWaitForTransactionTree(request, _)).map(response =>
        TransactionTree.fromProto(response.getTransaction)
      )
    }

    def listKnownPackages()(implicit
        ec: ExecutionContext
    ): Future[Seq[PackageManagementServiceOuterClass.PackageDetails]] = {
      val request = PackageManagementServiceOuterClass.ListKnownPackagesRequest.newBuilder().build
      wrapFuture(packageManagementServiceStub.listKnownPackages(request, _))
        .map(_.getPackageDetailsList.asScala.toSeq)
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
  private def createLedgerClient(
      config: ClientConfig,
      token: Option[String],
      tracerProvider: TracerProvider,
  )(implicit ec: ExecutionContextExecutor, esf: ExecutionSequencerFactory): Future[LedgerClient] = {

    val clientChannelConfig = LedgerClientChannelConfiguration(
      sslContext = config.tls.map(x => ClientChannelBuilder.sslContext(x)),
      // Hard-coding the maximum value (= 2GB).
      // If a limit is needed, because an application can't handle transactions at that size,
      // the participants should agree on a lower limit and enforce that through domain parameters.
      maxInboundMessageSize = Int.MaxValue,
    )

    val builder = clientChannelConfig
      .builderFor(config.address, config.port.unwrap)
      .executor(ec)
      .intercept(
        GrpcTracing.builder(tracerProvider.openTelemetry).build().newClientInterceptor()
      )

    val channel = builder.build
    val client = new LedgerClient(channel, token)
    Future.successful(client)
  }

  def create(
      clientConfig: ClientConfig,
      applicationId_ : String,
      token: Option[String],
      processingTimeouts: ProcessingTimeout,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      as: ActorSystem,
      esf: ExecutionSequencerFactory,
  ): Future[JavaCoinLedgerClient with NamedLogging] =
    createLedgerClient(
      clientConfig,
      token,
      tracerProvider,
    ).map { client_ =>
      new JavaCoinLedgerClient with NamedLogging {

        override val client: LedgerClient = client_

        override def connection(
            workflowId: String,
            maxRetries: Int,
        ): JavaCoinLedgerConnection with NamedLogging =
          JavaCoinLedgerConnection(
            this,
            maxRetries,
            workflowId,
            loggerFactoryForCoinLedgerConnectionOverride,
            tracerProvider,
          )

        override def applicationId: String = applicationId_
        override def timeouts: ProcessingTimeout = processingTimeouts

        protected val loggerFactory: NamedLoggerFactory =
          loggerFactoryForCoinLedgerConnectionOverride

        override def actorSystem: ActorSystem = as
        override def executionContextExecutor: ExecutionContextExecutor = ec

        override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List(
          SyncCloseable("client", client.close())
        )
      }
    }
}
