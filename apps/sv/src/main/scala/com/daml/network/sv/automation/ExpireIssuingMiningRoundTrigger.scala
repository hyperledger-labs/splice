package com.daml.network.sv.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.round.IssuingMiningRound.ContractId,
      cc.round.IssuingMiningRound,
    ](
      store.multiDomainAcsStore,
      store.listExpiredIssuingMiningRounds,
      cc.round.IssuingMiningRound.COMPANION,
    ) {

  private def performWorkAsLeader(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    for {
      coinRules <- store.getCoinRules()
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      cmd = svcRules.contractId.exerciseSvcRules_MiningRound_Close(
        coinRules.contractId,
        round.contract.contractId,
      )
      cid <- connection
        .submitWithResultNoDedup(Seq(store.key.svParty), Seq(store.key.svcParty), cmd, domainId)
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    store
      .lookupSvcRules()
      .flatMap({
        case None =>
          logger.warn(
            "Unexpected ledger state: there is an IssuingMiningRound to expire, but no SvcRules contract."
          )
          Future.successful(
            TaskSuccess(
              s"ignoring mining round ${PrettyContractId(task.work.contract)}, SvcRules contract not found"
            )
          )
        case Some(svcRules) =>
          store
            .svIsLeader()
            .flatMap(if (_) {
              performWorkAsLeader(svcRules, task)
            } else {
              Future.successful(
                TaskSuccess(
                  s"ignoring mining round ${PrettyContractId(task.work.contract)}, as we're not the leader"
                )
              )
            })
      })
  }
}
