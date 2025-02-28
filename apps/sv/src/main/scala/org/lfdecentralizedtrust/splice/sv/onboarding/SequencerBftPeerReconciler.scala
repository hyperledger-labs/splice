// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.implicits.*
import com.digitalasset.canton.config.RequireTypes
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.automation.{TaskFailed, TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.DsoRulesTopologyStateReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional

class SequencerBftPeerReconciler(
    override protected val svDsoStore: SvDsoStore,
    sequencerAdminConnection: SequencerAdminConnection,
    val loggerFactory: NamedLoggerFactory,
    migrationId: Long,
) extends DsoRulesTopologyStateReconciler[BftPeerDifference]
    with NamedLogging {

  private val sequencerP2pHostPrefix = s"sequencer-p2p-$migrationId"

  override protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[BftPeerDifference]] = {
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      sequencers = dsoRulesAndState
        .currentSynchronizerNodeConfigs()
        .flatMap(_.sequencer.toScala)
      sequencersWithoutSelf = sequencers.filter(_.sequencerId != sequencerId.toProtoPrimitive)
      dsoRulesPeers =
        sequencersWithoutSelf
          .flatMap(_.peerUrl.toScala)
          // the sequencers only accept the basic http protocol so far
          .map(rawPeerUrl =>
            if (rawPeerUrl.startsWith("http")) rawPeerUrl else s"http://$rawPeerUrl"
          )
          .map(Uri(_))
          .map(uri =>
            Endpoint(
              s"$sequencerP2pHostPrefix.${uri.authority.host.address()}",
              RequireTypes.Port(uri.effectivePort),
            )
          )
      currentPeers <- sequencerAdminConnection.listCurrentPeerEndpoints()
      peersToAdd = dsoRulesPeers.filterNot(currentPeers.contains)
      peersToRemove = currentPeers.filterNot(dsoRulesPeers.contains)
    } yield {
      if (peersToAdd.nonEmpty || peersToRemove.nonEmpty)
        Seq(BftPeerDifference(peersToAdd, peersToRemove, currentPeers))
      else Seq()
    }
  }

  override def reconcileTask(
      task: BftPeerDifference
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[TaskOutcome] = {
    if (task.toRemove.exists(!_.host.contains(sequencerP2pHostPrefix))) {
      val message =
        s"Not changing sequencer BFT p2p connection as it looks like the old sequencers are not configured for the current migration id, and there's a high chance that the sequencer is for a different migration id. Task: $task"
      logger.warn(
        message
      )
      Future.successful(
        TaskFailed(
          message
        )
      )
    } else {
      logger.info(
        s"Reconciling bft peers. Current peers [${task.currentPeers}]. Removing: [${task.toRemove}]. Adding: [${task.toAdd}]"
      )
      for {
        _ <- task.toAdd.toList.traverse(sequencerAdminConnection.addPeerEndpoint)
        _ <- task.toRemove.toList.traverse(sequencerAdminConnection.removePeerEndpoint)
      } yield TaskSuccess(s"Finished bft peer reconciling: $task")
    }
  }
}

object SequencerBftPeerReconciler {
  case class BftPeerDifference(
      toAdd: Seq[Endpoint],
      toRemove: Seq[Endpoint],
      currentPeers: Seq[Endpoint],
  )
}
