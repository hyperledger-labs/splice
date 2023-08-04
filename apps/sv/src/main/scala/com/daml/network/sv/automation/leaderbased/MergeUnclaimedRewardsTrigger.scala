package com.daml.network.sv.automation.leaderbased

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

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext

import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedRewardsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[MergeUnclaimedRewardsTask]
    with SvTaskBasedTrigger[MergeUnclaimedRewardsTask] {

  private val store = svTaskContext.svcStore

  protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[MergeUnclaimedRewardsTask]] =
    for {
      svcRules <- store.getSvcRules()
      threshold = svcRules.payload.config.numUnclaimedRewardsThreshold
      unclaimedRewards <- store.listUnclaimedRewards(threshold * 2)
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

  override def completeTaskAsLeader(
      unclaimedRewardsTask: MergeUnclaimedRewardsTask
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      arg = new SvcRules_MergeUnclaimedRewards(
        coinRules.contractId,
        unclaimedRewardsTask.contracts.map(_.contractId).asJava,
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

case class MergeUnclaimedRewardsTask(
    contracts: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(param("contracts", _.contracts))
}
