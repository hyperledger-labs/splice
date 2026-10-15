// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.lsu

import cats.implicits.showInterpolator
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
import org.lfdecentralizedtrust.splice.environment.{StatusAdminConnection, SynchronizerNode}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTriggerBase.LsuTransferTask

import java.nio.file.Path
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

/** Initializes the successor synchronizer node from the current one once an LSU has been
  * announced, which publishes the sequencer successor that members follow.
  *
  * Subclasses add the steps their own node needs around that.
  */
abstract class LsuTransferTriggerBase[T <: SynchronizerNode & LsuSynchronizerNode](
    baseContext: TriggerContext,
    protected val localSynchronizerNodes: LocalSynchronizerNodes[T],
    successorSynchronizerNode: T,
    dumpPath: Path,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuTransferTask] {

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  protected val currentSynchronizerNode: T = localSynchronizerNodes.current

  protected val exporter =
    new LsuStateExporter(
      dumpPath,
      currentSynchronizerNode.sequencerAdminConnection,
      currentSynchronizerNode.mediatorAdminConnection,
      loggerFactory,
    )

  protected val initializer =
    new LsuNodeInitializer(
      localSynchronizerNodes,
      successorSynchronizerNode,
      loggerFactory,
      context.retryProvider,
    )

  /** Upgrade work left to do once the successor's nodes are initialized. */
  @nowarn("cat=unused-params")
  protected def additionalWorkPending(
      now: CantonTimestamp,
      currentPsid: PhysicalSynchronizerId,
      announcements: Seq[LsuAnnouncement],
  )(implicit tc: TraceContext): Future[Boolean] = Future.successful(false)

  /** Runs before the successor is initialized from the current node's state. */
  @nowarn("cat=unused-params")
  protected def beforeInitialize(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[Unit] = Future.unit

  /** Runs once the successor is initialized. */
  @nowarn("cat=unused-params")
  protected def afterInitialize(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[Unit] = Future.unit

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuTransferTask]] =
    for {
      physicalSynchronizerId <- currentSynchronizerNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      announcements <- announcements(now, physicalSynchronizerId)
      sequencerNotInitialized <- isNodeNotInitialized(
        successorSynchronizerNode.sequencerAdminConnection,
        "sequencer",
      )
      mediatorNotInitialized <- isNodeNotInitialized(
        successorSynchronizerNode.mediatorAdminConnection,
        "mediator",
      )
      workPending <- additionalWorkPending(
        now,
        physicalSynchronizerId,
        announcements.map(_.mapping),
      )
    } yield announcements
      .filter(_ => sequencerNotInitialized || mediatorNotInitialized || workPending)
      .map(result => LsuTransferTask(result.mapping))

  override protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(
      implicit tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      _ <- beforeInitialize(task)
      state <- exporter.exportLSUState(topologyExportTime = None)
      parameters <- initializer.initializeSynchronizer(
        state,
        task.work.announcement.successorSynchronizerId.tryAsPhysical,
        task.readyAt,
        Some(task.work.announcement.upgradeTime),
        ignorePsidCheck = false,
      )
      _ <- afterInitialize(task)
    } yield TaskSuccess(
      show"Initialized successor synchronizer with parameters $parameters"
    )

  override protected def isStaleTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  private def isNodeNotInitialized[C <: StatusAdminConnection](
      adminConnection: C,
      nodeName: String,
  )(implicit tc: TraceContext): Future[Boolean] =
    adminConnection.getStatus(tc).map {
      case NodeStatus.Failure(msg) =>
        logger.error(s"Failed to get successor $nodeName status: $msg")
        false
      case NodeStatus.NotInitialized(_, _, _) => true
      case NodeStatus.Success(_) => false
    }

  private def announcements(now: CantonTimestamp, synchronizerId: PhysicalSynchronizerId)(implicit
      tc: TraceContext
  ) =
    currentSynchronizerNode.sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.base.validFrom
          .isBefore(
            now.toInstant
          ) && announcement.mapping.successorSynchronizerId.serial > synchronizerId.serial
      })
}

object LsuTransferTriggerBase {
  final case class LsuTransferTask(announcement: LsuAnnouncement) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("announcement", _.announcement)
    )
  }
}
