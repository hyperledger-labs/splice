package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.domain.LedgerId
import com.daml.ledger.api.refinements.ApiTypes.{ApplicationId, WorkflowId}
import com.daml.ledger.client.LedgerClient
import com.daml.ledger.client.configuration.{
  CommandClientConfiguration,
  LedgerClientChannelConfiguration,
  LedgerClientConfiguration,
  LedgerIdRequirement,
}
import com.daml.ledger.client.services.transactions.TransactionClient
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import scala.concurrent.{ExecutionContextExecutor, Future}

/** The physical connection to the ledger API.
  * Contains properties shared among all logical connections (workflows).
  */
trait CoinLedgerClient extends FlagCloseableAsync {
  def applicationId: ApplicationId
  def timeouts: ProcessingTimeout
  def token: Option[String]
  def ledgerId: LedgerId
  def transactionClient: TransactionClient

  // Note: all workflows share the same execution context and actor system
  def actorSystem: ActorSystem
  def executionContextExecutor: ExecutionContextExecutor

  val client: LedgerClient

  def connection(
      workflowId: String,
      maxRetries: Int = 10,
  ): CoinLedgerConnection with NamedLogging
}

object CoinLedgerClient {
  private def createLedgerClient(
      applicationId: ApplicationId,
      config: ClientConfig,
      commandClientConfiguration: CommandClientConfiguration,
      tracerProvider: TracerProvider,
      token: Option[String] = None,
  )(implicit
      ec: ExecutionContextExecutor,
      executionSequencerFactory: ExecutionSequencerFactory,
  ): Future[LedgerClient] = {
    val clientConfig = LedgerClientConfiguration(
      applicationId = ApplicationId.unwrap(applicationId),
      ledgerIdRequirement = LedgerIdRequirement(None),
      commandClient = commandClientConfiguration,
      token = token,
    )
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

    LedgerClient.fromBuilder(builder, clientConfig)
  }

  def create(
      clientConfig: ClientConfig,
      applicationId_ : ApplicationId,
      commandClientConfiguration: CommandClientConfiguration,
      token_ : Option[String],
      processingTimeouts: ProcessingTimeout,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      as: ActorSystem,
      sequencerPool: ExecutionSequencerFactory,
  ): Future[CoinLedgerClient with NamedLogging] =
    createLedgerClient(
      applicationId_,
      clientConfig,
      commandClientConfiguration,
      tracerProvider,
      token_,
    ).map { client_ =>
      new CoinLedgerClient with NamedLogging {

        protected val loggerFactory: NamedLoggerFactory =
          loggerFactoryForCoinLedgerConnectionOverride

        override val client: LedgerClient = client_

        override def connection(
            workflowId: String,
            maxRetries: Int,
        ): CoinLedgerConnection with NamedLogging =
          CoinLedgerConnection(
            this,
            maxRetries,
            WorkflowId(workflowId),
            loggerFactoryForCoinLedgerConnectionOverride,
            tracerProvider,
          )

        override def timeouts: ProcessingTimeout = processingTimeouts
        override def token: Option[String] = token_
        override def applicationId: ApplicationId = applicationId_
        override def ledgerId: LedgerId = client.ledgerId
        override def transactionClient: TransactionClient = client.transactionClient

        override def actorSystem: ActorSystem = as
        override def executionContextExecutor: ExecutionContextExecutor = ec

        override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = List(
          SyncCloseable("client", client.close())
        )
      }
    }

}
