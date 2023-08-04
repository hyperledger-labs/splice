package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.coin.{CoinRules, CoinRules_ClaimExpiredRewards}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.sv.store.ExpiredRewardCouponsBatch
import com.daml.network.util.{AssignedContract, Contract}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ExpiredRewardCouponsBatch]
    with SvTaskBasedTrigger[ExpiredRewardCouponsBatch] {
  private val store = svTaskContext.svcStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] =
    store.getExpiredRewardsForOldestClosedMiningRound()

  override protected def isStaleTask(expiredRewardsTask: ExpiredRewardCouponsBatch)(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.hasArchived(
    expiredRewardsTask.validatorCoupons ++ expiredRewardsTask.appCoupons
  )

  override def completeTaskAsLeader(
      expiredRewardsTask: ExpiredRewardCouponsBatch
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      numCmds <- expireRewardCouponsForRound(
        expiredRewardsTask,
        svcRules,
        coinRules,
      )
    } yield TaskSuccess(
      show"Expired ${numCmds} old reward coupons for closed round ${expiredRewardsTask}"
    )
  }

  private def expireRewardCouponsForRound(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    for {
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      validatorRewardCmd = svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
        coinRules.contractId,
        new CoinRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRound.contractId,
          expiredRewardsTask.validatorCoupons.asJava,
          Seq.empty.asJava,
        ),
      )
      appRewardCmd = svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
        coinRules.contractId,
        new CoinRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRound.contractId,
          Seq.empty.asJava,
          expiredRewardsTask.appCoupons.asJava,
        ),
      )
      cmds = Seq(validatorRewardCmd, appRewardCmd)
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext.connection
            .submitWithResultNoDedup(
              Seq(store.key.svParty),
              Seq(store.key.svcParty),
              cmd,
              domainId,
            )
        )
      )
    } yield cmds.size
  }
}
