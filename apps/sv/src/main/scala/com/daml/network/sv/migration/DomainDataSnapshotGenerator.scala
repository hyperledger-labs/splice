package com.daml.network.sv.migration

import cats.syntax.traverse.*
import com.daml.network.environment.{ParticipantAdminConnection, SequencerAdminConnection}
import com.daml.network.migration.{AcsExporter, DarExporter}
import com.daml.network.sv.store.SvDsoStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
    // TODO(#11099) Read everything from the participant connection once the genesis state API is available there.
    sequencerAdminConnection: Option[SequencerAdminConnection],
    dsoStore: SvDsoStore,
    acsExporter: AcsExporter,
) {

  private val darExporter = new DarExporter(participantAdminConnection)

  def getDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId],
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- dsoStore.getDsoRules().map(_.domain)
    cantonTimestamp = CantonTimestamp.tryFromInstant(timestamp)
    topologySnapshot <- sequencerAdminConnection.traverse(_.getGenesisState(cantonTimestamp))
    vettedPackages <- sequencerAdminConnection.traverse(
      _.getVettedPackagesSnapshot(globalDomain, cantonTimestamp)
    )
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        globalDomain,
        timestamp,
        partyId.fold(Seq(dsoStore.key.dsoParty, dsoStore.key.svParty))(Seq(_))*
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    vettedPackages,
    acsSnapshot,
    acsTimestamp = timestamp,
    dars,
    domainWasPaused = false,
  )

  def getDomainMigrationSnapshot(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- dsoStore.getDsoRules().map(_.domain)
    domainParamsStateTopology <- participantAdminConnection.getDomainParametersState(globalDomain)
    timestamp = CantonTimestamp.tryFromInstant(domainParamsStateTopology.base.validFrom)
    genesisState <- sequencerAdminConnection.traverse(
      _.getGenesisState(timestamp)
    )
    vettedPackages <- sequencerAdminConnection.traverse(
      _.getVettedPackagesSnapshot(globalDomain, timestamp)
    )
    (acsSnapshot, acsTimestamp) <- acsExporter
      .safeExportParticipantPartiesAcsFromPausedDomain(globalDomain)
      .leftMap(failure =>
        Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException()
      )
      .rethrowT
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    genesisState,
    vettedPackages,
    acsSnapshot,
    acsTimestamp,
    dars,
    domainWasPaused = true,
  )
}
