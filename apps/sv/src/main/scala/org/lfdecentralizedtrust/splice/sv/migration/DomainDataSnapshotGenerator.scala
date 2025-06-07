// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.migration

import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.migration.{
  AcsExporter,
  DarExporter,
  SynchronizerParametersStateTopologyConnection,
}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
    // TODO(DACH-NY/canton-network-node#11099) Read everything from the participant connection once the genesis state API is available there.
    sequencerAdminConnection: Option[SequencerAdminConnection],
    dsoStore: SvDsoStore,
    acsExporter: AcsExporter,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  private val darExporter = new DarExporter(participantAdminConnection)

  private val domainStateTopology = new SynchronizerParametersStateTopologyConnection(
    participantAdminConnection
  )

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
    topologySnapshot <- sequencerAdminConnection.traverse(_.getGenesisState(cantonTimestamp))
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        decentralizedSynchronizer,
        timestamp,
        force,
        partyId.fold(Seq(dsoStore.key.dsoParty, dsoStore.key.svParty))(Seq(_))*
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    acsTimestamp = timestamp,
    dars,
  )

  def getDomainMigrationSnapshot(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
    domainParamsStateTopology <- domainStateTopology
      .firstAuthorizedStateForTheLatestSynchronizerParametersState(
        decentralizedSynchronizer
      )
      .getOrElse {
        throw Status.FAILED_PRECONDITION
          .withDescription("No domain state topology found")
          .asRuntimeException()
      }
    timestamp = CantonTimestamp.tryFromInstant(domainParamsStateTopology.base.validFrom)
    _ = logger.info(s"Taking domain migration snapshot at $timestamp")
    genesisState <- sequencerAdminConnection.traverse { sequencerConnection =>
      for {
        // The sequencer can lag behind and queries will not fail but silently return an earlier state, so synchronize on it.
        // See https://github.com/DACH-NY/canton/issues/20658
        _ <- retryProvider.waitUntil(
          RetryFor.Automation,
          "sequencer_paused_domain",
          "sequencer observes SynchronizerParametersState that pauses domain",
          for {
            sequencerDomainParameters <- sequencerConnection.getSynchronizerParametersState(
              decentralizedSynchronizer
            )
          } yield {
            if (sequencerDomainParameters.base.serial < domainParamsStateTopology.base.serial) {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  s"Sequencer has not yet observed SynchronizerParametersState with serial >= ${domainParamsStateTopology.base.serial}, current serial: ${sequencerDomainParameters.base.serial}"
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
          sequencerDomainParamsPaused.base.validFrom
        )
        _ = if (sequencerPausedTimestamp != timestamp) {
          throw Status.INTERNAL
            .withDescription(
              s"Participant sees domain as paused at $timestamp while sequencer sees domain as paused at ${sequencerPausedTimestamp}"
            )
            .asRuntimeException()
        }
        genesisState <- sequencerConnection.getGenesisState(timestamp)
      } yield genesisState
    }
    (acsSnapshot, acsTimestamp) <- acsExporter
      .safeExportParticipantPartiesAcsFromPausedDomain(decentralizedSynchronizer)
      .leftMap(failure =>
        Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException()
      )
      .rethrowT
    dars <- darExporter.exportAllDars()
  } yield {
    val result = DomainDataSnapshot(
      genesisState,
      acsSnapshot,
      acsTimestamp,
      dars,
    )
    logger.info(show"Finished generating $result")
    result
  }
}
