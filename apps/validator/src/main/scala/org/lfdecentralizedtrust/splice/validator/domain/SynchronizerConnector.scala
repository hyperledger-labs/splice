// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.domain

import cats.implicits.catsSyntaxApplicativeId
import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.{SequencerAlias, SynchronizerAlias}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.config.SynchronizerTimeTrackerConfig
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoSequencer
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig

import scala.concurrent.{ExecutionContext, Future}

class SynchronizerConnector(
    config: ValidatorAppBackendConfig,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def waitForDecentralizedSynchronizerIsRegisteredAndConnected()(implicit
      tc: TraceContext
  ): Future[Unit] = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "ensure_decentralized_synchronizer_registered",
      "decentralized synchronizer is already registered",
      participantAdminConnection
        .isSynchronizerRegistered(config.domains.global.alias)
        .flatMap {
          case true =>
            participantAdminConnection
              .listConnectedSynchronizers()
              .map(
                _.find(_.synchronizerAlias == config.domains.global.alias).fold(
                  throw Status.FAILED_PRECONDITION
                    .withDescription("Global Synchronizer not connected in the participant")
                    .asRuntimeException()
                )(_ => ())
              )
          case false =>
            throw Status.NOT_FOUND
              .withDescription("Global Synchronizer not registered in the participant")
              .asRuntimeException()
        },
      logger,
    )
  }

  def ensureDecentralizedSynchronizerRegisteredAndConnectedWithCurrentConfig(clock: Clock)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    getDecentralizedSynchronizerConfigs(clock).flatMap(config => ensureDomainRegistered(config))
  }

  private def getDecentralizedSynchronizerConfigs(clock: Clock)(implicit
      tc: TraceContext
  ): Future[SynchronizerConnectionConfig] = {
    config.domains.global.url match {
      case None =>
        participantAdminConnection
          .lookupPhysicalSynchronizerId(config.domains.global.alias)
          .flatMap {
            case Some(psid) =>
              waitForSequencerConnectionsFromScan(clock, psid.serial).map(connections =>
                buildSynchronizerConfig(
                  config.domains.global.alias,
                  Some(psid),
                  connections,
                )
              )
            case None =>
              logger.info("No registered physical synchronizer id, using active id from scan")
              retryProvider
                .getValueWithRetries(
                  RetryFor.Automation,
                  "scan_physical_synchronizer_id",
                  "physical synchronizer id from scan",
                  scanConnection
                    .getActivePhysicalSynchronizerSerial(),
                  logger,
                )
                .flatMap(
                  waitForSequencerConnectionsFromScan(clock, _).map(connections =>
                    buildSynchronizerConfig(
                      config.domains.global.alias,
                      None,
                      connections,
                    )
                  )
                )
          }
      case Some(url) =>
        buildSynchronizerConfig(
          config.domains.global.alias,
          None,
          SequencerConnections
            .tryMany(
              connections = Seq(
                GrpcSequencerConnection
                  .create(url)
                  .fold(
                    error =>
                      throw Status.INVALID_ARGUMENT
                        .withDescription(s"Invalid synchronizer url $url: $error")
                        .asRuntimeException(),
                    identity,
                  )
              ),
              sequencerTrustThreshold = PositiveInt.one,
              sequencerLivenessMargin = NonNegativeInt.zero,
              submissionRequestAmplification = SubmissionRequestAmplification(
                PositiveInt.one,
                config.sequencerRequestAmplificationPatience.toInternal,
              ),
              sequencerConnectionPoolDelays = config.sequencerConnectionPoolDelays.toInternal,
              subscriptionLivenessLimits = config.subscriptionLivenessLimits.toInternal,
            ),
        ).pure[Future]
    }
  }

  def ensureExtraDomainsRegistered()(implicit tc: TraceContext): Future[Unit] =
    MonadUtil.sequentialTraverse_(config.domains.extra)(synchronizer =>
      ensureDomainRegistered(
        buildSynchronizerConfig(
          synchronizer.alias,
          None,
          SequencerConnections.single(
            GrpcSequencerConnection
              .create(synchronizer.url)
              .fold(
                error =>
                  throw Status.INVALID_ARGUMENT
                    .withDescription(
                      s"Invalid synchronizer url for synchronizer $synchronizer: $error"
                    )
                    .asRuntimeException(),
                identity,
              )
          ),
        )
      )
    )

  private def ensureDomainRegistered(
      syncConfig: SynchronizerConnectionConfig
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(
      s"Ensuring domain ${syncConfig.synchronizerAlias} registered with config $syncConfig"
    )
    participantAdminConnection.ensureSynchronizerRegisteredAndConnected(
      syncConfig,
      overwriteExistingConnection = true,
      reconnectOnSynchronizerConfigurationChange =
        config.parameters.enabledFeatures.reconnectOnSynchronizerConfigurationChange,
      retryFor = RetryFor.WaitingOnInitDependency,
    )
  }

  private def buildSynchronizerConfig(
      alias: SynchronizerAlias,
      psid: Option[PhysicalSynchronizerId],
      sequencerConnections: SequencerConnections,
  ) = {
    SynchronizerConnectionConfig(
      alias,
      sequencerConnections,
      psid = psid,
      timeTracker = SynchronizerTimeTrackerConfig(
        minObservationDuration = config.timeTrackerMinObservationDuration,
        observationLatency = config.timeTrackerObservationLatency,
      ),
    )
  }

  private def waitForSequencerConnectionsFromScan(clock: Clock, synchronizerSerial: NonNegativeInt)(
      implicit tc: TraceContext
  ): Future[SequencerConnections] = {
    retryProvider.getValueWithRetries(
      // Short retries since usually a failure here is just a misconfiguration error.
      // The only case where this can happen is during a domain migration and even then
      // it is fairly unlikely outside of tests for validators to come up fast enough that
      // scan has not yet updated.
      RetryFor.WaitingOnInitDependency, // because the scan connections might still be in bootstrap phase
      "scan_sequencer_connections",
      "non-empty sequencer connections from scan",
      getSequencerConnectionsFromScan(Right(clock), synchronizerSerial)
        .map { case (_, connections, time) =>
          if (connections.isEmpty) {
            throw Status.NOT_FOUND
              .withDescription(
                s"sequencer connections for serial $synchronizerSerial is empty at $time"
              )
              .asRuntimeException()
          } else {
            NonEmpty.from(connections) match {
              case None =>
                throw Status.NOT_FOUND
                  .withDescription(
                    s"sequencer connections for serial $synchronizerSerial is empty at $time"
                  )
                  .asRuntimeException()
              case Some(nonEmptyConnections) =>
                val threshold: PositiveInt =
                  config.domains.global.trustedSynchronizerConfig match {
                    case Some(config) => PositiveInt.tryCreate(config.threshold)
                    case None =>
                      Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size)
                  }

                // max(threshold, sequencerConnectionsSizeThreshold) is used to ensure that even in small configurations
                // (e.g., N=3, f+1=1) we have enough retries to make sure that the request is eventually processed by a live sequencer.

                val amplificationFactor = PositiveInt.tryCreate(
                  Math.max(
                    threshold.unwrap,
                    Thresholds
                      .sequencerSubmissionRequestAmplification(nonEmptyConnections.size)
                      .unwrap,
                  )
                )

                SequencerConnections.tryMany(
                  nonEmptyConnections.forgetNE,
                  threshold,
                  submissionRequestAmplification = SubmissionRequestAmplification(
                    amplificationFactor,
                    config.sequencerRequestAmplificationPatience.toInternal,
                  ),
                  sequencerLivenessMargin =
                    Thresholds.sequencerConnectionsLivenessMargin(nonEmptyConnections.size),
                  sequencerConnectionPoolDelays = config.sequencerConnectionPoolDelays.toInternal,
                  subscriptionLivenessLimits = config.subscriptionLivenessLimits.toInternal,
                )
            }
          }
        },
      logger,
    )
  }

  def getSequencerConnectionsFromScan(
      timeOrClock: Either[CantonTimestamp, Clock],
      synchronizerSerial: NonNegativeInt,
  )(implicit
      traceContext: TraceContext
  ): Future[(SynchronizerAlias, Seq[GrpcSequencerConnection], CantonTimestamp)] = {
    val domainTime = timeOrClock match {
      case Left(time) => time
      case Right(clock) => clock.now
    }
    for {
      domainSequencers <- scanConnection.listDsoSequencers()
      decentralizedSynchronizerId <- scanConnection.getAmuletRulesDomain()(traceContext)
    } yield {
      (
        config.domains.global.alias,
        domainSequencers
          .filter(sequencers =>
            // This filter should be a noop since we only ever expect to have one synchronizer here
            // so this is just an extra safeguard.
            sequencers.synchronizerId == decentralizedSynchronizerId
          )
          .flatMap { sequencers =>
            val serialSequencers =
              sequencers.sequencers
                .filter(_.serial == synchronizerSerial.unwrap.toLong)
            val svFilteredSequencers = config.domains.global.trustedSynchronizerConfig match {
              case Some(config) =>
                val allowedNamesSet = config.svNames.toList.toSet
                logger.debug(
                  s"Filtering sequencers to only include: ${allowedNamesSet.toList.mkString(", ")}"
                )
                serialSequencers.filter(s => allowedNamesSet.contains(s.svName))
              case None =>
                serialSequencers
            }
            val validConnections = extractValidConnections(
              svFilteredSequencers,
              domainTime,
            )
            logger.debug(
              s"For synchronizer ${config.domains.global.alias} at time $domainTime using $validConnections from scan"
            )
            validConnections
          },
        domainTime,
      )
    }
  }

  private def extractValidConnections(
      sequencers: Seq[DsoSequencer],
      domainTime: CantonTimestamp,
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, empty url or not yet available (`before availableAfter`)
    sequencers
      .collect {
        case DsoSequencer(_, id, url, _, availableAfter)
            if url.nonEmpty && !domainTime.toInstant
              .isBefore(availableAfter) =>
          for {
            sequencerAlias <- SequencerAlias.create(id.toProtoPrimitive)
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
  }
}
