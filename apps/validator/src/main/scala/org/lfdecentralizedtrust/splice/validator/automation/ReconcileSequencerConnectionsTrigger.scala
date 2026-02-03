// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingTrigger,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.validator.domain.DomainConnector
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SequencerConnectionPoolDelays,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ReconcileSequencerConnectionsTrigger(
    baseContext: TriggerContext,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    domainConnector: DomainConnector,
    patience: NonNegativeFiniteDuration,
    initialSynchronizerTimeO: Option[CantonTimestamp],
    newSequencerConnectionPool: Boolean,
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
      maybeDomainTime <-
        participantAdminConnection
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
          val maxDomainTime = initialSynchronizerTimeO match {
            case Some(initialSynchronizerTime) if domainTime < initialSynchronizerTime =>
              // Without this we can end up in situations where we first use a higher time to connect to
              // the synchronizer based on clock.now and then travel back in time as we get a
              // synchronizer connection. That doesn't really make any sense so we take the max.
              logger.info(
                s"Synchronizer time is $domainTime but initial timestamp used to determine synchronizer connections was $initialSynchronizerTime which is higher, using $initialSynchronizerTime instead"
              )
              initialSynchronizerTime
            case _ => domainTime
          }
          for {
            (sequencerConnections, _) <- domainConnector.getSequencerConnectionsFromScan(
              Left(maxDomainTime)
            )
            _ <- MonadUtil.sequentialTraverse_(sequencerConnections.toList) {
              case (alias, connections) =>
                val sequencerConnectionConfig = NonEmpty.from(connections) match {
                  case None =>
                    // We warn on repeated failures of a polling trigger so
                    // it's safe to just treat it as a transient exception and retry without logging warnings.
                    throw Status.NOT_FOUND
                      .withDescription(
                        "Dso Sequencer list from Scan is empty, not modifying sequencers connections. This can happen during initialization when domain time is lagging behind."
                      )
                      .asRuntimeException()
                  case Some(nonEmptyConnections) =>
                    SequencerConnections.tryMany(
                      nonEmptyConnections.forgetNE,
                      Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
                      sequencerLivenessMargin =
                        Thresholds.sequencerConnectionsLivenessMargin(nonEmptyConnections.size),
                      submissionRequestAmplification = SubmissionRequestAmplification(
                        Thresholds.sequencerSubmissionRequestAmplification(
                          nonEmptyConnections.size
                        ),
                        patience,
                      ),
                      // TODO(#2666) Make the delays configurable.
                      sequencerConnectionPoolDelays = SequencerConnectionPoolDelays.default,
                    )
                }
                participantAdminConnection.modifyOrRegisterSynchronizerConnectionConfigAndReconnect(
                  SynchronizerConnectionConfig(
                    alias,
                    sequencerConnectionConfig,
                  ),
                  newSequencerConnectionPool,
                  modifySequencerConnections(sequencerConnectionConfig),
                  RetryFor.Automation,
                )
            }
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
      sequencerConnections: SequencerConnections
  )(implicit
      traceContext: TraceContext
  ): SynchronizerConnectionConfig => Option[SynchronizerConnectionConfig] =
    conf => {
      if (differentEndpointSet(sequencerConnections, conf.sequencerConnections.connections)) {
        logger.info(
          s"modifying sequencers connections to $sequencerConnections"
        )
        Some(conf.copy(sequencerConnections = sequencerConnections))
      } else {
        logger.trace(
          "sequencers connections are already set. not modifying sequencers."
        )
        None
      }
    }

  private def differentEndpointSet(
      sequencerConnections: SequencerConnections,
      connections: NonEmpty[Seq[SequencerConnection]],
  )(implicit tc: TraceContext) =
    sequencerConnections.connections.forgetNE
      .map(ParticipantAdminConnection.dropSequencerId)
      .flatMap(c => sequencerConnectionEndpoint(c).toList)
      .toSet != connections.forgetNE
      .map(ParticipantAdminConnection.dropSequencerId)
      .flatMap(c => sequencerConnectionEndpoint(c).toList)
      .toSet

  private def sequencerConnectionEndpoint(
      connection: SequencerConnection
  )(implicit tc: TraceContext): Option[Endpoint] =
    connection match {
      case GrpcSequencerConnection(endpoints, _, _, _, _) if endpoints.size == 1 =>
        Some(endpoints.head1)
      case GrpcSequencerConnection(endpoints, _, _, _, _) =>
        logger.warn(s"expected exactly 1 endpoint in a sequencer connection but got: $endpoints")
        None
    }
}
