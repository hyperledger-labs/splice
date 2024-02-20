package com.daml.network.validator.domain

import cats.implicits.{catsSyntaxApplicativeId, toFoldableOps}
import com.daml.network.config.CNThresholds
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.validator.ValidatorApp
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{GrpcSequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

class DomainConnector(
    config: ValidatorAppBackendConfig,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    clock: Clock,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def ensureGlobalDomainRegistered()(implicit tc: TraceContext): Future[Unit] = {
    val globalDomainAlias = config.domains.global.alias
    getGlobalDomainSequencerConnections.flatMap(ensureDomainRegistered(globalDomainAlias, _))
  }

  def getGlobalDomainSequencerConnections(implicit
      tc: TraceContext
  ): Future[SequencerConnections] = {
    // TODO (#8450) config.domains.global.alias and config.domains.global.url are wrong if global has migrated
    config.domains.global.url match {
      case None =>
        getSequencerConnectionsFromScan(config.domains.global.submissionRequestAmplification)
      case Some(url) =>
        SequencerConnections.single(GrpcSequencerConnection.tryCreate(url)).pure[Future]
    }
  }

  def ensureExtraDomainsRegistered()(implicit tc: TraceContext): Future[Unit] =
    config.domains.extra.traverse_(domain =>
      ensureDomainRegistered(
        domain.alias,
        SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
      )
    )

  private def ensureDomainRegistered(
      alias: DomainAlias,
      sequencerConnections: SequencerConnections,
  )(implicit tc: TraceContext): Future[Unit] = {
    val domainConfig = DomainConnectionConfig(
      alias,
      sequencerConnections,
    )
    logger.info(s"Ensuring domain $alias registered with config $domainConfig")
    participantAdminConnection.ensureDomainRegisteredAndConnected(
      domainConfig,
      RetryFor.WaitingOnInitDependency,
    )
  }

  private def getSequencerConnectionsFromScan(
      submissionRequestAmplification: PositiveInt
  )(implicit tc: TraceContext) = for {
    _ <- waitForSequencerConnectionsFromScan(
      scanConnection,
      logger,
      retryProvider,
    )
    sequencerConnections <- ValidatorApp.getSequencerConnectionsFromScan(
      scanConnection,
      logger,
      clock.now,
    )
  } yield NonEmpty.from(sequencerConnections) match {
    case None =>
      sys.error("sequencer connections from scan is not expected to be empty.")
    case Some(nonEmptyConnections) =>
      SequencerConnections.tryMany(
        nonEmptyConnections.forgetNE,
        CNThresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
        submissionRequestAmplification = submissionRequestAmplification,
      )
  }

  private def waitForSequencerConnectionsFromScan(
      scanConnection: BftScanConnection,
      logger: TracedLogger,
      retryProvider: RetryProvider,
  )(implicit tc: TraceContext) = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "valid sequencer connections from scan is non empty",
      ValidatorApp
        .getSequencerConnectionsFromScan(
          scanConnection,
          logger,
          clock.now,
        )
        .map { connections =>
          if (connections.isEmpty)
            throw Status.NOT_FOUND
              .withDescription(
                s"sequencer connections is empty"
              )
              .asRuntimeException()
        },
      logger,
    )
  }

}
