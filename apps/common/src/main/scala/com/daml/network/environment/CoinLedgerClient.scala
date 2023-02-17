package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.client.configuration.LedgerClientChannelConfiguration
import com.daml.network.admin.api.client.ApiClientRequestLogger
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, FlagCloseableAsync, SyncCloseable}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.grpc.ClientChannelBuilder
import com.digitalasset.canton.tracing.TracerProvider
import io.opentelemetry.instrumentation.grpc.v1_6.GrpcTracing

import scala.concurrent.{ExecutionContextExecutor, Future}

trait CoinLedgerClient extends FlagCloseableAsync {
  def applicationId: String
  def client: LedgerClient
  def connection(
  ): CoinLedgerConnection with NamedLogging
  def timeouts: ProcessingTimeout

  def actorSystem: ActorSystem
  def executionContextExecutor: ExecutionContextExecutor
}

object CoinLedgerClient {

  private def createLedgerClient(
      config: ClientConfig,
      token: Option[String],
      tracerProvider: TracerProvider,
      loggerFactory: NamedLoggerFactory,
      apiLoggingConfig: ApiLoggingConfig,
  )(implicit
      ec: ExecutionContextExecutor,
      esf: ExecutionSequencerFactory,
      elc: ErrorLoggingContext,
  ): Future[LedgerClient] = {

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
    val client = new LedgerClient(channel, token)
    Future.successful(client)
  }

  def create(
      clientConfig: ClientConfig,
      applicationId_ : String,
      token: Option[String],
      processingTimeouts: ProcessingTimeout,
      apiLoggingConfig: ApiLoggingConfig,
      loggerFactoryForCoinLedgerConnectionOverride: NamedLoggerFactory,
      tracerProvider: TracerProvider,
  )(implicit
      ec: ExecutionContextExecutor,
      as: ActorSystem,
      esf: ExecutionSequencerFactory,
      elc: ErrorLoggingContext,
  ): Future[CoinLedgerClient with NamedLogging] =
    createLedgerClient(
      clientConfig,
      token,
      tracerProvider,
      loggerFactoryForCoinLedgerConnectionOverride,
      apiLoggingConfig,
    ).map { client_ =>
      new CoinLedgerClient with NamedLogging {

        override val client: LedgerClient = client_

        override def connection(
        ): CoinLedgerConnection with NamedLogging =
          CoinLedgerConnection(
            this,
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
