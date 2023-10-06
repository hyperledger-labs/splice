package com.daml.network.splitwell.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class StaleTransferInProgressTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[AssignedContract[
      splitwellCodegen.TransferInProgress.ContractId,
      splitwellCodegen.TransferInProgress,
    ]](
    ) {

  override def retrieveTasks()(implicit tc: TraceContext) =
    store.listStaleTransferInProgress()

  override def completeTask(
      task: AssignedContract[
        splitwellCodegen.TransferInProgress.ContractId,
        splitwellCodegen.TransferInProgress,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      _ <- connection
        .submit(
          Seq(store.providerParty),
          Seq.empty,
          task.contract.contractId.exerciseTransferInProgress_Abort(
            store.providerParty.toProtoPrimitive
          ),
        )
        .withDomainId(task.domain)
        .noDedup
        .yieldUnit()
    } yield TaskSuccess("Archived TransferInProgress because payment request was archived")
  }

  override def isStaleTask(
      task: AssignedContract[
        splitwellCodegen.TransferInProgress.ContractId,
        splitwellCodegen.TransferInProgress,
      ]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(splitwellCodegen.TransferInProgress.COMPANION)(
        task.domain,
        task.contract.contractId,
      )
      .map(_.isEmpty)
}
