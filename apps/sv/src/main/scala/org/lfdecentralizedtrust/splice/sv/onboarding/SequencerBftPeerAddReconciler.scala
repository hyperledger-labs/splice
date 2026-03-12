// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.implicits.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.automation.{TaskNoop, TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import scala.concurrent.{ExecutionContext, Future}

class SequencerBftPeerAddReconciler(
    override protected val svDsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    synchronizerNode: SynchronizerNodeService[LocalSynchronizerNode],
    val loggerFactory: NamedLoggerFactory,
    scanConnection: AggregatingScanConnection,
) extends SequencerBftPeerReconciler(participantAdminConnection, synchronizerNode, scanConnection) {

  override def reconcileTask(
      task: BftPeerDifference
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[TaskOutcome] = {
    if (task.toAdd.isEmpty) {
      Future.successful(TaskNoop)
    } else {
      logger.info(
        s"Adding bft peers. Current peers [${task.currentPeers}]. Adding: [${task.toAdd}]"
      )
      for {
        _ <- MonadUtil.sequentialTraverse(task.toAdd.toList)(
          task.adminConnection.addPeerEndpoint
        )
      } yield TaskSuccess(s"Finished bft peer addition: $task")
    }
  }
}
