// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext, TriggerEnabledSynchronization}
import com.daml.network.config.Thresholds
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.validator.domain.DomainConnector
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencerConnectionsTrigger(
    baseContext: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    decentralizedSynchronizerAlias: DomainAlias,
    domainConnector: DomainConnector,
    patience: NonNegativeFiniteDuration,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  // Disabling domain time and params sync since we might need to fix domain connections to allow for catchup.
  override protected lazy val context =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      decentralizedSynchronizer <- amuletRulesDomain()
      maybeDomainTime <- participantAdminConnection
        .getDomainTimeLowerBound(
          decentralizedSynchronizer,
          maxDomainTimeLag = context.config.pollingInterval,
        )
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
            sequencerConnections <- domainConnector.getSequencerConnectionsFromScan(domainTime)
            _ <- participantAdminConnection.modifyDomainConnectionConfigAndReconnect(
              decentralizedSynchronizerAlias, // TODO (#8450) how?
              modifySequencerConnections(sequencerConnections),
            )
          } yield ()
        case None =>
          logger.debug("time tracker from the domain is not yet available, skipping")
          Future.unit
      }

    } yield false
  }

  private[this] def amuletRulesDomain()(implicit tc: TraceContext) =
    scanConnection.getAmuletRulesDomain()(tc)

  private def modifySequencerConnections(
      sequencerConnections: Seq[GrpcSequencerConnection]
  )(implicit traceContext: TraceContext): DomainConnectionConfig => Option[DomainConnectionConfig] =
    conf => {
      if (differentEndpointSet(sequencerConnections, conf.sequencerConnections.connections)) {
        NonEmpty.from(sequencerConnections) match {
          case None =>
            // We warn on repeated failures of a polling trigger so
            // it's safe to just treat it as a transient exception and retry without logging warnings.
            throw Status.NOT_FOUND
              .withDescription(
                "Dso Sequencer list from Scan is empty, not modifying sequencers connections. This can happen during initialization when domain time is lagging behind."
              )
              .asRuntimeException()
          case Some(nonEmptyConnections) =>
            logger.info(
              s"modifying sequencers connections to $nonEmptyConnections"
            )
            Some(
              conf.copy(
                sequencerConnections = SequencerConnections.tryMany(
                  nonEmptyConnections.forgetNE,
                  Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
                  submissionRequestAmplification = SubmissionRequestAmplification(
                    Thresholds.sequencerSubmissionRequestAmplification(nonEmptyConnections.size),
                    patience,
                  ),
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
