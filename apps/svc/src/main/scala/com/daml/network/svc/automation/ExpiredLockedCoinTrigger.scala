package com.daml.network.svc.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredLockedCoinTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cc.coin.LockedCoin.Contract,
      cc.coin.LockedCoin.ContractId,
      cc.coin.LockedCoin,
    ](
      store.defaultAcs,
      store.listLockedExpiredCoins,
      cc.coin.LockedCoin.COMPANION,
    ) {

  override protected def completeTask(
      co: ScheduledTaskTrigger.ReadyTask[Contract[
        cc.coin.LockedCoin.ContractId,
        cc.coin.LockedCoin,
      ]]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    domainId <- store.domains.getUniqueDomainId()
    coinRules <- store.getCoinRules()
    latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
    cmd = co.work.contractId
      .exerciseLockedCoin_ExpireCoin(
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
  } yield TaskSuccess(s"archived expired locked coin")
}
