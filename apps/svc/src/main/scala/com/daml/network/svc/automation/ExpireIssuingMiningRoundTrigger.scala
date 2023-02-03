package com.daml.network.svc.automation

import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      cc.round.IssuingMiningRound.Contract,
      cc.round.IssuingMiningRound.ContractId,
      cc.round.IssuingMiningRound,
    ](
      store.acs,
      store.listExpiredIssuingMiningRounds,
      cc.round.IssuingMiningRound.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        Contract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    for {
      coinRules <- store.getCoinRules()
      domainId <- store.domains.getUniqueDomainId()
      cmd = coinRules.contractId.exerciseCoinRules_MiningRound_Close(round.contractId)
      cid <- connection
        .submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd, domainId)
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }
}
