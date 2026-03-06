// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.canton.SynchronizerAlias
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.LogicalSynchronizerUpgradeSchedule
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeAnnouncementTrigger.LsuAnnouncementTask
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class LogicalSynchronizerUpgradeAnnouncementTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: ParticipantAdminConnection,
    syncAlias: SynchronizerAlias,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuAnnouncementTask] {

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuAnnouncementTask]] = {
    store.getDsoRules().flatMap {
      _.payload.config.nextScheduledLogicalSynchronizerUpgrade.toScala match {
        case Some(schedule) if !now.toInstant.isBefore(schedule.topologyFreezeTime) =>
          for {
            psid <- connection.getPhysicalSynchronizerId(syncAlias)
            existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
              psid.logical,
              TimeQuery.HeadState,
              TopologyTransactionType.AuthorizedState,
            )
          } yield {
            val scheduleSerial =
              NonNegativeInt.tryCreate(schedule.newPhysicalSynchronizerSerial.toInt)
            if (psid.serial >= scheduleSerial) { Seq.empty }
            else {
              existingAnnouncement match {
                case Some(announcement)
                    if announcement.mapping.successorSynchronizerId.serial == scheduleSerial =>
                  Seq.empty
                case _ =>
                  Seq(LsuAnnouncementTask(psid.logical, schedule))
              }
            }
          }
        case _ => Future.successful(Seq.empty)
      }
    }
  }

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val schedule = task.work.schedule
    val upgradeTime = CantonTimestamp.assertFromInstant(schedule.upgradeTime)
    for {
      _ <- connection.ensureLsuAnnouncement(
        task.work.synchronizerId,
        upgradeTime,
        task.work.serial,
        ProtocolVersion.tryCreate(schedule.newPhysicalSynchronizerProtocolVersion),
      )
    } yield TaskSuccess(
      s"Published LSU announcement for upgrade at $upgradeTime with psid ${schedule.newPhysicalSynchronizerSerial}"
    )
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
        task.work.synchronizerId,
        TimeQuery.HeadState,
        TopologyTransactionType.AuthorizedState,
      )
    } yield existingAnnouncement.exists(
      _.mapping.successorSynchronizerId.serial == task.work.serial
    )
  }

}

object LogicalSynchronizerUpgradeAnnouncementTrigger {

  case class LsuAnnouncementTask(
      synchronizerId: SynchronizerId,
      schedule: LogicalSynchronizerUpgradeSchedule,
  ) extends PrettyPrinting {
    val serial = NonNegativeInt.tryCreate(schedule.newPhysicalSynchronizerSerial.toInt)
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("toologyFreezeTime", _.schedule.topologyFreezeTime.toString.unquoted),
      param("upgradeTime", _.schedule.upgradeTime.toString.unquoted),
      param("newPhysicalSynchronizerSerial", _.schedule.newPhysicalSynchronizerSerial),
      param(
        "newPhysicalSynchronizerProtocolVersion",
        _.schedule.newPhysicalSynchronizerProtocolVersion.unquoted,
      ),
    )
  }

}
