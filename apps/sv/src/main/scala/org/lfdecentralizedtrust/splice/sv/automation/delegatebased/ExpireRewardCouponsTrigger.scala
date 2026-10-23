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

  private def dropUnvettedBatch[TCid, T](
      batches: Map[Option[PackageVersion], Seq[Seq[Contract[TCid, T]]]],
      stakeholders: ContractStakeholders[T],
  )(implicit tc: TraceContext): Iterable[(PackageVersion, Seq[Contract[TCid, T]])] =
    batches.toSeq.flatMap {
      case (Some(v), batches) => batches.map((v, _))
      case (None, batches) =>
        val contracts = batches.flatten
        ignorePartiesWithoutVettedAmulet(
          stakeholders.getInformeesFromContracts(contracts),
          contracts.map(_.contractId.contractId),
        ).foreach(logger.info(_))
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
      (dropUnvettedBatch(validatorCoupons, ValidatorCoupons).map { case (v, batch) =>
        Task(v, emptyBatch.copy(validatorCoupons = batch))
      } ++
        dropUnvettedBatch(appCoupons, AppRewardCoupons).map { case (v, batch) =>
          Task(v, emptyBatch.copy(appCoupons = batch))
        } ++
        dropUnvettedBatch(svRewardCoupons, SvRewardCoupons).map { case (v, batch) =>
          Task(v, emptyBatch.copy(svRewardCoupons = batch))
        } ++
        dropUnvettedBatch(validatorFaucets, ValidatorFaucetCoupons).map { case (v, batch) =>
          Task(v, emptyBatch.copy(validatorFaucets = batch))
        } ++
        dropUnvettedBatch(validatorLivenessActivityRecords, ValidatorLivenessActivityRecords).map {
          case (v, batch) =>
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
    )(completeExpiryTaskAsDsoDelegate(task, controller))
  }

  private def completeExpiryTaskAsDsoDelegate(
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
