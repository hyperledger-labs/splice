package com.daml.network.validator.domain

import cats.implicits.{catsSyntaxApplicativeId, toFoldableOps}
import com.daml.network.config.CNThresholds
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.SvcSequencer
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.data.CantonTimestamp
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
    migrationId: Long,
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
        sequencerConnectionsFromScan(config.domains.global.submissionRequestAmplification)
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

  private def sequencerConnectionsFromScan(
      submissionRequestAmplification: PositiveInt
  )(implicit tc: TraceContext) = for {
    _ <- waitForSequencerConnectionsFromScan(logger, retryProvider)
    sequencerConnections <- getSequencerConnectionsFromScan(clock.now)
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
      logger: TracedLogger,
      retryProvider: RetryProvider,
  )(implicit tc: TraceContext) = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "valid sequencer connections from scan is non empty",
      getSequencerConnectionsFromScan(clock.now)
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

  def getSequencerConnectionsFromScan(
      domainTime: CantonTimestamp
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[GrpcSequencerConnection]] = {
    for {
      globalDomainId <- scanConnection.getCoinRulesDomain()(traceContext)
      domainSequencers <- scanConnection.listSvcSequencers()
      maybeSequencers = domainSequencers.find(_.domainId == globalDomainId)
    } yield maybeSequencers.fold {
      logger.warn("global domain sequencer list not found.")
      Seq.empty[GrpcSequencerConnection]
    } { domainSequencer =>
      extractValidConnections(domainSequencer.sequencers, domainTime, migrationId)
    }
  }

  private def extractValidConnections(
      sequencers: Seq[SvcSequencer],
      domainTime: CantonTimestamp,
      migrationId: Long,
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, empty url or not yet available (`before availableAfter`)
    val validConnections = sequencers
      .collect {
        case SvcSequencer(sequencerMigrationId, _, url, svName, availableAfter)
            if migrationId == sequencerMigrationId && url.nonEmpty && !domainTime.toInstant
              .isBefore(availableAfter) =>
          for {
            sequencerAlias <- SequencerAlias.create(svName)
            grpcSequencerConnection <- GrpcSequencerConnection.create(
              url,
              None,
              sequencerAlias,
            )
          } yield grpcSequencerConnection
      }
      .collect { case Right(conn) =>
        conn
      }
    validConnections
  }
}
