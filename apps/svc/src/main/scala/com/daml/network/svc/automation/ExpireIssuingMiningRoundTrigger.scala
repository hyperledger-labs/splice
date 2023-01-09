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
import com.daml.network.util.JavaContract
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
        JavaContract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    val totals = store.getTotalsForRound(round.payload.round.number)
    for {
      coinRules <- store.getCoinRules()
      cmd = coinRules.contractId
        .exerciseCoinRules_MiningRound_Close(
          round.contractId,
          totals.transferFees.bigDecimal,
          totals.adminFees.bigDecimal,
          totals.holdingFees.bigDecimal,
          totals.transferInputs.bigDecimal,
          totals.nonSelfTransferOutputs.bigDecimal,
          totals.selfTransferOutputs.bigDecimal,
        )
      cid <- connection
        .submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }
}
