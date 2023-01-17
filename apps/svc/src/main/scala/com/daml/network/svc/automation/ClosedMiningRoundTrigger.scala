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
import scala.jdk.CollectionConverters.*

class ClosedMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      cc.round.ClosedMiningRound.Contract,
      cc.round.ClosedMiningRound.ContractId,
      cc.round.ClosedMiningRound,
    ](store.acs, cc.round.ClosedMiningRound.COMPANION) {

  override def completeTask(
      closedRound: JavaContract[
        cc.round.ClosedMiningRound.ContractId,
        cc.round.ClosedMiningRound,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      coinRules <- store.getCoinRules()
      domainId <- store.domains.getUniqueDomainId()
      // TODO(M3-06): claim unclaimed rewards
      cmd = coinRules.contractId
        .exerciseCoinRules_MiningRound_Archive(
          closedRound.contractId
        )
        .commands
        .asScala
        .toSeq
      _ <-
        connection.submitCommandsNoDedup(Seq(store.svcParty), Seq.empty, cmd, domainId)
    } yield TaskSuccess(s"successfully archived closed mining round $closedRound")
  }

}
