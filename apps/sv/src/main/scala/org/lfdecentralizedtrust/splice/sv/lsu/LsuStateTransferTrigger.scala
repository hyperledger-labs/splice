// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.showInterpolator
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.admin.api.client.data.SequencerHealthStatus.implicitPrettyString
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
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.lsu.LsuStateTransferTrigger.LsuTransferTask

import scala.concurrent.{ExecutionContext, Future}

class LsuStateTransferTrigger(
    baseContext: TriggerContext,
    currentSynchronizerNode: SynchronizerNode,
    successorSynchronizerNode: LocalSynchronizerNode,
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
      announcements <- announcements(now, physicalSynchronizerId)
    } yield {
      announcements
        .map(result => LsuTransferTask(result.mapping))
    }

  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.info(s"Running LSU state transfer for $task")
    successorSynchronizerNode.mediatorAdminConnection.getStatus.flatMap {
      case NodeStatus.Failure(msg) =>
        val message = s"Failed to get successor status, will not transfer state: $msg"
        logger.error(message)
        Future.successful(TaskFailed(message))
      case NodeStatus.NotInitialized(_, _) =>
        for {
          state <- exporter.exportLSUState(task.work.announcement.upgradeTime)
          _ = logger.info("Initializing sequencer and mediators from the data of the old nodes")
          _ <- newMediatorInitializer.initializeFromDump(state.nodeIdentities.mediator)
          _ <- newSequencerIntializer.initializeFromDump(state.nodeIdentities.sequencer)
          _ <- context.retryProvider.ensureThat(
            RetryFor.InitializingClientCalls,
            "init_sequencer_lsu",
            "Initialize sequencer from the state of the predecessor",
            successorSynchronizerNode.sequencerAdminConnection.getStatus.map {
              case NodeStatus.Failure(msg) => Left(msg)
              case NodeStatus.NotInitialized(_, _) => Left("Not initialized")
              case NodeStatus.Success(_) =>
                logger.info("Sequencer is already initialized")
                Right(())
            },
            (_: String) => {
              logger.info(
                show"Initializing sequencer from predecessor with ${successorSynchronizerNode.staticSynchronizerParameters}"
              )
              successorSynchronizerNode.sequencerAdminConnection.initializeFromPredecessor(
                state.synchronizerState,
                successorSynchronizerNode.staticSynchronizerParameters,
              )
            },
            logger,
          )
          psid <- successorSynchronizerNode.sequencerAdminConnection.getPhysicalSynchronizerId()
          _ <- context.retryProvider.ensureThat(
            RetryFor.InitializingClientCalls,
            "init_mediator_lsu",
            "Initialize mediator after the LSU",
            successorSynchronizerNode.mediatorAdminConnection.getStatus.map {
              case NodeStatus.Failure(msg) => Left(msg)
              case NodeStatus.NotInitialized(_, _) => Left("Not initialized")
              case NodeStatus.Success(_) =>
                logger.info("Mediator is already initialized")
                Right(())
            },
            (_: String) => {
              logger.info(show"Initializing mediator")
              successorSynchronizerNode.mediatorAdminConnection.initialize(
                psid,
                successorSynchronizerNode.sequencerConnection,
                successorSynchronizerNode.mediatorSequencerAmplification.toInternal,
              )
            },
            logger,
          )
        } yield {
          TaskSuccess(
            show"Initialized new synchronizer with parameters ${successorSynchronizerNode.staticSynchronizerParameters}"
          )
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

  private def announcements(now: CantonTimestamp, synchronizerId: PhysicalSynchronizerId)(implicit
      tc: TraceContext
  ) = {
    currentSynchronizerNode.sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.base.validFrom
          .isBefore(now.toInstant) && announcement.mapping.successorSynchronizerId != synchronizerId
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
