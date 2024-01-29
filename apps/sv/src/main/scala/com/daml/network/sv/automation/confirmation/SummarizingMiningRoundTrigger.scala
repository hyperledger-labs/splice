package com.daml.network.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinrules.CoinRules_MiningRound_StartIssuing
import com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary
import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.Limit
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.{AssignedContract, Contract}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      cc.round.SummarizingMiningRound.ContractId,
      cc.round.SummarizingMiningRound,
    ](
      store,
      cc.round.SummarizingMiningRound.COMPANION,
    ) {

  private val svParty = store.key.svParty
  private val svcParty = store.key.svcParty

  private def coinRulesStartIssuingAction(
      miningRoundCid: SummarizingMiningRound.ContractId,
      summary: OpenMiningRoundSummary,
  ): ActionRequiringConfirmation =
    new ARC_CoinRules(
      new CRARC_MiningRound_StartIssuing(
        new CoinRules_MiningRound_StartIssuing(
          miningRoundCid,
          summary,
        )
      )
    )

  override def completeTask(
      summarizingRound: AssignedContract[
        cc.round.SummarizingMiningRound.ContractId,
        cc.round.SummarizingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      rewards <- queryRewards(
        summarizingRound.payload.round.number,
        summarizingRound.domain,
      )
      svcRules <- store.getSvcRules()
      action = coinRulesStartIssuingAction(
        summarizingRound.contractId,
        rewards.summary,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = svcRules.exercise(
        _.exerciseSvcRules_ConfirmAction(
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
              readAs = Seq(svcParty),
              update = cmd,
            )
            .withDedup(
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.createMiningRoundStartIssuingConfirmation",
                Seq(svParty, svcParty),
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
      appRewardCoupons: Seq[
        Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]
      ],
      validatorRewardCoupons: Seq[
        Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]
      ],
      validatorFaucetCoupons: Seq[
        Contract[
          cc.validatorlicense.ValidatorFaucetCoupon.ContractId,
          cc.validatorlicense.ValidatorFaucetCoupon,
        ]
      ],
  ) {
    lazy val summary: cc.issuance.OpenMiningRoundSummary = new cc.issuance.OpenMiningRoundSummary(
      validatorRewardCoupons.map[BigDecimal](c => BigDecimal(c.payload.amount)).sum.bigDecimal,
      appRewardCoupons
        .collect[BigDecimal] { case c if c.payload.featured => BigDecimal(c.payload.amount) }
        .sum
        .bigDecimal,
      appRewardCoupons
        .collect[BigDecimal] { case c if !c.payload.featured => BigDecimal(c.payload.amount) }
        .sum
        .bigDecimal,
      // TODO(#9173): total up SV reward coupons weights,
      0,
      Optional.of(validatorFaucetCoupons.size.toLong),
    )
  }

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long, domain: DomainId)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[RoundRewards] = {
    // TODO (#9607): this will break if there's more than DefaultLimit contracts, sum/count in DB instead.
    for {
      appRewardCoupons <- store.listAppRewardCouponsOnDomain(round, domain, Limit.DefaultLimit)
      validatorRewardCoupons <- store.listValidatorRewardCouponsOnDomain(
        round,
        domain,
        Limit.DefaultLimit,
      )
      validatorFaucetCoupons <- store
        .listValidatorFaucetCouponsOnDomain(
          round,
          domain,
          Limit.DefaultLimit,
        )
    } yield {
      RoundRewards(
        round = round,
        appRewardCoupons = appRewardCoupons,
        validatorRewardCoupons = validatorRewardCoupons,
        validatorFaucetCoupons = validatorFaucetCoupons,
      )
    }
  }
}
