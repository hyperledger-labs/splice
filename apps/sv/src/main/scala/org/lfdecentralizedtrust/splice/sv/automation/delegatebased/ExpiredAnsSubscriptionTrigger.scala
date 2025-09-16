// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subsCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.SubscriptionIdleState_ExpireSubscription
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class ExpiredAnsSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends ScheduledTaskTrigger[SvDsoStore.IdleAnsSubscription]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]] {
  private val store = svTaskContext.dsoStore

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[SvDsoStore.IdleAnsSubscription]] =
    store.listExpiredAnsSubscriptions(now, PageLimit.tryCreate(limit))

  override protected def completeTaskAsDsoDelegate(
      task: ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription],
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    cmd = dsoRules.exercise(
      _.exerciseDsoRules_ExpireSubscription(
        task.work.context.contractId,
        task.work.state.contractId,
        new SubscriptionIdleState_ExpireSubscription(store.key.dsoParty.toProtoPrimitive),
        Optional.of(controller),
      )
    )
    result <- svTaskContext
      .connection(SpliceLedgerConnectionPriority.Low)
      .submit(
        actAs = Seq(store.key.svParty),
        readAs = Seq(store.key.dsoParty),
        cmd,
      )
      .noDedup
      .yieldUnit()
      .map(_ => TaskSuccess(s"archived expired ans subscription"))

  } yield result

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[SvDsoStore.IdleAnsSubscription]
  )(implicit tc: TraceContext): Future[Boolean] =
    (for {
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          subsCodegen.SubscriptionIdleState.COMPANION
        )(
          task.work.state.contractId
        )
      )
      _ <- OptionT(
        store.multiDomainAcsStore.lookupContractById(
          ansCodegen.AnsEntryContext.COMPANION
        )(
          task.work.context.contractId
        )
      )
    } yield ()).isEmpty
}
