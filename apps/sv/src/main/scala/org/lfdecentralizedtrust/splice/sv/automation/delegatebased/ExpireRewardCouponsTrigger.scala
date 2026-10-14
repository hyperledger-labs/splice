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
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.{UnavailablePartiesStore, PageLimit}
import org.lfdecentralizedtrust.splice.codegen.java.splice

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random
import ExpireRewardCouponsTrigger.Task
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.{
  ValidatorFaucetCoupon,
  ValidatorLivenessActivityRecord,
}
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.util.ContractStakeholders

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
    override protected val unavailablePartiesStore: UnavailablePartiesStore,
    override protected val svConfig: SvAppBackendConfig,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task]
    with UnavailablePartiesGuard {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[Task]] = for {
    dsoRules <- store.getDsoRules()
    batches <- store
      .getExpiredCouponsInBatchesPerRoundAndCouponType(
        dsoRules.domain,
        context.config.enableExpireValidatorFaucet,
        Some(unavailablePartiesStore),
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
      )(c => ValidatorCoupons.getStakeholders(c.payload))
      appCoupons <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.appCoupons,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => AppRewardCoupons.getStakeholders(c.payload))
      svRewardCoupons <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.svRewardCoupons,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => SvRewardCoupons.getStakeholders(c.payload))
      validatorFaucets <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.validatorFaucets,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => ValidatorFaucetCoupons.getStakeholders(c.payload))
      validatorLivenessActivityRecords <- svTaskContext.vettingLookupService.splitBatch(
        SpliceAmulet,
        batch.validatorLivenessActivityRecords,
        svTaskContext.delegatelessAutomationExpiredRewardCouponBatchSize,
      )(c => ValidatorLivenessActivityRecords.getStakeholders(c.payload))
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

  override def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val informees = ValidatorCoupons.getInformeesFromContracts(task.batch.validatorCoupons) ++
      AppRewardCoupons.getInformeesFromContracts(task.batch.appCoupons) ++
      SvRewardCoupons.getInformeesFromContracts(task.batch.svRewardCoupons) ++
      ValidatorFaucetCoupons.getInformeesFromContracts(task.batch.validatorFaucets) ++
      ValidatorLivenessActivityRecords.getInformeesFromContracts(
        task.batch.validatorLivenessActivityRecords
      )
    completeUnlessAmuletVersionIgnored(
      task.vettedAmuletVersion.toString,
      informees,
      ignoreUnresponsiveParties = true,
      restoreOnSuccess = false,
    )(completeExpiryTaskAsDsoDelegate(task, controller))
  }

  private def completeExpiryTaskAsDsoDelegate(
      expiredRewardsTask: Task,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      outcomes <- expireRewardCouponsForRound(
        expiredRewardsTask.batch,
        dsoRules,
        amuletRules,
        Optional.of(controller),
      )
    } yield TaskSuccess(
      show"Expired old reward coupons for closed round ${expiredRewardsTask}: ${outcomes.mkString("; ").unquoted}"
    )
  }

  private def expireRewardCouponsForRound(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      controller: Optional[String],
  )(implicit
      tc: TraceContext
  ): Future[Seq[String]] = {
    val round = expiredRewardsTask.closedRoundCid
    def noCids[C] = Seq.empty[C].asJava

    // Submits one ClaimExpiredRewards command per (sub-)batch of a single coupon type,
    // splitting the batch on failures to isolate the offending contracts.
    def expireWithSplitting[TCid, T](
        kind: String,
        coupons: Seq[Contract[TCid, T]],
        stakeholders: ContractStakeholders[T],
    )(mkArg: java.util.List[TCid] => AmuletRules_ClaimExpiredRewards): Future[Option[String]] =
      if (coupons.isEmpty) Future.successful(None)
      else
        submitBatchWithSplitting(
          coupons,
          (c: Contract[TCid, T]) => stakeholders.getStakeholders(c.payload).toSet,
        ) { batch =>
          svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              dsoRules.exercise(
                _.exerciseDsoRules_ClaimExpiredRewards(
                  amuletRules.contractId,
                  mkArg(batch.map(c => c.contractId: TCid).asJava),
                  controller,
                )
              ),
            )
            .noDedup
            .yieldUnit()
        }.map(outcome => Some(s"$kind: $outcome"))

    // The store query only produces batches with exactly one of the coupon types set,
    // so running these sequentially does not lose any parallelism.
    MonadUtil
      .sequentialTraverse(
        Seq[() => Future[Option[String]]](
          () =>
            expireWithSplitting(
              "validator coupons",
              expiredRewardsTask.validatorCoupons,
              ValidatorCoupons,
            )(cids =>
              new AmuletRules_ClaimExpiredRewards(
                round,
                cids,
                noCids,
                noCids,
                None.toJava,
                None.toJava,
              )
            ),
          () =>
            expireWithSplitting(
              "app coupons",
              expiredRewardsTask.appCoupons,
              AppRewardCoupons,
            )(cids =>
              new AmuletRules_ClaimExpiredRewards(
                round,
                noCids,
                cids,
                noCids,
                None.toJava,
                None.toJava,
              )
            ),
          () =>
            expireWithSplitting(
              "sv reward coupons",
              expiredRewardsTask.svRewardCoupons,
              SvRewardCoupons,
            )(cids =>
              new AmuletRules_ClaimExpiredRewards(
                round,
                noCids,
                noCids,
                cids,
                None.toJava,
                None.toJava,
              )
            ),
          () =>
            expireWithSplitting(
              "validator faucets",
              expiredRewardsTask.validatorFaucets,
              ValidatorFaucetCoupons,
            )(cids =>
              new AmuletRules_ClaimExpiredRewards(
                round,
                noCids,
                noCids,
                noCids,
                Some(cids).toJava,
                None.toJava,
              )
            ),
          () =>
            expireWithSplitting(
              "validator liveness activity records",
              expiredRewardsTask.validatorLivenessActivityRecords,
              ValidatorLivenessActivityRecords,
            )(cids =>
              new AmuletRules_ClaimExpiredRewards(
                round,
                noCids,
                noCids,
                noCids,
                None.toJava,
                Some(cids).toJava,
              )
            ),
        )
      )(_())
      .map(_.flatten)
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

object ValidatorCoupons extends ContractStakeholders[splice.amulet.ValidatorRewardCoupon] {
  override def informees(payload: splice.amulet.ValidatorRewardCoupon): Seq[String] = Seq(
    payload.user
  )
  override def dso(payload: splice.amulet.ValidatorRewardCoupon): String = payload.dso
}

object AppRewardCoupons extends ContractStakeholders[splice.amulet.AppRewardCoupon] {
  override def informees(payload: splice.amulet.AppRewardCoupon): Seq[String] =
    Seq(payload.provider) ++ payload.beneficiary.toScala.toList
  override def dso(payload: splice.amulet.AppRewardCoupon): String = payload.dso
}

object SvRewardCoupons extends ContractStakeholders[splice.amulet.SvRewardCoupon] {
  override def informees(payload: splice.amulet.SvRewardCoupon): Seq[String] =
    Seq(payload.sv, payload.beneficiary)
  override def dso(payload: splice.amulet.SvRewardCoupon): String = payload.dso
}

object ValidatorFaucetCoupons extends ContractStakeholders[ValidatorFaucetCoupon] {
  override def informees(payload: ValidatorFaucetCoupon): Seq[String] = Seq(payload.validator)
  override def dso(payload: ValidatorFaucetCoupon): String = payload.dso
}

object ValidatorLivenessActivityRecords
    extends ContractStakeholders[ValidatorLivenessActivityRecord] {
  override def informees(payload: ValidatorLivenessActivityRecord): Seq[String] = Seq(
    payload.validator
  )
  override def dso(payload: ValidatorLivenessActivityRecord): String = payload.dso
}
