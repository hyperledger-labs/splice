// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.daml.lf.data.Ref.PackageVersion
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
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.Package.SpliceAmulet
import org.lfdecentralizedtrust.splice.sv.store.ExpiredRewardCouponsBatch
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.PageLimit

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random

import ExpireRewardCouponsTrigger.Task

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = for {
    dsoRules <- store.getDsoRules()
    batches <- store
      .getExpiredCouponsInBatchesPerRoundAndCouponType(
        dsoRules.domain,
        context.config.enableExpireValidatorFaucet,
        context.config.ignoredExpiredRewardsPartyIds,
        batchSize =
          PageLimit.tryCreate(svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize),
        numBatches =
          PageLimit.tryCreate(svTaskContext.delegatelessAutomationExpiredRewardCouponNumBatches),
      )
      // We select at most parallelism batches per round as  processing more than that would most likely just hit contention
      // If any work was done the trigger will run again anyway so it's safer to just requery the stores
      .flatMap(MonadUtil.sequentialTraverse(_)(splitBatch(_)).map(_.flatten))
      .map(seq => Random.shuffle(seq).take(context.config.parallelism))
  } yield batches

  def dropUnvettedBatch[TCid, T](
      batches: Map[Option[PackageVersion], Seq[Seq[Contract[TCid, T]]]]
  )(implicit tc: TraceContext): Iterable[(PackageVersion, Seq[Contract[TCid, T]])] =
    batches.view.flatMap {
      case (Some(v), batches) => batches.map((v, _))
      case (None, batches) =>
        logger.warn(s"No vetted amulet version for ${batches.flatMap(_.map(_.contractId))}")
        Seq.empty
    }

  private def splitBatch(
      batch: ExpiredRewardCouponsBatch
  )(implicit tc: TraceContext): Future[Seq[Task]] =
    for {
      validatorCoupons <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.validatorCoupons,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => Seq(c.payload.dso, c.payload.user).map(PartyId.tryFromProtoPrimitive(_)))
      appCoupons <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.appCoupons,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c =>
        (Seq(c.payload.dso, c.payload.provider) ++ c.payload.beneficiary.toScala.toList)
          .map(PartyId.tryFromProtoPrimitive(_))
      )
      svRewardCoupons <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.svRewardCoupons,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c =>
        Seq(c.payload.dso, c.payload.sv, c.payload.beneficiary)
          .map(PartyId.tryFromProtoPrimitive(_))
      )
      validatorFaucets <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.validatorFaucets,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => Seq(c.payload.dso, c.payload.validator).map(PartyId.tryFromProtoPrimitive(_)))
      validatorLivenessActivityRecords <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.validatorLivenessActivityRecords,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => Seq(c.payload.dso, c.payload.validator).map(PartyId.tryFromProtoPrimitive(_)))
    } yield {
      val emptyBatch = ExpiredRewardCouponsBatch(
        closedRoundCid = batch.closedRoundCid,
        closedRoundNumber = batch.closedRoundNumber,
        validatorCoupons = Seq.empty,
        appCoupons = Seq.empty,
        svRewardCoupons = Seq.empty,
        validatorFaucets = Seq.empty,
        validatorLivenessActivityRecords = Seq.empty,
      )
      // The store query only produces batches with exactly one of the coupon types set so we also don't bother merging them here.
      (dropUnvettedBatch(validatorCoupons).map { case (v, batch) =>
        Task(v, emptyBatch.copy(validatorCoupons = batch))
      } ++
        dropUnvettedBatch(appCoupons).map { case (v, batch) =>
          Task(v, emptyBatch.copy(appCoupons = batch))
        } ++
        dropUnvettedBatch(svRewardCoupons).map { case (v, batch) =>
          Task(v, emptyBatch.copy(svRewardCoupons = batch))
        } ++
        dropUnvettedBatch(validatorFaucets).map { case (v, batch) =>
          Task(v, emptyBatch.copy(validatorFaucets = batch))
        } ++
        dropUnvettedBatch(validatorLivenessActivityRecords).map { case (v, batch) =>
          Task(v, emptyBatch.copy(validatorLivenessActivityRecords = batch))
        }).toSeq
    }

  override protected def isStaleTask(expiredRewardsTask: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.containsArchived(
    expiredRewardsTask.batch.validatorCoupons
      .map(_.contractId) ++ expiredRewardsTask.batch.appCoupons
      .map(_.contractId) ++ expiredRewardsTask.batch.validatorLivenessActivityRecords.map(
      _.contractId
    ) ++ expiredRewardsTask.batch.svRewardCoupons.map(_.contractId)
  )

  override def completeTaskAsDsoDelegate(
      expiredRewardsTask: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      numCoupons <- expireRewardCouponsForRound(
        expiredRewardsTask.batch,
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
            expiredRewardsTask.validatorCoupons.map(_.contractId).asJava,
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
            Some(expiredRewardsTask.validatorFaucets.map(_.contractId).asJava).toJava,
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
            Some(
              expiredRewardsTask.validatorLivenessActivityRecords.map(_.contractId).asJava
            ).toJava,
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
            expiredRewardsTask.appCoupons.map(_.contractId).asJava,
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
            expiredRewardsTask.svRewardCoupons.map(_.contractId).asJava,
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

object ExpireRewardCouponsTrigger {
  final case class Task(
      vettedAmuletVersion: PackageVersion,
      batch: ExpiredRewardCouponsBatch,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("vettedAmuletVersion", _.vettedAmuletVersion),
        param("batch", _.batch),
      )
  }
}
