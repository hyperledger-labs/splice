// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import cats.implicits.toTraverseOps
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.transaction.{LsuAnnouncement, TopologyChangeOp}
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{ScheduledTaskTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTriggerBase
import org.lfdecentralizedtrust.splice.lsu.LsuTransferTriggerBase.LsuTransferTask
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState.OnboardedImmediately
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

class LsuTrigger(
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
) extends LsuTransferTriggerBase[LocalSynchronizerNode](
      baseContext,
      localSynchronizerNodes,
      successorSynchronizerNode,
      dumpPath,
    ) {

  /** Without BFT connections the participant does not follow the upgrade on its own. */
  override protected def additionalWorkPending(
      now: CantonTimestamp,
      currentPsid: PhysicalSynchronizerId,
      announcements: Seq[LsuAnnouncement],
  )(implicit tc: TraceContext): Future[Boolean] =
    participantNeedsManualLsu(now, currentPsid, announcements)

  override protected def beforeInitialize(
      task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask]
  )(implicit tc: TraceContext): Future[Unit] =
    for {
      rulesAndState <- store.getDsoRulesWithSvNodeStates()
      owningNodeSvName <- rulesAndState.getSvNameInDso(store.key.svParty)
      _ <- successorSynchronizerNode.cometbftNode.traverse(
        _.rotateGenesisGovernanceKeyForSV1(owningNodeSvName)
      )
      _ <- successorSynchronizerNode.cometbftNode.traverse(
        _.reconcileNetworkConfig(owningNodeSvName, rulesAndState)
      )
    } yield ()

  override protected def afterInitialize(
      task: ScheduledTaskTrigger.ReadyTask[LsuTransferTask]
  )(implicit tc: TraceContext): Future[Unit] =
    for {
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
                task.work.announcement.successorSynchronizerId.tryAsPhysical,
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
        localSynchronizerNodes,
        currentPsid.logical,
        OnboardedImmediately,
      )
    } yield ()

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
              .lookupSequencerSuccessors(
                announcement.successorSynchronizerId.logical,
                sequencerId,
                Some(announcement.successorSynchronizerId.tryAsPhysical),
                Some(TopologyChangeOp.Replace),
              )
              .map(_.isEmpty)
            participantPsid <- participantAdminConnection
              .getPhysicalSynchronizerId(currentPsid.logical)
          } yield {
            hasNoSuccessor && !hasBftSequencerConnections && participantPsid.opaque != announcement.successorSynchronizerId
          }
        case None => Future.successful(false)
      }
  }
}
