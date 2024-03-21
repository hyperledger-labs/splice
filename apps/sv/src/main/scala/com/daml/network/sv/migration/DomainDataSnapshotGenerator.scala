package com.daml.network.sv.migration

import cats.syntax.traverse.*
import com.daml.network.environment.{ParticipantAdminConnection, SequencerAdminConnection}
import com.daml.network.migration.{AcsExporter, DarExporter}
import com.daml.network.sv.store.SvSvcStore
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
    svcStore: SvSvcStore,
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
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    cantonTimestamp = CantonTimestamp.tryFromInstant(timestamp)
    topologySnapshot <- sequencerAdminConnection.traverse(_.getGenesisState(cantonTimestamp))
    vettedPackages <- sequencerAdminConnection.traverse(
      _.getVettedPackagesSnapshot(globalDomain, cantonTimestamp)
    )
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        globalDomain,
        timestamp,
        partyId.fold(Seq(svcStore.key.svcParty, svcStore.key.svParty))(Seq(_))*
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    vettedPackages,
    acsSnapshot,
    dars,
  )

  def getDomainMigrationSnapshot(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    domainParamsStateTopology <- participantAdminConnection.getDomainParametersState(globalDomain)
    timestamp = CantonTimestamp.tryFromInstant(domainParamsStateTopology.base.validFrom)
    genesisState <- sequencerAdminConnection.traverse(
      _.getGenesisState(timestamp)
    )
    vettedPackages <- sequencerAdminConnection.traverse(
      _.getVettedPackagesSnapshot(globalDomain, timestamp)
    )
    acsSnapshot <- acsExporter
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
    dars,
  )
}
