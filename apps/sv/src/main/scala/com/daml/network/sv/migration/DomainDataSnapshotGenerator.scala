package com.daml.network.sv.migration

import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.migration.{AcsExporter, DarExporter}
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
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
    topologySnapshot <- getTopologySnapshot(globalDomain, timestamp)
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        globalDomain,
        timestamp,
        partyId.fold(Seq(svcStore.key.svcParty, svcStore.key.svParty))(Seq(_)): _*
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars,
  )

  def getDomainMigrationSnapshot()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    domainParamsStateTopology <- participantAdminConnection.getDomainParametersState(globalDomain)
    topologySnapshot <- getTopologySnapshot(globalDomain, domainParamsStateTopology.base.validFrom)
    acsSnapshot <- acsExporter
      .safeExportParticipantPartiesAcsFromPausedDomain(
        globalDomain
      )
      .leftMap(failure =>
        Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException()
      )
      .rethrowT
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars,
  )

  private def getTopologySnapshot(domainId: DomainId, timestamp: Instant)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ) = {
    val snapshotTimestamp = CantonTimestamp.tryFromInstant(timestamp)
    for {
      topologySnapshotWithProposals <- participantAdminConnection
        .getTopologySnapshot(
          domainId,
          snapshotTimestamp,
        )
      topologySnapshot = topologySnapshotWithProposals.filter(_.transaction.isProposal == false)
    } yield { topologySnapshot }
  }

}
