// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules_MergeUnclaimedRewards
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MergeUnclaimedRewardsTask]
    with SvTaskBasedTrigger[MergeUnclaimedRewardsTask] {

  private val store = svTaskContext.dsoStore

  protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MergeUnclaimedRewardsTask]] =
    for {
      dsoRules <- store.getDsoRules()
      threshold = dsoRules.payload.config.numUnclaimedRewardsThreshold
      limit = PageLimit.tryCreate(threshold.toInt * 2)
      unclaimedRewards <- store.listUnclaimedRewards(limit)
    } yield
      (
        if (unclaimedRewards.length > threshold) {
          Seq(MergeUnclaimedRewardsTask(unclaimedRewards))
        } else {
          Seq()
        }
      )

  protected def isStaleTask(
      unclaimedRewardsTask: MergeUnclaimedRewardsTask
  )(implicit tc: TraceContext): Future[Boolean] = store.multiDomainAcsStore.containsArchived(
    unclaimedRewardsTask.contracts.map(_.contractId)
  )

  override def completeTaskAsDsoDelegate(
      unclaimedRewardsTask: MergeUnclaimedRewardsTask,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      arg = new DsoRules_MergeUnclaimedRewards(
        amuletRules.contractId,
        unclaimedRewardsTask.contracts.map(_.contractId).asJava,
        Optional.of(controller),
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeUnclaimedRewards(arg))
      res <- for {
        outcome <- svTaskContext
          .connection(SpliceLedgerConnectionPriority.Low)
          .submit(
            Seq(store.key.svParty),
            Seq(store.key.dsoParty),
            cmd,
          )
          .noDedup
          .yieldResult()
      } yield Some(outcome)
    } yield {
      res
        .map(cid => {
          TaskSuccess(
            s"Merged unclaimed rewards into contract ${cid.exerciseResult.unclaimedReward.contractId}"
          )
        })
        .getOrElse(TaskSuccess(s"Not enough unclaimed rewards to merge"))
    }
  }
}

case class MergeUnclaimedRewardsTask(
    contracts: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(param("contracts", _.contracts))
}
