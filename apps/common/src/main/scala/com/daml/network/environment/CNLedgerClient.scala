package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ApiClientRequestLogger
import com.daml.network.environment.ledger.api.LedgerClient
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.ledger.client.configuration.LedgerClientChannelConfiguration
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

class CNLedgerClient(
    config: ClientConfig,
    applicationId: String,
    token: Option[String],
    override protected val timeouts: ProcessingTimeout,
    apiLoggingConfig: ApiLoggingConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    retryProvider: RetryProvider,
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
        new ApiClientRequestLogger(loggerFactory, apiLoggingConfig),
        GrpcTracing.builder(tracerProvider.openTelemetry).build().newClientInterceptor(),
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

  def connection(
      connectionClient: String,
      baseLoggerFactory: NamedLoggerFactory,
      completionOffsetCallback: String => Future[Unit] = _ => Future.unit,
  ): CNLedgerConnection =
    new CNLedgerConnection(
      this.client,
      applicationId,
      baseLoggerFactory.append("connClient", connectionClient),
      timeouts,
      retryProvider,
      callbacks,
      completionOffsetCallback,
    )

  override def onClosed(): Unit = {
    client.close()
  }
}
