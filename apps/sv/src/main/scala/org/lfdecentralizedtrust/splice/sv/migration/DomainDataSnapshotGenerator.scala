// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import cats.syntax.apply.*
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.migration.{
  AcsExporter,
  DarExporter,
  MigrationSynchronizersState,
  SynchronizerParametersStateTopologyConnection,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import org.lfdecentralizedtrust.splice.migration.AcsExporter.AcsExportForParties

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
    sequencerAdminConnection: SequencerAdminConnection,
    dsoStore: SvDsoStore,
    acsExporter: AcsExporter,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  private val darExporter = new DarExporter(participantAdminConnection)

  private val domainStateTopology = new SynchronizerParametersStateTopologyConnection(
    participantAdminConnection
  )

  // This is the unsafe version used for disaster recovery that allows exporting at any timestamp.
  def getDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId],
      force: Boolean,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
    cantonTimestamp = CantonTimestamp.tryFromInstant(timestamp)
    topologySnapshot <- sequencerAdminConnection.getGenesisState(cantonTimestamp)
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        decentralizedSynchronizer,
        timestamp,
        force,
        AcsExportForParties.OnlyForParties(
          partyId.fold(Seq(dsoStore.key.dsoParty, dsoStore.key.svParty))(Seq(_)).toSet
        ),
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    Some(topologySnapshot),
    acsSnapshot,
    acsTimestamp = timestamp,
    dars,
    synchronizerWasPaused = false,
  )

  private def exportGenesisState(
      decentralizedSynchronizer: SynchronizerId,
      participantParamsState: MigrationSynchronizersState,
      timestamp: CantonTimestamp,
  )(implicit ec: ExecutionContext, tc: TraceContext) = {
    for {
      // The sequencer can lag behind and queries will not fail but silently return an earlier state, so synchronize on it.
      // See https://github.com/DACH-NY/canton/issues/20658
      _ <- retryProvider.waitUntil(
        RetryFor.Automation,
        "sequencer_paused_domain",
        "sequencer observes SynchronizerParametersState that pauses domain",
        for {
          sequencerDomainParameters <- sequencerAdminConnection.getSynchronizerParametersState(
            decentralizedSynchronizer
          )
        } yield {
          if (
            sequencerDomainParameters.base.serial < participantParamsState.currentState.base.serial
          ) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Sequencer has not yet observed SynchronizerParametersState with serial >= ${participantParamsState.currentState.base.serial}, current serial: ${sequencerDomainParameters.base.serial}"
              )
              .asRuntimeException()
          }
        },
        logger,
      )
      sequencerDomainParamsPaused <- domainStateTopology
        .firstAuthorizedStateForTheLatestSynchronizerParametersState(
          decentralizedSynchronizer
        )
        .getOrElse {
          throw Status.FAILED_PRECONDITION
            .withDescription("No domain state topology found")
            .asRuntimeException()
        }
      sequencerPausedTimestamp = CantonTimestamp.tryFromInstant(
        sequencerDomainParamsPaused.exportTimestamp
      )
      _ = if (sequencerPausedTimestamp != timestamp) {
        throw Status.INTERNAL
          .withDescription(
            s"Participant sees domain as paused at $timestamp while sequencer sees domain as paused at ${sequencerPausedTimestamp}"
          )
          .asRuntimeException()
      }
      _ = logger.info("Starting export of genesis state from sequencer")
      genesisState <- sequencerAdminConnection.getGenesisState(timestamp)
      _ = logger.info("Finished export of genesis state from sequencer")
    } yield genesisState
  }

  // This is the safe version used for migrations that exports at the timestamp where we pause the synchronizer.
  def getDomainMigrationSnapshot(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
    participantParamsState <- domainStateTopology
      .firstAuthorizedStateForTheLatestSynchronizerParametersState(
        decentralizedSynchronizer
      )
      .getOrElse {
        throw Status.FAILED_PRECONDITION
          .withDescription("No domain state topology found")
          .asRuntimeException()
      }
    timestamp = CantonTimestamp.tryFromInstant(participantParamsState.exportTimestamp)
    _ = logger.info(s"Taking domain migration snapshot at $timestamp")
    (genesisState, (acsSnapshot, acsTimestamp)) <- (
      exportGenesisState(decentralizedSynchronizer, participantParamsState, timestamp),
      acsExporter
        .safeExportParticipantPartiesAcsFromPausedDomain(decentralizedSynchronizer)
        .leftMap(failure =>
          Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException()
        )
        .rethrowT,
    ).tupled
    dars <- darExporter.exportAllDars()
  } yield {
    val result = DomainDataSnapshot(
      Some(genesisState),
      acsSnapshot,
      acsTimestamp,
      dars,
      synchronizerWasPaused = true,
    )
    logger.info(show"Finished generating $result")
    result
  }
}
