package com.daml.network.sv.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TriggerContext,
  TaskOutcome,
  TaskSuccess,
}
import com.daml.network.codegen.java.cc.coin.{CoinRules, CoinRules_ClaimExpiredRewards}
import com.daml.network.codegen.java.cc.coin.{ValidatorRewardCoupon, AppRewardCoupon}
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.sv.store.ExpiredRewardCouponsBatch
import com.daml.network.util.Contract
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
  ): Future[Boolean] = {
    for {
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)

      // lookup coupons in the ACS to check if they were archived
      validatorCouponsExist <- Future
        .sequence(
          expiredRewardsTask.validatorCoupons
            .fold(Seq())((acc, group) => acc ++ group)
            .map(validatorCoupon =>
              store.multiDomainAcsStore
                .lookupContractByIdOnDomain(ValidatorRewardCoupon.COMPANION)(
                  domainId,
                  validatorCoupon,
                )
                .map(_.isDefined)
            )
        )
        .map(_.exists(found => found))
      appCouponsExist <- Future
        .sequence(
          expiredRewardsTask.appCoupons
            .fold(Seq())((acc, group) => acc ++ group)
            .map(appCoupon =>
              store.multiDomainAcsStore
                .lookupContractByIdOnDomain(AppRewardCoupon.COMPANION)(
                  domainId,
                  appCoupon,
                )
                .map(_.isDefined)
            )
        )
        .map(_.exists(found => found))

      isStale <- Future.successful(!validatorCouponsExist && !appCouponsExist)
    } yield isStale
  }

  override def completeTaskAsFollower(
      expiredRewardsTask: ExpiredRewardCouponsBatch
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future.successful(
      TaskSuccess(
        show"ignoring ${expiredRewardsTask}, as we're not the leader"
      )
    )
  }

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
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    for {
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      validatorRewardCmds = expiredRewardsTask.validatorCoupons.map(group =>
        svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
          coinRules.contractId,
          new CoinRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRound.contractId,
            group.asJava,
            Seq.empty.asJava,
          ),
        )
      )
      appRewardCmds = expiredRewardsTask.appCoupons.map(group =>
        svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
          coinRules.contractId,
          new CoinRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRound.contractId,
            Seq.empty.asJava,
            group.asJava,
          ),
        )
      )
      cmds = validatorRewardCmds ++ appRewardCmds
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
