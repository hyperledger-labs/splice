package com.daml.network.svc.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.jdk.CollectionConverters.*

import scala.concurrent.{ExecutionContext, Future}

class ExpiredCoinTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cc.coin.Coin.Contract,
      cc.coin.Coin.ContractId,
      cc.coin.Coin,
    ](
      store.acs,
      store.listExpiredCoins,
      cc.coin.Coin.COMPANION,
    ) {

  override protected def completeTask(
      co: ScheduledTaskTrigger.ReadyTask[JavaContract[
        cc.coin.Coin.ContractId,
        cc.coin.Coin,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    domainId <- store.domains.getUniqueDomainId()
    coinRules <- store.getCoinRules()
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    cmd = co.work.contractId
      .exerciseCoin_Expire(
        latestOpenMiningRound.contractId,
        coinRules.contractId.toInterface(cc.api.v1.coin.CoinRules.INTERFACE),
      )
    _ <- connection
      .submitCommandsNoDedup(
        actAs = Seq(store.svcParty),
        readAs = Seq(),
        commands = cmd.commands.asScala.toSeq,
        domainId = domainId,
      )
  } yield TaskSuccess("archived expired coin")
}
