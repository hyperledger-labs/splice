package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract
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
      summarizingRound: JavaContract[
        cc.round.SummarizingMiningRound.ContractId,
        cc.round.SummarizingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      rewards <- queryRewards(summarizingRound.payload.round.number)
      coinRules <- store.getCoinRules()
      // TODO(M3-06): consider querying the round audit store (once we have it) and
      // passing along the opensAt time of the previous IssuingMiningRound
      // see discussion: https://docs.google.com/document/d/1RAcc4uJKjRtPKDmVglVhqg-y58fCJ7xyljPbwimE-IA/edit?disco=AAAAjyuFFEw
      cmd = coinRules.contractId
        .exerciseCoinRules_MiningRound_StartIssuing(
          summarizingRound.contractId,
          rewards.summary,
        )
      cid <-
        connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
    } yield TaskSuccess(
      s"completed summarizing mining round with ${rewards.summary}, and created issuing mining round with cid ${cid.exerciseResult}"
    )
  }

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      appRewardCoupons: Seq[
        JavaContract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]
      ],
      validatorRewardCoupons: Seq[
        JavaContract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]
      ],
  ) {
    lazy val summary: cc.issuance.OpenMiningRoundSummary = new cc.issuance.OpenMiningRoundSummary(
      validatorRewardCoupons.map[BigDecimal](c => BigDecimal(c.payload.quantity)).sum.bigDecimal,
      appRewardCoupons
        .collect[BigDecimal] { case c if c.payload.featured => BigDecimal(c.payload.quantity) }
        .sum
        .bigDecimal,
      appRewardCoupons
        .collect[BigDecimal] { case c if !c.payload.featured => BigDecimal(c.payload.quantity) }
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
        (c: JavaContract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
          c.payload.round.number == round,
      )
      validatorRewardCoupons <- store.acs.listContracts(
        cc.coin.ValidatorRewardCoupon.COMPANION,
        (c: JavaContract[
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
