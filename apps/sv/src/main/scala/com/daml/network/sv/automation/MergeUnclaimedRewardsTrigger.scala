package com.daml.network.sv.automation

import com.daml.network.automation.{
  TriggerContext,
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
}
import com.daml.network.codegen.java.cc.coin.UnclaimedReward
import com.daml.network.codegen.java.cn.svcrules.SvcRules_MergeUnclaimedRewards
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*

import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]
    ]
    with SvTaskBasedTrigger[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] {

  private val store = svTaskContext.svcStore

  protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Seq[Contract[
    UnclaimedReward.ContractId,
    UnclaimedReward,
  ]]]] =
    for {
      svcRules <- store.getSvcRules()
      threshold = svcRules.payload.config.numUnclaimedRewardsThreshold
      unclaimedRewards <- store.listUnclaimedRewards(threshold * 2)
    } yield
      (
        if (unclaimedRewards.length > threshold) {
          Seq(unclaimedRewards)
        } else {
          Seq()
        }
      )

  protected def isStaleTask(
      unclaimedRewards: Seq[Contract[
        UnclaimedReward.ContractId,
        UnclaimedReward,
      ]]
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      unclaimedRewardsExist <- store.multiDomainAcsStore.allKnownAndNotArchived(
        unclaimedRewards.map(_.contractId)
      )
    } yield !unclaimedRewardsExist

  override def completeTaskAsFollower(
      unclaimedRewards: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        show"ignoring ${unclaimedRewards}, as we're not the leader"
      )
    )
  }

  override def completeTaskAsLeader(
      unclaimedRewards: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      arg = new SvcRules_MergeUnclaimedRewards(
        coinRules.contractId,
        unclaimedRewards.map(_.contractId).asJava,
      )
      cmd = svcRules.contractId.exerciseSvcRules_MergeUnclaimedRewards(arg)
      res <- for {
        outcome <- svTaskContext.connection
          .submitWithResultNoDedup(
            Seq(store.key.svParty),
            Seq(store.key.svcParty),
            cmd,
            domainId,
          )
      } yield Some(outcome)
    } yield {
      res
        .map(cid => {
          TaskSuccess(
            s"Merged unclaimed rewards into contract ${cid.exerciseResult.contractId}"
          )
        })
        .getOrElse(TaskSuccess(s"Not enough unclaimed rewards to merge"))
    }
  }
}
