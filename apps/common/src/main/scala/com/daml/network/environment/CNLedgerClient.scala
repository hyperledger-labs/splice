package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.client.ApiClientRequestLogger
import com.daml.network.auth.AuthToken
import com.daml.network.environment.ledger.api.LedgerClient
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
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
    getToken: () => Future[Option[AuthToken]],
    apiLoggingConfig: ApiLoggingConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    override protected[this] val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContextExecutor,
    as: ActorSystem,
    esf: ExecutionSequencerFactory,
    elc: ErrorLoggingContext,
) extends RetryProvider.Has
    with FlagCloseable
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
    new LedgerClient(channel, getToken)
  }

  private val callbacks = new AtomicReference[Seq[String => Unit]](Seq())

  def registerInactiveContractsCallback(
      f: String => Unit
  ): Unit = {
    callbacks.getAndUpdate(prev => prev :+ f)
  }: Unit

  private val trafficBalanceService = new AtomicReference[Option[TrafficBalanceService]](None)

  def registerTrafficBalanceService(service: TrafficBalanceService): Unit = {
    // compareAndSet ensures the atomic reference is only set once
    trafficBalanceService.compareAndSet(None, Some(service))
  }: Unit

  def readOnlyConnection(
      connectionClient: String,
      baseLoggerFactory: NamedLoggerFactory,
  ): BaseLedgerConnection =
    new BaseLedgerConnection(
      this.client,
      applicationId,
      baseLoggerFactory.append("roConnClient", connectionClient),
      retryProvider,
    )

  def connection(
      connectionClient: String,
      baseLoggerFactory: NamedLoggerFactory,
      packageIdResolver: PackageIdResolver,
      completionOffsetCallback: String => Future[Unit] = _ => Future.unit,
  ): CNLedgerConnection =
    new CNLedgerConnection(
      this.client,
      applicationId,
      baseLoggerFactory.append("connClient", connectionClient),
      retryProvider,
      callbacks,
      trafficBalanceService,
      completionOffsetCallback,
      packageIdResolver,
    )

  override def onClosed(): Unit = {
    client.close()
  }
}
