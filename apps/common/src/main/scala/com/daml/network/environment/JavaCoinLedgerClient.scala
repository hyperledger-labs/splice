package com.daml.network.environment

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.grpc.adapter.client.akka.ClientAdapter
import com.daml.ledger.api.v1.{
  ActiveContractsServiceGrpc,
  CommandServiceGrpc,
  CommandServiceOuterClass,
  CommandsOuterClass,
  TransactionServiceGrpc,
}
import com.daml.ledger.client.GrpcChannel
import com.daml.ledger.client.configuration.LedgerClientChannelConfiguration
import com.daml.ledger.javaapi.data.{
  Command,
  GetActiveContractsRequest,
  GetActiveContractsResponse,
  GetTransactionsRequest,
  GetTransactionsResponse,
  Transaction,
  TransactionTree,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.Channel
import io.grpc.stub.StreamObserver
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import java.io.Closeable
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.jdk.CollectionConverters._

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
  private def wrapFuture[T](f: (StreamObserver[T] => Unit)): Future[T] = {
    val futureObserver = new FutureObserver[T]
    f(futureObserver)
    futureObserver.promise.future
  }
  class LedgerClient(channel: Channel)(implicit esf: ExecutionSequencerFactory) extends Closeable {
    val activeContractsServiceStub: ActiveContractsServiceGrpc.ActiveContractsServiceStub =
      ActiveContractsServiceGrpc.newStub(channel)
    val commandServiceStub: CommandServiceGrpc.CommandServiceStub =
      CommandServiceGrpc.newStub(channel)
    val transactionServiceStub: TransactionServiceGrpc.TransactionServiceStub =
      TransactionServiceGrpc.newStub(channel)

    override def close(): Unit = GrpcChannel.close(channel)

    def activeContracts(
        request: GetActiveContractsRequest
    ): Source[GetActiveContractsResponse, NotUsed] = {
      ClientAdapter
        .serverStreaming(request.toProto, activeContractsServiceStub.getActiveContracts)
        .map(GetActiveContractsResponse.fromProto)
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
  }
  private def createLedgerClient(
      config: ClientConfig,
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
    val client = new LedgerClient(channel)
    Future.successful(client)
  }

  def create(
      clientConfig: ClientConfig,
      applicationId_ : String,
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
