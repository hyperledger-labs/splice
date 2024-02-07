package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.sv.{DomainMigrationDump, LocalDomainNode}
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.BackupDump
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.transaction.DomainParametersStateX
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*
import scala.jdk.OptionConverters.*

final class DomainUpgradeTrigger(
    override protected val context: TriggerContext,
    domainAlias: DomainAlias,
    localDomainNode: LocalDomainNode,
    svcStore: SvSvcStore,
    participantAdminConnection: ParticipantAdminConnection,
    dumpPath: Path,
    migrationId: Option[Long],
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[DomainUpgradeTrigger.Task] {
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[DomainUpgradeTrigger.Task]] = {
    val run = for {
      svcRules <- OptionT(svcStore.lookupSvcRules())
      schedule <- OptionT.fromOption[Future](
        svcRules.contract.payload.config.nextScheduledDomainUpgrade.toScala
      )
      domainTime <- OptionT.liftF(
        participantAdminConnection.getDomainTime(svcRules.domain, timeouts.default)
      )
      _ <-
        // check if the domain time is after the scheduled time for migration.
        // and the migrationId configured for this SV is not the same as that of the current scheduled migration
        if (
          domainTime.timestamp.toInstant.isAfter(schedule.time) && migrationId.forall(
            _ != schedule.migrationId
          ) && !BackupDump.fileExists(dumpPath)
        )
          OptionT.pure[Future](())
        else OptionT.none[Future, Unit]
    } yield schedule
    run.value.map(_.map(schedule => DomainUpgradeTrigger.Task(schedule.migrationId)).toList)
  }

  override protected def completeTask(task: DomainUpgradeTrigger.ReadyTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    if (!BackupDump.fileExists(dumpPath)) {
      // pause domain and trigger export
      for {
        globalDomainId <- svcStore.getSvcRules().map(_.domain)
        domainParamsTopologyResult <- ensureDomainIsPaused(globalDomainId)
        // TODO(#8761) wait until it's safe, based on params described in the design
        _ <- waitForMediatorAndParticipantResponseTime(globalDomainId, domainParamsTopologyResult)
        _ <- exportMigrationDump(task.work.migrationId, domainParamsTopologyResult.base.validFrom)
      } yield TaskSuccess(show"Triggered domain pause and migration dump export for ${task.work}")
    } else
      Future.successful(TaskSuccess(show"migration dump already exists. skipping ${task.work}"))
  }

  override protected def isStaleTask(
      task: DomainUpgradeTrigger.ReadyTask
  )(implicit tc: TraceContext): Future[Boolean] = Future.successful(false)

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

  private def exportMigrationDump(migrationId: Long, domainPausedAt: Instant)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    DomainMigrationDump
      .getDomainMigrationDump(
        domainAlias,
        participantAdminConnection,
        localDomainNode,
        loggerFactory,
        svcStore,
        context.clock,
        migrationId,
        domainPausedAt,
      )
      .map { dump =>
        val path = BackupDump.writeToPath(
          dumpPath,
          dump.toJson.noSpaces,
        )
        logger.info(s"Wrote domain migration dump at path $path")
      }
  }

  private def waitForMediatorAndParticipantResponseTime(
      globalDomainId: DomainId,
      domainParamsTopologyResult: TopologyResult[DomainParametersStateX],
  )(implicit
      tc: TraceContext
  ) = {
    val domainDynamicParams = domainParamsTopologyResult.mapping.parameters
    val duration = domainDynamicParams.mediatorReactionTimeout.duration
      .plus(domainDynamicParams.participantResponseTimeout.duration)
      .plus(5.seconds.toJava) // a small buffer to account for network latency

    val readyForDumpAfter = domainParamsTopologyResult.base.validFrom.plus(duration)
    for {
      _ <- context.retryProvider
        .waitUntil(
          RetryFor.WaitingOnInitDependency,
          "wait for mediator and participant response time after domain is paused",
          participantAdminConnection
            .getDomainTime(globalDomainId, timeouts.default)
            .map(domainTimeResponse =>
              if (domainTimeResponse.timestamp.toInstant.isBefore(readyForDumpAfter)) {
                throw Status.FAILED_PRECONDITION
                  .withDescription(
                    s"we should wait until $readyForDumpAfter to let all participants catch up with the paused domain state"
                  )
                  .asRuntimeException()
              }
            ),
          logger,
        )
    } yield ()
  }
}

object DomainUpgradeTrigger {
  case class Task(migrationId: Long) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("migrationId", _.migrationId))
  }
  private type ReadyTask = ScheduledTaskTrigger.ReadyTask[Task]
}
