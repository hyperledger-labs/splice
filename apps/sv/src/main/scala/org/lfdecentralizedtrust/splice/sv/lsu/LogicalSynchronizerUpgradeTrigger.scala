// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.showInterpolator
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.admin.api.client.data.SequencerHealthStatus.implicitPrettyString
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.networking
import com.digitalasset.canton.topology.transaction.{GrpcConnection, LsuAnnouncement}
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
  TriggerEnabledSynchronization,
}
import org.lfdecentralizedtrust.splice.environment.{RetryFor, StatusAdminConnection}
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.{LocalSynchronizerNode, SynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeTrigger.LsuTransferTask

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

class LogicalSynchronizerUpgradeTrigger(
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

  private val successorConnection = {
    networking.Endpoint
      .fromUris(
        NonEmpty(
          Seq,
          URI.create(
            successorSynchronizerNode.sequencerExternalPublicUrl
          ),
        )
      )
      .map { case (validatedEndpoints, useTls) =>
        GrpcConnection(
          validatedEndpoints,
          useTls,
          None,
        )
      }
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Failed to create gRPC connection for ${successorSynchronizerNode.sequencerExternalPublicUrl}"
          )
          .asRuntimeException()
      )
  }

  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[LsuTransferTask]] = {
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
      sequencerId <- currentSynchronizerNode.sequencerAdminConnection.getSequencerId
      successorExists <- currentSynchronizerNode.sequencerAdminConnection
        .lookupSequencerSuccessors(physicalSynchronizerId.logical, sequencerId)
        .map(_.exists(_.mapping.connection == successorConnection))
    } yield {
      announcements
        .filter { _ =>
          sequencerNotInitialized || mediatorNotInitialized || !successorExists
        }
        .map(result => LsuTransferTask(result.mapping))
    }

  }

  private def isNodeNotInitialized[T <: StatusAdminConnection](
      adminConnection: T,
      nodeName: String,
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    adminConnection.getStatus(tc).map {
      case NodeStatus.Failure(msg) =>
        logger.error(s"Failed to get successor $nodeName status: $msg")
        false
      case NodeStatus.NotInitialized(_, _) => true
      case NodeStatus.Success(_) => false
    }
  }

  protected def completeTask(task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask])(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.info(s"Running LSU state transfer for $task")
    (for {
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
            successorSynchronizerNode.internalSequencerConnection,
            successorSynchronizerNode.mediatorSequencerAmplification.toInternal,
          )
        },
        logger,
      )
    } yield {
      TaskSuccess(
        show"Initialized new synchronizer with parameters ${successorSynchronizerNode.staticSynchronizerParameters}"
      )
    }).flatMap(result => {
      for {
        psid <- successorSynchronizerNode.sequencerAdminConnection.getPhysicalSynchronizerId()
        sequencerId <- currentSynchronizerNode.sequencerAdminConnection.getSequencerId
        _ <-
          currentSynchronizerNode.sequencerAdminConnection.ensureSequencerSuccessor(
            psid.logical,
            sequencerId = sequencerId,
            connection = successorConnection,
          )
      } yield { result }
    })
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

object LogicalSynchronizerUpgradeTrigger {
  case class LsuTransferTask(announcement: LsuAnnouncement) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("announcement", _.announcement)
    )
  }
}
