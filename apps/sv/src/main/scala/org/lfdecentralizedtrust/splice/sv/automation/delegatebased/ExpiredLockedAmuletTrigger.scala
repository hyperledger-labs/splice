// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import ExpiredLockedAmuletTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional

class ExpiredLockedAmuletTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      splice.amulet.LockedAmulet.ContractId,
      splice.amulet.LockedAmulet,
    ](
      svTaskContext.dsoStore.multiDomainAcsStore,
      svTaskContext.dsoStore.listLockedExpiredAmulets(context.config.ignoredExpiredAmuletPartyIds),
      splice.amulet.LockedAmulet.COMPANION,
    )
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override protected def completeTaskAsDsoDelegate(
      co: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_LockedAmulet_ExpireAmulet(
        co.work.contractId,
        new splice.amulet.LockedAmulet_ExpireAmulet(
          latestOpenMiningRound.contractId
        ),
        Optional.of(controller),
      )
    )
    _ <- svTaskContext
      .connection(SpliceLedgerConnectionPriority.Low)
      .submit(
        Seq(store.key.svParty),
        Seq(store.key.dsoParty),
        update = cmd,
      )
      .noDedup
      .yieldUnit()
  } yield TaskSuccess(s"archived expired locked amulet")
}

object ExpiredLockedAmuletTrigger {
  type Task =
    ScheduledTaskTrigger.ReadyTask[
      AssignedContract[splice.amulet.LockedAmulet.ContractId, splice.amulet.LockedAmulet]
    ]
}
