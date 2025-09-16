// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.ledger.client.configuration.LedgerClientChannelConfiguration
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.admin.api.client.{
  ApiClientRequestLogger,
  GrpcClientMetrics,
  GrpcMetricsClientInterceptor,
}
import org.lfdecentralizedtrust.splice.auth.AuthToken
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.util.SpliceCircuitBreaker

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

class SpliceLedgerClient(
    config: ClientConfig,
    applicationId: String,
    getToken: () => Future[Option[AuthToken]],
    apiLoggingConfig: ApiLoggingConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    // Note: traceProvider is not used right now, but we keep it for future use. See the comments on the tracing
    // client interceptors below
    val unusedTracerProvider: TracerProvider,
    override protected[this] val retryProvider: RetryProvider,
    grpcClientMetrics: GrpcClientMetrics,
)(implicit
    ec: ExecutionContextExecutor,
    as: ActorSystem,
    esf: ExecutionSequencerFactory,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging {

  private val client = {
    val clientChannelConfig = LedgerClientChannelConfiguration(
      sslContext = config.tlsConfig.map(x => ClientChannelBuilder.sslContext(x)),
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
        // The above interceptor handles both client request logging and trace-id allocation and propagation.
        // It does though not create proper spans, like they would be required for distributed tracing.
        // For that we either want to use the standard tracer below (with appropriate adjustments wrt context propagation),
        // or extend the above interceptor.
        // GrpcTracing.builder(tracerProvider.openTelemetry).build().newClientInterceptor()
        new GrpcMetricsClientInterceptor(grpcClientMetrics),
      )

    val channel = builder.build
    new LedgerClient(channel, applicationId, getToken, loggerFactory)
  }

  private val inactiveContractsCallbacks = new AtomicReference[Seq[String => Unit]](Seq())

  private val contractDowngradeErrorCallbacks = new AtomicReference[Seq[() => Unit]](Seq())

  def registerInactiveContractsCallback(
      f: String => Unit
  ): Unit = {
    inactiveContractsCallbacks.getAndUpdate(prev => prev :+ f)
  }: Unit

  def registerContractDowngradeErrorCallback(
      f: () => Unit
  ): Unit = {
    contractDowngradeErrorCallbacks.getAndUpdate(prev => prev :+ f)
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
      circuitBreaker: SpliceCircuitBreaker,
      completionOffsetCallback: Long => Future[Unit] = _ => Future.unit,
  ): SpliceLedgerConnection =
    new SpliceLedgerConnection(
      this.client,
      applicationId,
      baseLoggerFactory.append("connClient", connectionClient),
      retryProvider,
      inactiveContractsCallbacks,
      contractDowngradeErrorCallbacks,
      trafficBalanceService,
      completionOffsetCallback,
      circuitBreaker,
    )

  override def onClosed(): Unit = {
    client.close()
  }
}
