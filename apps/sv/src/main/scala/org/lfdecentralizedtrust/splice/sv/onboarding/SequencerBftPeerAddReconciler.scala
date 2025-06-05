// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.implicits.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.{TaskNoop, TaskOutcome, TaskSuccess}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.{ExecutionContext, Future}

class SequencerBftPeerAddReconciler(
    override protected val svDsoStore: SvDsoStore,
    sequencerAdminConnection: SequencerAdminConnection,
    val loggerFactory: NamedLoggerFactory,
    scanConnection: AggregatingScanConnection,
    migrationId: Long,
) extends SequencerBftPeerReconciler(sequencerAdminConnection, scanConnection, migrationId) {

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
        _ <- task.toAdd.toList.traverse(sequencerAdminConnection.addPeerEndpoint)
      } yield TaskSuccess(s"Finished bft peer addition: $task")
    }
  }
}
