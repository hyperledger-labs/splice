package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.{CoinRules, CoinRules_MiningRound_StartIssuing}
import com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary
import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      cc.round.SummarizingMiningRound.ContractId,
      cc.round.SummarizingMiningRound,
    ](
      store,
      () => store.domains.signalWhenConnected(store.defaultAcsDomain),
      cc.round.SummarizingMiningRound.COMPANION,
    ) {

  private def coinRulesStartIssuingAction(
      coinRulesCid: CoinRules.ContractId,
      miningRoundCid: SummarizingMiningRound.ContractId,
      summary: OpenMiningRoundSummary,
  ): ActionRequiringConfirmation =
    new ARC_CoinRules(
      coinRulesCid,
      new CRARC_MiningRound_StartIssuing(
        new CoinRules_MiningRound_StartIssuing(
          miningRoundCid,
          summary,
        )
      ),
    )

  override def completeTask(
      summarizingRound: Contract[
        cc.round.SummarizingMiningRound.ContractId,
        cc.round.SummarizingMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      rewards <- queryRewards(summarizingRound.payload.round.number)
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      action = coinRulesStartIssuingAction(
        coinRules.contractId,
        summarizingRound.contractId,
        rewards.summary,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(action)
      cmd = svcRules.contractId.exerciseSvcRules_ConfirmAction(
        store.key.svParty.toProtoPrimitive,
        action,
      )
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${store.key.svParty} is already created for such action"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submitCommands(
              actAs = Seq(store.key.svParty),
              readAs = Seq(store.key.svcParty),
              commands = cmd.commands.asScala.toSeq,
              commandId = CoinLedgerConnection.CommandId(
                "com.daml.network.directory.createMiningRoundStartIssuingConfirmation",
                Seq(store.key.svParty, store.key.svcParty),
                summarizingRound.contractId.contractId,
              ),
              deduplicationOffset = offset,
              domainId = domainId,
            )
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
      acs <- store.defaultAcs
      appRewardCoupons <- acs.listContracts(
        cc.coin.AppRewardCoupon.COMPANION,
        (c: Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
          c.payload.round.number == round,
      )
      validatorRewardCoupons <- acs.listContracts(
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
