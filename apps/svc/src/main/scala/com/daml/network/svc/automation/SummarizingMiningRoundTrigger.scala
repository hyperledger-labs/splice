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
      totalBurn = rewards.totalBurn
      coinRules <- store.getCoinRules()
      // TODO(M3-06): consider querying the round audit store (once we have it) and
      // passing along the opensAt time of the previous IssuingMiningRound
      // see discussion: https://docs.google.com/document/d/1RAcc4uJKjRtPKDmVglVhqg-y58fCJ7xyljPbwimE-IA/edit?disco=AAAAjyuFFEw
      cmd = coinRules.contractId
        .exerciseCoinRules_MiningRound_StartIssuing(
          summarizingRound.contractId,
          totalBurn.bigDecimal,
        )
      cid <-
        connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
    } yield TaskSuccess(
      s"successfully archived summarizing mining round with burn ${totalBurn}, and created issuing mining round with cid $cid"
    )
  }

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      appRewards: Seq[JavaContract[cc.coin.AppReward.ContractId, cc.coin.AppReward]],
      validatorRewards: Seq[
        JavaContract[cc.coin.ValidatorReward.ContractId, cc.coin.ValidatorReward]
      ],
  ) {

    /** Calculate the total burn for the given round based on the rewards issued in that round.
      */
    def totalBurn: BigDecimal =
      appRewards.map[BigDecimal](r => BigDecimal(r.payload.quantity)).sum + validatorRewards
        .map[BigDecimal](r => BigDecimal(r.payload.quantity))
        .sum
  }

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long)(implicit ec: ExecutionContext): Future[RoundRewards] =
    for {
      appRewards <- store.acs.listContracts(
        cc.coin.AppReward.COMPANION,
        (c: JavaContract[cc.coin.AppReward.ContractId, cc.coin.AppReward]) =>
          c.payload.round.number == round,
      )
      validatorRewards <- store.acs.listContracts(
        cc.coin.ValidatorReward.COMPANION,
        (c: JavaContract[cc.coin.ValidatorReward.ContractId, cc.coin.ValidatorReward]) =>
          c.payload.round.number == round,
      )
    } yield {
      RoundRewards(
        round = round,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
      )
    }
}
