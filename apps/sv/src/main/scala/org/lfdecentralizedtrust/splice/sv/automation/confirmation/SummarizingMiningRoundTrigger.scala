// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MiningRound_StartIssuing
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.OpenMiningRoundSummary
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.SummarizingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

/** This is a polling trigger to avoid issues where SVs run out of retries (e.g. due to the synchronizer being down)
  * and then the round gets stuck forever in the summarizing state.
  */
class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SummarizingMiningRoundTrigger.Task] {

  import SummarizingMiningRoundTrigger.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  private def amuletRulesStartIssuingAction(
      miningRoundCid: SummarizingMiningRound.ContractId,
      summary: OpenMiningRoundSummary,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_MiningRound_StartIssuing(
        new AmuletRules_MiningRound_StartIssuing(
          miningRoundCid,
          summary,
        )
      )
    )

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    summarizingRounds <- store.listOldestSummarizingMiningRounds()
    tasks <- MonadUtil
      .sequentialTraverse(summarizingRounds) { round =>
        for {
          rewards <- queryRewards(round.payload.round.number, round.domain)
          action = amuletRulesStartIssuingAction(
            round.contractId,
            rewards.summary,
          )
          queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
        } yield queryResult.value match {
          case None =>
            Some(
              Task(
                round,
                rewards,
              )
            )
          case Some(_) => None
        }
      }
      .map(_.flatten)
  } yield tasks

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.summarizingRound.contract.payload.round.number
    for {
      dsoRules <- store.getDsoRules()
      action = amuletRulesStartIssuingAction(
        task.summarizingRound.contractId,
        task.rewards.summary,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${svParty} is already created for summarizing round ${round}"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.sv.createMiningRoundStartIssuingConfirmation",
                Seq(svParty, dsoParty),
                task.summarizingRound.contractId.contractId,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map { _ =>
              TaskSuccess(
                s"created confirmation for summarizing mining round ${round}"
              )
            }
      }
    } yield taskOutcome
  }

  override def isStaleTask(task: SummarizingMiningRoundTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    // We don't bother checking if a confirmation exists since this is handled in completeTask
    store.multiDomainAcsStore
      .lookupContractById(SummarizingMiningRound.COMPANION)(task.summarizingRound.contractId)
      .map(_.isEmpty)

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long, domain: SynchronizerId)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[RoundRewards] = {
    for {
      appRewardCoupons <- store.sumAppRewardCouponsOnDomain(
        round,
        domain,
      )
      validatorRewardCoupons <- store.sumValidatorRewardCouponsOnDomain(
        round,
        domain,
      )
      validatorFaucetCoupons <- store.countValidatorFaucetCouponsOnDomain(
        round,
        domain,
      )
      validatorLivenessActivityRecords <- store.countValidatorLivenessActivityRecordsOnDomain(
        round,
        domain,
      )
      svRewardCouponsWeightSum <- store.sumSvRewardCouponWeightsOnDomain(
        round,
        domain,
      )
    } yield {
      RoundRewards(
        round = round,
        featuredAppRewardCoupons = appRewardCoupons.featured,
        unfeaturedAppRewardCoupons = appRewardCoupons.unfeatured,
        validatorRewardCoupons = validatorRewardCoupons,
        validatorLivenessActivityRecords =
          validatorFaucetCoupons + validatorLivenessActivityRecords,
        svRewardCouponsWeightSum = svRewardCouponsWeightSum,
      )
    }
  }
}

object SummarizingMiningRoundTrigger {
  final case class RoundRewards(
      round: Long,
      featuredAppRewardCoupons: BigDecimal,
      unfeaturedAppRewardCoupons: BigDecimal,
      validatorRewardCoupons: BigDecimal,
      validatorLivenessActivityRecords: Long,
      svRewardCouponsWeightSum: Long,
  ) extends PrettyPrinting {
    lazy val summary: splice.issuance.OpenMiningRoundSummary =
      new splice.issuance.OpenMiningRoundSummary(
        validatorRewardCoupons.bigDecimal,
        featuredAppRewardCoupons.bigDecimal,
        unfeaturedAppRewardCoupons.bigDecimal,
        svRewardCouponsWeightSum,
        Optional.empty(), // optTotalValidatorFaucetCoupons (deprecated)
        Optional.of(
          java.math.BigDecimal.valueOf(validatorLivenessActivityRecords)
        ), // optTotalValidatorLivenessActivityRecords
      )

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("featuredAppRewardCoupons", _.featuredAppRewardCoupons),
        param("unfeaturedAppRewardCoupons", _.unfeaturedAppRewardCoupons),
        param("validatorRewardCoupons", _.validatorRewardCoupons),
        param("validatorLivenessActivityRecords", _.validatorLivenessActivityRecords),
        param("svRewardCouponsWeightSum", _.svRewardCouponsWeightSum),
      )
  }

  final case class Task(
      summarizingRound: AssignedContract[
        splice.round.SummarizingMiningRound.ContractId,
        splice.round.SummarizingMiningRound,
      ],
      rewards: RoundRewards,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("summarizingRound", _.summarizingRound),
        param("rewards", _.rewards),
      )
  }
}
