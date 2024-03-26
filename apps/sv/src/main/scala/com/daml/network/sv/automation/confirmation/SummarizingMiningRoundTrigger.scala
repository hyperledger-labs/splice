package com.daml.network.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules_MiningRound_StartIssuing
import com.daml.network.codegen.java.splice.issuance.OpenMiningRoundSummary
import com.daml.network.codegen.java.splice.round.SummarizingMiningRound
import com.daml.network.codegen.java.splice.dsorules.ActionRequiringConfirmation
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import com.daml.network.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      splice.round.SummarizingMiningRound.ContractId,
      splice.round.SummarizingMiningRound,
    ](
      store,
      splice.round.SummarizingMiningRound.COMPANION,
    ) {

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

  override def completeTask(
      summarizingRound: AssignedContract[
        splice.round.SummarizingMiningRound.ContractId,
        splice.round.SummarizingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      rewards <- queryRewards(
        summarizingRound.payload.round.number,
        summarizingRound.domain,
      )
      dsoRules <- store.getDsoRules()
      action = amuletRulesStartIssuingAction(
        summarizingRound.contractId,
        rewards.summary,
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
              s"skipping as confirmation from ${svParty} is already created for such action"
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
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.createMiningRoundStartIssuingConfirmation",
                Seq(svParty, dsoParty),
                summarizingRound.contractId.contractId,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map { _ =>
              TaskSuccess(
                s"created confirmation for summarizing mining round with ${rewards.summary}"
              )
            }
      }
    } yield taskOutcome
  }

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      featuredAppRewardCoupons: BigDecimal,
      unfeaturedAppRewardCoupons: BigDecimal,
      validatorRewardCoupons: BigDecimal,
      validatorFaucetCoupons: Long,
      svRewardCouponsWeightSum: Long,
  ) {
    lazy val summary: splice.issuance.OpenMiningRoundSummary =
      new splice.issuance.OpenMiningRoundSummary(
        validatorRewardCoupons.bigDecimal,
        featuredAppRewardCoupons.bigDecimal,
        unfeaturedAppRewardCoupons.bigDecimal,
        svRewardCouponsWeightSum,
        Optional.of(validatorFaucetCoupons),
      )
  }

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long, domain: DomainId)(implicit
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
        validatorFaucetCoupons = validatorFaucetCoupons,
        svRewardCouponsWeightSum = svRewardCouponsWeightSum,
      )
    }
  }
}
