// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.{catsSyntaxOptionId, showInterpolator, toTraverseOps}
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.transaction.LsuAnnouncement
import com.digitalasset.canton.topology.PhysicalSynchronizerId
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
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  StatusAdminConnection,
}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeTrigger.LsuTransferTask
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState.OnboardedImmediately
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

class LogicalSynchronizerUpgradeTrigger(
    baseContext: TriggerContext,
    reconciler: SynchronizerNodeReconciler,
    localSynchronizerNodes: LocalSynchronizerNodes[LocalSynchronizerNode],
    successorSynchronizerNode: LocalSynchronizerNode,
    participantAdminConnection: ParticipantAdminConnection,
    store: SvDsoStore,
    dumpPath: Path,
    hasBftSequencerConnections: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[LsuTransferTask] {

  private val currentSynchronizerNode = localSynchronizerNodes.current

  private val exporter =
    new LsuStateExporter(
      dumpPath,
      currentSynchronizerNode.sequencerAdminConnection,
      currentSynchronizerNode.mediatorAdminConnection,
      loggerFactory,
    )

  private val initializer =
    new LsuNodeInitializer(
      localSynchronizerNodes,
      successorSynchronizerNode,
      loggerFactory,
      context.retryProvider,
    )

  override protected lazy val context: TriggerContext =
    baseContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

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
      participantNeedsManualLsu <- participantNeedsManualLsu(
        now,
        physicalSynchronizerId,
        announcements.map(_.mapping),
      )
    } yield {
      announcements
        .filter { _ =>
          sequencerNotInitialized || mediatorNotInitialized || participantNeedsManualLsu
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
    for {
      rulesAndState <- store.getDsoRulesWithSvNodeStates()
      owningNodeSvName <- rulesAndState.getSvNameInDso(store.key.svParty)
      _ <- successorSynchronizerNode.cometbftNode.traverse(
        _.rotateGenesisGovernanceKeyForSV1(owningNodeSvName)
      )
      _ <- successorSynchronizerNode.cometbftNode.traverse(
        _.reconcileNetworkConfig(owningNodeSvName, rulesAndState)
      )
      state <- exporter.exportLSUState(
        topologyExportTime = None
      )
      parameters <- initializer.initializeSynchronizer(
        state,
        task.work.announcement.successorSynchronizerId,
        task.readyAt,
        Some(task.work.announcement.upgradeTime),
        ignorePsidCheck = false,
      )
      currentPsid <- currentSynchronizerNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      participantPsid <- participantAdminConnection.getPhysicalSynchronizerId(
        currentPsid.logical
      )
      needsManualLsu <- participantNeedsManualLsu(
        task.readyAt,
        currentPsid,
        Seq(task.work.announcement),
      )
      _ <-
        if (needsManualLsu) {
          logger.info(
            s"Participant is on physical synchronizer id $participantPsid, past upgrade time with no sequencer successor and BFT disabled, initiating manual LSU"
          )
          for {
            successorSequencerId <-
              successorSynchronizerNode.sequencerAdminConnection.getSequencerId
            _ <- participantAdminConnection
              .performManualLsu(
                currentPsid,
                task.work.announcement.successorSynchronizerId,
                Some(task.work.announcement.upgradeTime),
                Map(
                  successorSequencerId -> initializer.successorConnection
                ),
              )
          } yield {
            logger.info("Manual LSU completed")
          }
        } else {
          Future.unit
        }
      _ <- reconciler.reconcileSynchronizerNodeConfigIfRequired(
        localSynchronizerNodes.some,
        currentPsid.logical,
        OnboardedImmediately,
      )
    } yield {
      TaskSuccess(
        show"Initialized new synchronizer with parameters $parameters"
      )
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

  private def participantNeedsManualLsu(
      now: CantonTimestamp,
      currentPsid: PhysicalSynchronizerId,
      announcements: Seq[LsuAnnouncement],
  )(implicit tc: TraceContext): Future[Boolean] = {
    if (hasBftSequencerConnections) {
      Future.successful(false)
    } else
      announcements.find(a => now.isAfter(a.upgradeTime)) match {
        case Some(announcement) =>
          for {
            sequencerId <- currentSynchronizerNode.sequencerAdminConnection.getSequencerId
            hasNoSuccessor <- currentSynchronizerNode.sequencerAdminConnection
              .lookupSequencerSuccessors(currentPsid.logical, sequencerId)
              .map(_.isEmpty)
            participantPsid <- participantAdminConnection
              .getPhysicalSynchronizerId(currentPsid.logical)
          } yield {
            hasNoSuccessor && !hasBftSequencerConnections && participantPsid != announcement.successorSynchronizerId
          }
        case None => Future.successful(false)
      }
  }
}

object LogicalSynchronizerUpgradeTrigger {
  case class LsuTransferTask(announcement: LsuAnnouncement) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("announcement", _.announcement)
    )
  }
}
