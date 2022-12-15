package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class CoinRulesRequestTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      cc.coin.CoinRulesRequest.Contract,
      cc.coin.CoinRulesRequest.ContractId,
      cc.coin.CoinRulesRequest,
    ](store.acs, cc.coin.CoinRulesRequest.COMPANION) {

  override def completeTask(
      req: JavaContract[
        cc.coin.CoinRulesRequest.ContractId,
        cc.coin.CoinRulesRequest,
      ]
  )(implicit tc: TraceContext): Future[String] = {
    val validatorParty = PartyId.tryFromProtoPrimitive(req.payload.user)
    for {
      QueryResult(_, openMiningRounds) <- store.acs.listContracts(
        cc.round.OpenMiningRound.COMPANION
      )
      QueryResult(_, issuingMiningRounds) <- store.acs.listContracts(
        cc.round.IssuingMiningRound.COMPANION
      )
      QueryResult(_, coinRules) <- store.getCoinRules()
      cmds = req.contractId
        .exerciseCoinRulesRequest_Accept(
          coinRules.contractId,
          openMiningRounds.map(_.contractId).asJava,
          issuingMiningRounds.map(_.contractId).asJava,
        )
        .commands
        .asScala
        .toSeq
      // No command-dedup required, as the CoinRules contract is archived and recreated
      _ <- connection.submitCommandsNoDedup(Seq(store.svcParty), Seq(), cmds)
    } yield s"accepted coin rules request from $validatorParty"
  }

}
