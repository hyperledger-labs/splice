// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0
package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext
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

  private val UNCLAIMED_REWARDS_LIMIT = PageLimit.tryCreate(10)

  override def completeTaskAsDsoDelegate(
      unallocatedUnclaimedActivityRecord: UnallocatedUnclaimedActivityRecordContract,
      controller: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {  //)(implicit tc: TraceContext, ec: ExecutionContext): Future[TaskOutcome] = {
    val store = svTaskContext.dsoStore
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      requiredAmount = unallocatedUnclaimedActivityRecord.payload.amount
      unclaimedRewardsToBurnCids <-
        collectSufficientUnclaimedRewards(store, UNCLAIMED_REWARDS_LIMIT, requiredAmount).map(_.map(_.contractId))
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_AllocateUnallocatedUnclaimedActivityRecord(
          unallocatedUnclaimedActivityRecord.contract.contractId,
          amuletRules.contractId,
          unclaimedRewardsToBurnCids.asJava,
          controller,
        )
      )
      _ <- svTaskContext.connection
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
    requiredAmount: BigDecimal
  )(implicit  tc: TraceContext, ec: ExecutionContext): Future[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] = {
    def loop(
      acc: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
      accAmount: BigDecimal
    ): Future[Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]]] = {
      if (accAmount >= requiredAmount) {
        Future.successful(takeSufficientUnclaimedRewards(acc, requiredAmount))
      } else {
        store.listUnclaimedRewards(limit).flatMap { newRewards =>
          val errorMsg = s"Insufficient rewards: ${requiredAmount - accAmount}"
          if (newRewards.isEmpty) {
            logger.warn(errorMsg)
            Future.failed(
              Status.INTERNAL
                .withDescription(errorMsg)
                .asRuntimeException()
            )
          } else {
            val updatedAcc = acc ++ newRewards
            val updatedAmount = accAmount + newRewards.map(r => scala.math.BigDecimal(r.payload.amount)).sum
            loop(updatedAcc, updatedAmount)
          }
        }
      }
    }

    loop(Seq.empty, BigDecimal(0))
  }

  private def takeSufficientUnclaimedRewards(
    rewards: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
    target: BigDecimal
  ): Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]] = {
    @annotation.tailrec
    def loop(
      remaining: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
      acc: Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]],
      sum: BigDecimal
    ): Seq[Contract[UnclaimedReward.ContractId, UnclaimedReward]] = {
      if (sum >= target) acc
      else {
        remaining.headOption match {
          case Some(next) =>
            loop(remaining.drop(1), acc :+ next, sum + next.payload.amount)
          case None =>
            acc // no more elements to consume
        }
      }
    }

    loop(rewards, Seq.empty, BigDecimal(0))
  }
}
