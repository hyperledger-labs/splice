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
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireIssuingMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      cc.round.IssuingMiningRound.ContractId,
      cc.round.IssuingMiningRound,
    ](
      svTaskContext.svcStore.multiDomainAcsStore,
      svTaskContext.svcStore.listExpiredIssuingMiningRounds,
      cc.round.IssuingMiningRound.COMPANION,
    )
    with SvTaskBasedTrigger[
      ScheduledTaskTrigger.ReadyTask[ReadyContract[
        cc.round.IssuingMiningRound.ContractId,
        cc.round.IssuingMiningRound,
      ]]
    ] {

  val store = svTaskContext.svcStore

  override protected def completeTaskAsLeader(
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.work
    for {
      svcRules <- store.getSvcRules()
      coinRules <- store.getCoinRules()
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      cmd = svcRules.contractId.exerciseSvcRules_MiningRound_Close(
        coinRules.contractId,
        round.contract.contractId,
      )
      cid <- svTaskContext.connection
        .submitWithResultNoDedup(Seq(store.key.svParty), Seq(store.key.svcParty), cmd, domainId)
    } yield TaskSuccess(s"successfully created the closed mining round with cid $cid")
  }

  override protected def completeTaskAsFollower(
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[IssuingMiningRound.ContractId, IssuingMiningRound]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    Future.successful(
      TaskSuccess(
        s"ignoring mining round ${PrettyContractId(task.work.contract)}, as we're not the leader"
      )
    )
}
