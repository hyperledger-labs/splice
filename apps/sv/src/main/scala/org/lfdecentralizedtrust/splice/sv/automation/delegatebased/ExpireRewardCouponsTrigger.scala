// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_ClaimExpiredRewards,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.sv.store.ExpiredRewardCouponsBatch
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.PageLimit

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ExpiredRewardCouponsBatch]
    with SvTaskBasedTrigger[ExpiredRewardCouponsBatch] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] = for {
    dsoRules <- store.getDsoRules()
    batches <- store
      .getExpiredCouponsInBatchesPerRoundAndCouponType(
        dsoRules.domain,
        context.config.enableExpireValidatorFaucet,
        context.config.ignoredExpiredRewardsPartyIds,
        PageLimit.tryCreate(svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize),
      )
      // We select at most parallelism batches per round as  processing more than that would most likely just hit contention
      // If any work was done the trigger will run again anyway so it's safer to just requery the stores
      .map(seq => Random.shuffle(seq).take(context.config.parallelism))
  } yield batches

  override protected def isStaleTask(expiredRewardsTask: ExpiredRewardCouponsBatch)(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.containsArchived(
    expiredRewardsTask.validatorCoupons ++ expiredRewardsTask.appCoupons ++ expiredRewardsTask.validatorLivenessActivityRecords ++ expiredRewardsTask.svRewardCoupons
  )

  override def completeTaskAsDsoDelegate(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      numCoupons <- expireRewardCouponsForRound(
        expiredRewardsTask,
        dsoRules,
        amuletRules,
        Optional.of(controller),
      )
    } yield TaskSuccess(
      show"Expired ${numCoupons} old reward coupons for closed round ${expiredRewardsTask}"
    )
  }

  private def expireRewardCouponsForRound(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      controller: Optional[String],
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    val validatorRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            expiredRewardsTask.validatorCoupons.asJava,
            Seq.empty.asJava,
            Seq.empty.asJava,
            None.toJava,
            None.toJava,
          ),
          controller,
        )
      )
    ).filter(_ => expiredRewardsTask.validatorCoupons.nonEmpty)
    val validatorFaucetCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            Seq.empty.asJava,
            Seq.empty.asJava,
            Some(expiredRewardsTask.validatorFaucets.asJava).toJava,
            None.toJava,
          ),
          controller,
        )
      )
    ).filter(_ => expiredRewardsTask.validatorFaucets.nonEmpty)
    val validatorLivenessActivityRecordCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            Seq.empty.asJava,
            Seq.empty.asJava,
            None.toJava,
            Some(expiredRewardsTask.validatorLivenessActivityRecords.asJava).toJava,
          ),
          controller,
        )
      )
    ).filter(_ => expiredRewardsTask.validatorLivenessActivityRecords.nonEmpty)
    val appRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            expiredRewardsTask.appCoupons.asJava,
            Seq.empty.asJava,
            None.toJava,
            None.toJava,
          ),
          controller,
        )
      )
    ).filter(_ => expiredRewardsTask.appCoupons.nonEmpty)
    val svRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            Seq.empty.asJava,
            expiredRewardsTask.svRewardCoupons.asJava,
            None.toJava,
            None.toJava,
          ),
          controller,
        )
      )
    ).filter(_ => expiredRewardsTask.svRewardCoupons.nonEmpty)
    val cmds = Seq(
      validatorRewardCmd,
      appRewardCmd,
      svRewardCmd,
      validatorFaucetCmd,
      validatorLivenessActivityRecordCmd,
    ).flatten
    for {
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldResult()
        )
      )
    } yield expiredRewardsTask.validatorCoupons.size + expiredRewardsTask.appCoupons.size + expiredRewardsTask.validatorFaucets.size + expiredRewardsTask.svRewardCoupons.size + expiredRewardsTask.validatorLivenessActivityRecords.size
  }
}
