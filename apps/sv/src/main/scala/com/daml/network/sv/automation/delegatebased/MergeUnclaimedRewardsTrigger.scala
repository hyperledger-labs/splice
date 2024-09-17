// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.amulet.UnclaimedReward
import com.daml.network.codegen.java.splice.dsorules.DsoRules_MergeUnclaimedRewards
import com.daml.network.store.PageLimit
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

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
  )(implicit tc: TraceContext): Future[Boolean] = store.multiDomainAcsStore.hasArchived(
    unclaimedRewardsTask.contracts.map(_.contractId)
  )

  override def completeTaskAsDsoDelegate(
      unclaimedRewardsTask: MergeUnclaimedRewardsTask
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      arg = new DsoRules_MergeUnclaimedRewards(
        amuletRules.contractId,
        unclaimedRewardsTask.contracts.map(_.contractId).asJava,
      )
      cmd = dsoRules.exercise(_.exerciseDsoRules_MergeUnclaimedRewards(arg))
      res <- for {
        outcome <- svTaskContext.connection
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
