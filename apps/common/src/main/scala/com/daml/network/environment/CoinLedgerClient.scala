package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.LedgerClientChannelConfiguration
import com.daml.network.admin.api.client.ApiClientRequestLogger
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContextExecutor

class CoinLedgerClient(
    config: ClientConfig,
    applicationId: String,
    token: Option[String],
    override protected val timeouts: ProcessingTimeout,
    apiLoggingConfig: ApiLoggingConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContextExecutor,
    as: ActorSystem,
    esf: ExecutionSequencerFactory,
    elc: ErrorLoggingContext,
) extends FlagCloseable
    with NamedLogging {

  private val client = {
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
        GrpcTracing.builder(tracerProvider.openTelemetry).build().newClientInterceptor(),
        new ApiClientRequestLogger(loggerFactory, apiLoggingConfig),
      )

    val channel = builder.build

    new LedgerClient(channel, token)
  }

  private val callbacks = new AtomicReference[Seq[String => Unit]](Seq())

  def registerInactiveContractsCallback(
      f: String => Unit
  ): Unit = {
    callbacks.getAndUpdate(prev => prev :+ f)
  }: Unit

  def connection(origin: String): CoinLedgerConnection = {
    val connection = new CoinLedgerConnection(
      this.client,
      applicationId,
      loggerFactory,
      timeouts,
      retryProvider,
      callbacks,
    )
    logger.debug(s"Created a CoinLedgerConnection ($origin).")(TraceContext.empty)
    connection
  }

  override def onClosed(): Unit = {
    client.close()
  }
}
