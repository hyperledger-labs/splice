// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.domain

import cats.implicits.{catsSyntaxApplicativeId}
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DsoSequencer
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{SynchronizerAlias, SequencerAlias}
import com.digitalasset.canton.config.SynchronizerTimeTrackerConfig
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnections,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status

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

  def ensureDecentralizedSynchronizerRegisteredAndConnectedWithCurrentConfig(time: CantonTimestamp)(
      implicit tc: TraceContext
  ): Future[Unit] = {
    getDecentralizedSynchronizerSequencerConnections(time).flatMap(x =>
      MonadUtil.sequentialTraverse_(x.toList) { case (alias, connections) =>
        ensureDomainRegistered(alias, connections)
      }
    )
  }

  def getDecentralizedSynchronizerSequencerConnections(time: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Map[SynchronizerAlias, SequencerConnections]] = {
    config.domains.global.url match {
      case None =>
        waitForSequencerConnectionsFromScan(time)
      case Some(url) =>
        Map(
          config.domains.global.alias -> SequencerConnections
            .single(GrpcSequencerConnection.tryCreate(url))
        ).pure[Future]
    }
  }

  def ensureExtraDomainsRegistered()(implicit tc: TraceContext): Future[Unit] =
    MonadUtil.sequentialTraverse_(config.domains.extra)(domain =>
      ensureDomainRegistered(
        domain.alias,
        SequencerConnections.single(GrpcSequencerConnection.tryCreate(domain.url)),
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
      retryFor = RetryFor.WaitingOnInitDependency,
    )
  }

  private def waitForSequencerConnectionsFromScan(
      time: CantonTimestamp
  )(implicit tc: TraceContext): Future[Map[SynchronizerAlias, SequencerConnections]] = {
    retryProvider.getValueWithRetries(
      // Short retries since usually a failure here is just a misconfiguration error.
      // The only case where this can happen is during a domain migration and even then
      // it is fairly unlikely outside of tests for validators to come up fast enough that
      // scan has not yet updated.
      RetryFor.ClientCalls,
      "scan_sequencer_connections",
      "non-empty sequencer connections from scan",
      getSequencerConnectionsFromScan(time)
        .map { connections =>
          if (connections.isEmpty) {
            throw Status.NOT_FOUND
              .withDescription(
                s"sequencer connections for migration id $migrationId is empty, validate with your SV sponsor that your migration id is correct"
              )
              .asRuntimeException()
          } else {
            connections.view.mapValues {
              NonEmpty.from(_) match {
                case None =>
                  throw Status.NOT_FOUND
                    .withDescription(
                      s"sequencer connections for migration id $migrationId is empty, validate with your SV sponsor that your migration id is correct"
                    )
                    .asRuntimeException()
                case Some(nonEmptyConnections) =>
                  SequencerConnections.tryMany(
                    nonEmptyConnections.forgetNE,
                    Thresholds.sequencerConnectionsSizeThreshold(nonEmptyConnections.size),
                    submissionRequestAmplification = SubmissionRequestAmplification(
                      Thresholds.sequencerSubmissionRequestAmplification(nonEmptyConnections.size),
                      config.sequencerRequestAmplificationPatience,
                    ),
                    // TODO(#2110) Rethink this when we enable sequencer connection pools.
                    sequencerLivenessMargin = NonNegativeInt.zero,
                  )
              }
            }.toMap
          }
        },
      logger,
    )
  }

  def getSequencerConnectionsFromScan(
      domainTime: CantonTimestamp
  )(implicit
      traceContext: TraceContext
  ): Future[Map[SynchronizerAlias, Seq[GrpcSequencerConnection]]] = {
    for {
      domainSequencers <- scanConnection.listDsoSequencers()
      decentralizedSynchronizerId <- scanConnection.getAmuletRulesDomain()(traceContext)
    } yield {
      val filteredSequencers = domainSequencers
        .filter(sequencers =>
          // This filter should be a noop since we only ever expect to have one synchronizer here
          // so this is just an extra safeguard.
          sequencers.synchronizerId == decentralizedSynchronizerId
        )
      filteredSequencers.map { domainSequencer =>
        config.domains.global.alias ->
          extractValidConnections(domainSequencer.sequencers, domainTime, migrationId)
      }.toMap
    }
  }

  private def extractValidConnections(
      sequencers: Seq[DsoSequencer],
      domainTime: CantonTimestamp,
      migrationId: Long,
  ): Seq[GrpcSequencerConnection] = {
    // sequencer connections will be ignore if they are with a invalid Alias, empty url or not yet available (`before availableAfter`)
    val validConnections = sequencers
      .collect {
        case DsoSequencer(sequencerMigrationId, _, url, svName, availableAfter)
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

object DomainConnector {
  final case class DomainConnection(
      alias: SynchronizerAlias,
      connections: Seq[GrpcSequencerConnection],
  )
}
