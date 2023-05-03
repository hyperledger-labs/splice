package com.daml.network.sv.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svcrules.{Confirmation, SvcRules}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.ReadyContract
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireStaleConfirmationsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      Confirmation.ContractId,
      Confirmation,
    ](
      store.multiDomainAcsStore,
      store.listStaleConfirmations,
      Confirmation.COMPANION,
    ) {

  private def performWorkAsLeader(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[Confirmation.ContractId, Confirmation]
      ],
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      cmd = svcRules.contractId.exerciseSvcRules_ExpireStaleConfirmation(
        task.work.contract.contractId
      )
      _ <- connection
        .submitWithResultNoDedup(Seq(store.key.svParty), Seq(store.key.svcParty), cmd, domainId)
    } yield TaskSuccess(
      s"successfully expired the confirmation with cid ${task.work.contract.contractId}"
    )
  }

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[Confirmation.ContractId, Confirmation]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    store
      .lookupSvcRules()
      .flatMap({
        case None =>
          logger.debug("SvcRules contract not found")
          Future.successful(
            TaskSuccess(
              s"ignoring confirmation ${PrettyContractId(task.work.contract)}, SvcRules contract not found"
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
                  s"ignoring confirmation ${PrettyContractId(task.work.contract)}, as we're not the leader"
                )
              )
            })
      })
  }
}
