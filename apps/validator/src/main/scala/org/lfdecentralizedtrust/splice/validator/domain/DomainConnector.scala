// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.domain

import cats.implicits.catsSyntaxApplicativeId
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
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

class DomainConnector(
    config: ValidatorAppBackendConfig,
    participantAdminConnection: ParticipantAdminConnection,
    scanConnection: BftScanConnection,
    migrationId: Long,
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
        .lookupSynchronizerConnectionConfig(config.domains.global.alias)
        .flatMap {
          case Some(_) =>
            participantAdminConnection
              .listConnectedDomains()
              .map(
                _.find(_.synchronizerAlias == config.domains.global.alias).fold(
                  throw Status.FAILED_PRECONDITION
                    .withDescription("Global Synchronizer not connected in the participant")
                    .asRuntimeException()
                )(_ => ())
              )
          case None =>
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
    getDecentralizedSynchronizerSequencerConnections(clock).flatMap(x =>
      MonadUtil.sequentialTraverse_(x.toList) { case (alias, connections) =>
        ensureDomainRegistered(alias, connections)
      }
    )
  }

  def getDecentralizedSynchronizerSequencerConnections(clock: Clock)(implicit
      tc: TraceContext
  ): Future[Map[SynchronizerAlias, SequencerConnections]] = {
    config.domains.global.url match {
      case None =>
        participantAdminConnection
          .lookupPhysicalSynchronizerId(config.domains.global.alias)
          .flatMap {
            case Some(serial) =>
              waitForSequencerConnectionsFromScan(clock, serial.serial)
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
                  waitForSequencerConnectionsFromScan(clock, _)
                )
          }
      case Some(url) =>
        Map(
          config.domains.global.alias -> SequencerConnections
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
                config.sequencerRequestAmplificationPatience,
              ),
              sequencerConnectionPoolDelays = config.sequencerConnectionPoolDelays,
            )
        ).pure[Future]
    }
  }

  def ensureExtraDomainsRegistered()(implicit tc: TraceContext): Future[Unit] =
    MonadUtil.sequentialTraverse_(config.domains.extra)(synchronizer =>
      ensureDomainRegistered(
        synchronizer.alias,
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

  private def ensureDomainRegistered(
      alias: SynchronizerAlias,
      sequencerConnections: SequencerConnections,
  )(implicit tc: TraceContext): Future[Unit] = {
    val domainConfig = SynchronizerConnectionConfig(
      alias,
      sequencerConnections,
      timeTracker = SynchronizerTimeTrackerConfig(
        minObservationDuration = config.timeTrackerMinObservationDuration,
        observationLatency = config.timeTrackerObservationLatency,
      ),
    )
    logger.info(s"Ensuring domain $alias registered with config $domainConfig")
    participantAdminConnection.ensureDomainRegisteredAndConnected(
      domainConfig,
      overwriteExistingConnection = true,
      reconnectOnSynchronizerConfigurationChange =
        config.parameters.enabledFeatures.reconnectOnSynchronizerConfigurationChange,
      retryFor = RetryFor.WaitingOnInitDependency,
    )
  }

  private def waitForSequencerConnectionsFromScan(clock: Clock, synchronizerSerial: NonNegativeInt)(
      implicit tc: TraceContext
  ): Future[Map[SynchronizerAlias, SequencerConnections]] = {
    retryProvider.getValueWithRetries(
      // Short retries since usually a failure here is just a misconfiguration error.
      // The only case where this can happen is during a domain migration and even then
      // it is fairly unlikely outside of tests for validators to come up fast enough that
      // scan has not yet updated.
      RetryFor.WaitingOnInitDependency, // because the scan connections might still be in bootstrap phase
      "scan_sequencer_connections",
      "non-empty sequencer connections from scan",
      getSequencerConnectionsFromScan(Right(clock), synchronizerSerial)
        .map { case (connections, time) =>
          if (connections.isEmpty) {
            throw Status.NOT_FOUND
              .withDescription(
                s"sequencer connections for migration id $migrationId and serial $synchronizerSerial is empty at $time, validate with your SV sponsor that your migration id is correct"
              )
              .asRuntimeException()
          } else {
            connections.view.mapValues {
              NonEmpty.from(_) match {
                case None =>
                  throw Status.NOT_FOUND
                    .withDescription(
                      s"sequencer connections for migration id $migrationId and serial $synchronizerSerial is empty at $time, validate with your SV sponsor that your migration id is correct"
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
                  )
              }
            }.toMap
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
  ): Future[(Map[SynchronizerAlias, Seq[GrpcSequencerConnection]], CantonTimestamp)] = {
    val domainTime = timeOrClock match {
      case Left(time) => time
      case Right(clock) => clock.now
    }
    for {
      domainSequencers <- scanConnection.listDsoSequencers()
      decentralizedSynchronizerId <- scanConnection.getAmuletRulesDomain()(traceContext)
    } yield {
      domainSequencers
        .filter(sequencers =>
          // This filter should be a noop since we only ever expect to have one synchronizer here
          // so this is just an extra safeguard.
          sequencers.synchronizerId == decentralizedSynchronizerId
        )
        .map { sequencers =>
          val serialOrMigrationSequencers =
            sequencers.sequencers
              .groupBy(_.id)
              .view
              .mapValues { sequencersForId =>
                val serialMatch =
                  sequencersForId.find(_.serial.contains(synchronizerSerial.unwrap.toLong))
                // it might be that some SV did not update the url for the latest serial
                // in that case we don't want to fallback to the migration id one
                // the migration id fallback is valid only if the SV did not sync the per serial urls yet for the first time
                val sequencerHasAnyEntryWithSerial = sequencersForId.exists(_.serial.nonEmpty)
                if (sequencerHasAnyEntryWithSerial) serialMatch
                else
                  serialMatch.orElse(
                    sequencersForId.find(s => s.serial.isEmpty && s.migrationId == migrationId)
                  )
              }
              .values
              .flatten
              .toSeq
          val svFilteredSequencers = config.domains.global.trustedSynchronizerConfig match {
            case Some(config) =>
              val allowedNamesSet = config.svNames.toList.toSet
              logger.debug(
                s"Filtering sequencers to only include: ${allowedNamesSet.toList.mkString(", ")}"
              )
              serialOrMigrationSequencers.filter(s => allowedNamesSet.contains(s.svName))
            case None =>
              serialOrMigrationSequencers
          }
          val validConnections = extractValidConnections(
            svFilteredSequencers,
            domainTime,
          )
          logger.debug(
            s"For synchronizer ${config.domains.global.alias} at time $domainTime using $validConnections from scan"
          )
          config.domains.global.alias ->
            validConnections
        }
        .toMap -> domainTime
    }
  }

  private def extractValidConnections(
      sequencers: Seq[DsoSequencer],
      domainTime: CantonTimestamp,
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, empty url or not yet available (`before availableAfter`)
    sequencers
      .collect {
        case DsoSequencer(_, _, id, url, _, availableAfter)
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
