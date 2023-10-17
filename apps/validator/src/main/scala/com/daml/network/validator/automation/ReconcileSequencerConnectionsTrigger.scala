package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.config.CNThresholds
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.scan.admin.api.client.ScanConnection.GetCoinRulesDomain
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.SvcSequencer
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DomainAlias, SequencerAlias}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencerConnectionsTrigger(
    override protected val context: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: ScanConnection,
    clock: Clock,
    getCoinRulesDomain: GetCoinRulesDomain,
    globalDomainAlias: DomainAlias,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      domainSequencers <- scanConnection.listSvcSequencers()
      globalDomainId <- getCoinRulesDomain()(traceContext)
      maybeSequencers = domainSequencers.find(_.domainId == globalDomainId)
      _ <- maybeSequencers.fold {
        logger.warn("global domain sequencer list not found.")
        Future.unit
      } { domainSequencer =>
        participantAdminConnection.modifyDomainConnectionConfig(
          globalDomainAlias,
          modifySequencerConnections(domainSequencer.sequencers),
        )
      }
    } yield false
  }

  private def modifySequencerConnections(
      sequencers: Seq[SvcSequencer]
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf => {
      val connections = extractValidConnections(sequencers)
      if (differentEndpointSet(connections, conf.sequencerConnections.connections)) {
        NonEmpty.from(connections) match {
          case None =>
            // TODO: (#8015) remove the domain in this case
            logger.warn(
              "Svc Sequencer list from Scan is empty, not modifying sequencers connections."
            )
            None
          case Some(nonEmptyConnections) =>
            logger.info(
              s"modifying sequencers connections"
            )
            Some(
              conf.copy(
                sequencerConnections = getSequencersConnections(nonEmptyConnections)
              )
            )
        }
      } else {
        logger.trace(
          "sequencers connections are already set. not modifying sequencers."
        )
        None
      }
    }

  private def getSequencersConnections(
      connections: NonEmpty[Seq[SequencerConnection]]
  ): SequencerConnections = {
    val threshold = CNThresholds.getSequencerConnectionsSizeThreshold(connections.size)
    SequencerConnections.many(connections, threshold)
  }

  private def extractValidConnections(
      sequencers: Seq[SvcSequencer]
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, url or not yet available (`before availableAfter`)
    val validConnections = sequencers
      .collect {
        case SvcSequencer(_, url, svName, availableAfter)
            if !clock.now.toInstant.isBefore(availableAfter) =>
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

  private def differentEndpointSet(
      sequencerConnections: Seq[GrpcSequencerConnection],
      connections: NonEmpty[Seq[SequencerConnection]],
  )(implicit traceContext: TraceContext) = {
    val newEndpointSet = sequencerConnections
      .map(conn => conn.endpoints.head1)
      .toSet
    val existingEndpointSet = connections
      .collect {
        case GrpcSequencerConnection(endpoints, _, _, _) if endpoints.size == 1 =>
          Some(endpoints.head1)
        case GrpcSequencerConnection(endpoints, _, _, _) if endpoints.size > 1 =>
          logger.warn(s"only 1 single endpoint in a sequencer connection is expected. $endpoints")
          None
      }
      .flatten
      .toSet
    newEndpointSet != existingEndpointSet
  }
}
