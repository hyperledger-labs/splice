package com.daml.network.sv.migration

import cats.data.OptionT
import com.daml.network.automation.TriggerContext
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.migration.{AcsExporter, DomainMigrationTrigger}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.transaction.DomainParametersStateX
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

final class GlobalDomainMigrationTrigger(
    override protected val currentMigrationId: Long,
    override protected val context: TriggerContext,
    domainAlias: DomainAlias,
    localDomainNode: LocalDomainNode,
    svcStore: SvSvcStore,
    protected val participantAdminConnection: ParticipantAdminConnection,
    protected val dumpPath: Path,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DomainMigrationTrigger[DomainMigrationDump] {

  val domainDataSnapshotGenerator = new DomainDataSnapshotGenerator(
    participantAdminConnection,
    svcStore,
    new AcsExporter(participantAdminConnection, context.retryProvider, loggerFactory),
  )

  override protected def getSchedule(implicit
      tc: TraceContext
  ): OptionT[Future, DomainMigrationTrigger.ScheduledMigration] = {
    for {
      svcRules <- OptionT(svcStore.lookupSvcRules())
      schedule <- OptionT.fromOption[Future](
        svcRules.contract.payload.config.nextScheduledDomainUpgrade.toScala
      )
    } yield DomainMigrationTrigger.ScheduledMigration(schedule.time, schedule.migrationId)
  }

  override protected def getDomainId()(implicit tc: TraceContext): Future[DomainId] = {
    svcStore.getSvcRules().map(_.domain)
  }

  override protected def existingDumpFileMigrationId(dump: DomainMigrationDump): Long =
    dump.migrationId

  override protected def generateDump(task: DomainMigrationTrigger.Task)(implicit
      tc: TraceContext
  ): Future[DomainMigrationDump] = for {
    _ <- ensureDomainIsPaused(task.domainId)
    dump <- exportMigrationDump(task.migrationId)
  } yield dump

  private def ensureDomainIsPaused(
      globalDomainId: DomainId
  )(implicit tc: TraceContext): Future[TopologyResult[DomainParametersStateX]] = for {
    id <- participantAdminConnection.getId()
    domainParamsTopologyResult <- participantAdminConnection
      .ensureDomainParameters(
        globalDomainId,
        _.tryUpdate(maxRatePerParticipant = NonNegativeInt.zero),
        signedBy = id.namespace.fingerprint,
      )
  } yield domainParamsTopologyResult

  private def exportMigrationDump(migrationId: Long)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainMigrationDump] = {
    DomainMigrationDump
      .getDomainMigrationDump(
        domainAlias,
        participantAdminConnection,
        localDomainNode,
        loggerFactory,
        svcStore,
        migrationId,
        domainDataSnapshotGenerator,
      )
  }

}
