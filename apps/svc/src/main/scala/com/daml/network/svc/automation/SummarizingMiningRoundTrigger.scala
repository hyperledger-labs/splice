package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      cc.round.SummarizingMiningRound.Contract,
      cc.round.SummarizingMiningRound.ContractId,
      cc.round.SummarizingMiningRound,
    ](store.acs, cc.round.SummarizingMiningRound.COMPANION) {

  override def completeTask(
      summarizingRound: Contract[
        cc.round.SummarizingMiningRound.ContractId,
        cc.round.SummarizingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.getUniqueDomainId()
      rewards <- queryRewards(summarizingRound.payload.round.number)
      coinRules <- store.getCoinRules()
      cmd = coinRules.contractId
        .exerciseCoinRules_MiningRound_StartIssuing(
          summarizingRound.contractId,
          rewards.summary,
        )
      cid <-
        connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd, domainId)
    } yield TaskSuccess(
      s"completed summarizing mining round with ${rewards.summary}, and created issuing mining round with cid ${cid.exerciseResult}"
    )
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
    )
  }

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long)(implicit ec: ExecutionContext): Future[RoundRewards] =
    for {
      appRewardCoupons <- store.acs.listContracts(
        cc.coin.AppRewardCoupon.COMPANION,
        (c: Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
          c.payload.round.number == round,
      )
      validatorRewardCoupons <- store.acs.listContracts(
        cc.coin.ValidatorRewardCoupon.COMPANION,
        (c: Contract[
          cc.coin.ValidatorRewardCoupon.ContractId,
          cc.coin.ValidatorRewardCoupon,
        ]) => c.payload.round.number == round,
      )
    } yield {
      RoundRewards(
        round = round,
        appRewardCoupons = appRewardCoupons,
        validatorRewardCoupons = validatorRewardCoupons,
      )
    }
}
