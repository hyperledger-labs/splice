// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.SynchronizerAlias
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType
import org.lfdecentralizedtrust.splice.sv.config.ScheduledLsuConfig
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeAnnouncementTrigger.LsuAnnouncementTask

import scala.concurrent.{ExecutionContext, Future}

class LogicalSynchronizerUpgradeAnnouncementTrigger(
    val context: TriggerContext,
    lsuAnnouncementConfig: Option[ScheduledLsuConfig],
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
    lsuAnnouncementConfig match {
      case Some(config) if !now.toInstant.isBefore(config.freezeTime) =>
        for {
          psid <- connection.getPhysicalSynchronizerId(syncAlias)
          existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
            psid.logical,
            TimeQuery.HeadState,
            TopologyTransactionType.AuthorizedState,
          )
        } yield {
          if (psid.serial <= config.psid) { Seq.empty }
          else
            existingAnnouncement match {
              case Some(announcement)
                  if announcement.mapping.successorSynchronizerId.serial == config.psid =>
                Seq.empty
              case _ =>
                Seq(LsuAnnouncementTask(psid.logical, config))
            }
        }
      case _ => Future.successful(Seq.empty)
    }
  }

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val config = task.work.config
    val upgradeTime = CantonTimestamp.assertFromInstant(config.upgradeTime)
    for {
      _ <- connection.ensureLsuAnnouncement(
        task.work.synchronizerId,
        upgradeTime,
        config.psid,
        config.protocolVersion,
      )
    } yield TaskSuccess(
      s"Published LSU announcement for upgrade at $upgradeTime with psid ${config.psid}"
    )
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[LsuAnnouncementTask]
  )(implicit tc: TraceContext): Future[Boolean] = {
    val config = task.work.config
    for {
      existingAnnouncement <- connection.lookupSynchronizerLsuAnnouncement(
        task.work.synchronizerId,
        TimeQuery.HeadState,
        TopologyTransactionType.AuthorizedState,
      )
    } yield existingAnnouncement.exists(
      _.mapping.successorSynchronizerId.serial == config.psid
    )
  }

}

object LogicalSynchronizerUpgradeAnnouncementTrigger {

  case class LsuAnnouncementTask(synchronizerId: SynchronizerId, config: ScheduledLsuConfig)
      extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("freezeTime", _.config.freezeTime.toString.unquoted),
      param("upgradeTime", _.config.upgradeTime.toString.unquoted),
      param("psid", _.config.psid),
      param("protocolVersion", _.config.protocolVersion),
    )
  }

}
