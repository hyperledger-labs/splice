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
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.StatusRuntimeException
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing
import org.slf4j.event.Level
import com.daml.ledger.client.services.transactions.TransactionClient

import scala.concurrent.{ExecutionContextExecutor, Future}

/** The physical connection to the ledger API.
  * Contains properties shared among all logical connections (workflows).
  */
trait CoinLedgerClient {
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
  def createLedgerClient(
      applicationId: ApplicationId,
      config: ClientConfig,
      commandClientConfiguration: CommandClientConfiguration,
      logger: TracedLogger,
      tracerProvider: TracerProvider,
      token: Option[String] = None,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
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

    LedgerClient.fromBuilder(builder, clientConfig) recover {
      case _: StatusRuntimeException | _: java.io.IOException => {
        // TODO(i447) -- eventually we should drop this and replace with a more robust retry solution
        logger.error("Failed to instantiate ledger client due to connection failure, exiting...")
        sys.exit(1)
      }
    }
  }

  def apply(
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
  ): CoinLedgerClient with NamedLogging =
    new CoinLedgerClient with NamedLogging {
      protected val loggerFactory: NamedLoggerFactory = loggerFactoryForCoinLedgerConnectionOverride

      override val client: LedgerClient = {
        import TraceContext.Implicits.Empty._
        processingTimeouts.unbounded.await(
          s"Creation of the ledger client",
          logFailing = Some(Level.WARN),
        )(
          createLedgerClient(
            applicationId,
            clientConfig,
            commandClientConfiguration,
            logger,
            tracerProvider,
            token,
          )
        )
      }

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

    }
}
