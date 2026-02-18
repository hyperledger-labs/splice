// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.showInterpolator
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.transaction.LsuAnnouncement
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskFailed,
  TaskOutcome,
  TaskStale,
  TaskSuccess,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.lsu.LsuStateTransferTrigger.LsuTransferTask
import org.lfdecentralizedtrust.splice.sv.SynchronizerNode

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class LsuStateTransferTrigger(
    baseContext: TriggerContext,
    currentSynchronizerNode: SynchronizerNode,
    successorSynchronizerNode: SynchronizerNode,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuTransferTask] {

  private val exporter =
    new LsuStateExporter(
      currentSynchronizerNode.sequencerAdminConnection,
      currentSynchronizerNode.mediatorAdminConnection,
      loggerFactory,
    )

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)
  private val newSequencerIntializer = new NodeInitializer(
    successorSynchronizerNode.sequencerAdminConnection,
    context.retryProvider,
    loggerFactory,
  )
  private val newMediatorInitializer = new NodeInitializer(
    successorSynchronizerNode.mediatorAdminConnection,
    context.retryProvider,
    loggerFactory,
  )

  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuTransferTask]] = {
    for {
      physicalSynchronizerId <- currentSynchronizerNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      announcements <- announcements(physicalSynchronizerId)
    } yield {
      announcements
        .map(result => LsuTransferTask(result.mapping))
    }

  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    successorSynchronizerNode.sequencerAdminConnection.getStatus.flatMap {
      case NodeStatus.Failure(msg) =>
        val message = s"Failed to get successor status, will not transfer state: $msg"
        logger.error(message)
        Future.successful(TaskFailed(message))
      case NodeStatus.NotInitialized(_, _) =>
        for {
          state <- exporter.exportLSUState(task.work.announcement.upgradeTime)
          _ <- newMediatorInitializer.initializeFromDumpAndWait(state.nodeIdentities.sequencer)
          _ <- newSequencerIntializer.initializeFromDumpAndWait(state.nodeIdentities.sequencer)
          currentStaticParams <- currentSynchronizerNode.sequencerAdminConnection.getStaticParams()
          parameters = currentStaticParams.toInternal.copy(
            serial = currentStaticParams.serial + NonNegativeInt.one
          )
          _ <- successorSynchronizerNode.sequencerAdminConnection.initializeFromPredecessor(
            state.synchronizerState,
            // TODO(#564) - configure the serial increment in the sv app to allow rollforward
            // TODO(#564) - support different protocol versions
            parameters,
          )
        } yield {
          TaskSuccess(show"Initialized new synchronizer with parameters $parameters")
        }
      case NodeStatus.Success(status) =>
        logger.info(
          s"Successor sequencer is already initialized: ${status.synchronizerId} -> ${status.protocolVersion}"
        )
        Future.successful(TaskStale)
    }
  }

  protected def isStaleTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[Boolean] = Future.successful(false)

  private def announcements(synchronizerId: PhysicalSynchronizerId)(implicit tc: TraceContext) = {
    currentSynchronizerNode.sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.base.validFrom
          .isAfter(Instant.now()) && announcement.mapping.successorSynchronizerId != synchronizerId
      })
  }
}

object LsuStateTransferTrigger {
  case class LsuTransferTask(announcement: LsuAnnouncement) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("announcement", _.announcement)
    )
  }
}
