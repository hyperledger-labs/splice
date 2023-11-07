package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.config.CNThresholds
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.validator.ValidatorApp
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status.Code
import io.grpc.StatusRuntimeException
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencerConnectionsTrigger(
    override protected val context: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: ScanConnection,
    globalDomainAlias: DomainAlias,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      maybeDomainTime <- participantAdminConnection
        .getDomainTime(globalDomainAlias, timeouts.default)
        .map(domainTime => Some(domainTime.timestamp))
        .recover {
          // Time tracker for domain not found. the domainTime is not yet available.
          case ex: StatusRuntimeException
              if ex.getStatus.getCode == Code.INVALID_ARGUMENT &&
                ex.getStatus.getDescription.contains("Time tracker for domain") =>
            None
        }
      _ <- maybeDomainTime match {
        case Some(domainTime) =>
          for {
            sequencerConnections <- ValidatorApp.getSequencerConnectionsFromScan(
              scanConnection,
              logger,
              domainTime,
            )
            isModified <- participantAdminConnection.modifyDomainConnectionConfig(
              globalDomainAlias,
              modifySequencerConnections(sequencerConnections),
            )
            _ <-
              if (isModified) {
                // reconnect to the domain for new sequencer configuration to take effect
                participantAdminConnection.reconnectDomain(globalDomainAlias)
              } else Future.unit
          } yield ()
        case None =>
          logger.debug("time tracker from the domain is not yet available, skipping")
          Future.unit
      }

    } yield false
  }

  private def modifySequencerConnections(
      sequencerConnections: Seq[GrpcSequencerConnection]
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf => {
      if (differentEndpointSet(sequencerConnections, conf.sequencerConnections.connections)) {
        NonEmpty.from(sequencerConnections) match {
          case None =>
            // TODO: (#8015) remove the domain in this case
            logger.warn(
              "Svc Sequencer list from Scan is empty, not modifying sequencers connections."
            )
            None
          case Some(nonEmptyConnections) =>
            logger.info(
              s"modifying sequencers connections to $nonEmptyConnections"
            )
            Some(
              conf.copy(
                sequencerConnections = SequencerConnections.many(
                  nonEmptyConnections,
                  CNThresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
                )
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
