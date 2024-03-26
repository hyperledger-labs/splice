package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.amuletrules.{AmuletRules, AmuletRules_ClaimExpiredRewards}
import com.daml.network.codegen.java.cn.dsorules.DsoRules
import com.daml.network.sv.store.ExpiredRewardCouponsBatch
import com.daml.network.util.{AssignedContract, Contract}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ExpiredRewardCouponsBatch]
    with SvTaskBasedTrigger[ExpiredRewardCouponsBatch] {
  private val store = svTaskContext.dsoStore

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
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      numCmds <- expireRewardCouponsForRound(
        expiredRewardsTask,
        dsoRules,
        amuletRules,
      )
    } yield TaskSuccess(
      show"Expired ${numCmds} old reward coupons for closed round ${expiredRewardsTask}"
    )
  }

  private def expireRewardCouponsForRound(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    val validatorRewardCmd = dsoRules.exercise(
      _.exerciseDsoRules_ClaimExpiredRewards(
        amuletRules.contractId,
        new AmuletRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRoundCid,
          expiredRewardsTask.validatorCoupons.asJava,
          Seq.empty.asJava,
          Seq.empty.asJava,
          None.toJava,
        ),
      )
    )
    val validatorFaucetCmd = dsoRules.exercise(
      _.exerciseDsoRules_ClaimExpiredRewards(
        amuletRules.contractId,
        new AmuletRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRoundCid,
          Seq.empty.asJava,
          Seq.empty.asJava,
          Seq.empty.asJava,
          Some(expiredRewardsTask.validatorFaucets.asJava).toJava,
        ),
      )
    )
    val appRewardCmd = dsoRules.exercise(
      _.exerciseDsoRules_ClaimExpiredRewards(
        amuletRules.contractId,
        new AmuletRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRoundCid,
          Seq.empty.asJava,
          expiredRewardsTask.appCoupons.asJava,
          Seq.empty.asJava,
          None.toJava,
        ),
      )
    )
    val svRewardCmd = dsoRules.exercise(
      _.exerciseDsoRules_ClaimExpiredRewards(
        amuletRules.contractId,
        new AmuletRules_ClaimExpiredRewards(
          expiredRewardsTask.closedRoundCid,
          Seq.empty.asJava,
          Seq.empty.asJava,
          expiredRewardsTask.svRewardCoupons.asJava,
          None.toJava,
        ),
      )
    )
    val cmds = Seq(validatorRewardCmd, validatorFaucetCmd, appRewardCmd, svRewardCmd)
    for {
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext.connection
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldResult()
        )
      )
    } yield cmds.size
  }
}
