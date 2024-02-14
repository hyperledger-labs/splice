package com.daml.network.sv.migration

import cats.implicits.toTraverseOps
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.sv.migration.DomainDataSnapshot.Dar
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
    svcStore: SvSvcStore,
    acsExporter: AcsExporter,
) {

  def getDomainDataSnapshot(
      timestamp: Instant
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
        svcStore.key.svParty,
        svcStore.key.svcParty,
      )
    dars <- getDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars.flatten,
  )

  def getDomainMigrationSnapshot()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    globalDomain <- svcStore.getSvcRules().map(_.domain)
    domainParamsStateTopology <- participantAdminConnection.getDomainParametersState(globalDomain)
    topologySnapshot <- getTopologySnapshot(globalDomain, domainParamsStateTopology.base.validFrom)
    acsSnapshot <- acsExporter
      .safeExportAcsFromPausedDomain(
        globalDomain,
        svcStore.key.svParty,
        svcStore.key.svcParty,
      )
      .fold(
        failure =>
          throw Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException(),
        identity,
      )
    dars <- getDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    dars.flatten,
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

  private def getDars()(implicit tc: TraceContext, ec: ExecutionContext) = {
    for {
      darDescriptions <- participantAdminConnection.listDars()
      dars <- darDescriptions.traverse { dar =>
        val hash = Hash.tryFromHexString(dar.hash)
        participantAdminConnection.lookupDar(hash).map(_.map(Dar(hash, _)))
      }
    } yield dars
  }

}
