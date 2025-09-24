// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.UnclaimedReward
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.UnallocatedUnclaimedActivityRecord
import org.lfdecentralizedtrust.splice.store.PageLimit
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.Contract
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AllocateUnallocatedUnclaimedActivityRecordTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      UnallocatedUnclaimedActivityRecord.ContractId,
      UnallocatedUnclaimedActivityRecord,
    ](
      svTaskContext.dsoStore,
      UnallocatedUnclaimedActivityRecord.COMPANION,
    )
    with SvTaskBasedTrigger[AssignedContract[
      UnallocatedUnclaimedActivityRecord.ContractId,
      UnallocatedUnclaimedActivityRecord,
    ]] {
  private type UnallocatedUnclaimedActivityRecordContract = AssignedContract[
    UnallocatedUnclaimedActivityRecord.ContractId,
    UnallocatedUnclaimedActivityRecord,
  ]

  private val UNCLAIMED_REWARDS_LIMIT = PageLimit.tryCreate(100)

  override def completeTaskAsDsoDelegate(
      unallocatedUnclaimedActivityRecord: UnallocatedUnclaimedActivityRecordContract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val store = svTaskContext.dsoStore
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      requiredAmount = unallocatedUnclaimedActivityRecord.payload.amount
      unclaimedRewardsToBurnCids <-
        collectSufficientUnclaimedRewards(store, UNCLAIMED_REWARDS_LIMIT, requiredAmount).map(
          _.map(_.contractId)
        )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_AllocateUnallocatedUnclaimedActivityRecord(
          unallocatedUnclaimedActivityRecord.contract.contractId,
          amuletRules.contractId,
          unclaimedRewardsToBurnCids.asJava,
          controller,
        )
      )
      _ <- svTaskContext
        .connection(SpliceLedgerConnectionPriority.Medium)
        .submit(
          Seq(store.key.svParty),
          Seq(store.key.dsoParty),
          cmd,
        )
        .noDedup
        .yieldUnit()
    } yield TaskSuccess(
      s"allocated unallocated unclaimed activity record"
    )
  }

  private def collectSufficientUnclaimedRewards(
      store: SvDsoStore,
      limit: PageLimit,
      requiredAmount: BigDecimal,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] =
    store.listUnclaimedRewards(limit).flatMap { unclaimedRewards =>
      val sortedUnclaimedRewards = unclaimedRewards.sortBy(_.payload.amount.negate())
      val (collectedUnclaimedRewards, collectedAmount) =
        takeSufficientUnclaimedRewards(sortedUnclaimedRewards, requiredAmount)
      if (collectedAmount < requiredAmount) {
        val errorMsg = s"Insufficient rewards: ${requiredAmount - collectedAmount}"
        logger.warn(errorMsg)
        Future.failed(
          Status.INTERNAL
            .withDescription(errorMsg)
            .asRuntimeException()
        )
      } else {
        Future.successful(collectedUnclaimedRewards)
      }
    }

  private def takeSufficientUnclaimedRewards(
      rewards: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
      target: BigDecimal,
  ): (Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]], BigDecimal) = {
    @annotation.tailrec
    def loop(
        remaining: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
        unclaimedRewardsAcc: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
        amountAcc: BigDecimal,
    ): (Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]], BigDecimal) = {
      if (amountAcc >= target) (unclaimedRewardsAcc, amountAcc)
      else {
        remaining.toList match {
          case next :: remaining =>
            loop(remaining, unclaimedRewardsAcc :+ next, amountAcc + next.payload.amount)
          case Nil =>
            (unclaimedRewardsAcc, amountAcc) // no more elements to consume
        }
      }
    }

    loop(rewards, Seq.empty, BigDecimal(0))
  }
}
