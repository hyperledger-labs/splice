// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.transaction.LsuAnnouncement
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.SynchronizerNode
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSyncUpgradeTransferTrafficTrigger.TrafficTransferTask

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future}

class LogicalSyncUpgradeTransferTrafficTrigger(
    baseContext: TriggerContext,
    currentSynchronizerNode: SynchronizerNode,
    successorSynchronizerNode: SynchronizerNode,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[TrafficTransferTask] {

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  private val transferred = new AtomicBoolean(false)

  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[TrafficTransferTask]] = {
    if (transferred.get()) {
      Future.successful(Seq.empty)
    } else {
      for {
        physicalSynchronizerId <- currentSynchronizerNode.sequencerAdminConnection
          .getPhysicalSynchronizerId()
        announcements <- announcements(now, physicalSynchronizerId)
        successorInitialized <- isSuccessorInitialized()
      } yield {
        announcements
          .filter(_ => successorInitialized)
          .map(result => TrafficTransferTask(result.mapping))
      }
    }
  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[TrafficTransferTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      trafficState <-
        currentSynchronizerNode.sequencerAdminConnection.getLsuTrafficControlState()
      _ = logger.info(
        "Transferring LSU traffic control state from current to successor sequencer"
      )
      _ <- successorSynchronizerNode.sequencerAdminConnection.setLsuTrafficControlState(
        trafficState
      )
      _ = {
        logger.info("Successfully transferred LSU traffic control state to successor sequencer")
        transferred.set(true)
      }
    } yield TaskSuccess(
      "Transferred LSU traffic control state to successor sequencer"
    )
  }

  protected def isStaleTask(task: ScheduledTaskTrigger.ReadyTask[TrafficTransferTask])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(transferred.get())

  private def isSuccessorInitialized()(implicit
      traceContext: TraceContext
  ): Future[Boolean] = {
    successorSynchronizerNode.sequencerAdminConnection.getStatus.map {
      case NodeStatus.Failure(msg) =>
        logger.warn(s"Failed to get successor sequencer status: $msg")
        false
      case NodeStatus.NotInitialized(_, _) => false
      case NodeStatus.Success(_) => true
    }
  }

  private def announcements(now: CantonTimestamp, synchronizerId: PhysicalSynchronizerId)(implicit
      tc: TraceContext
  ) = {
    currentSynchronizerNode.sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.mapping.upgradeTime
          .isAfter(now) && announcement.mapping.successorSynchronizerId != synchronizerId
      })
  }
}

object LogicalSyncUpgradeTransferTrafficTrigger {
  case class TrafficTransferTask(announcement: LsuAnnouncement) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("announcement", _.announcement)
    )
  }
}
