package com.daml.network.splitwell.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TerminatedAppPaymentTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      walletCodegen.TerminatedAppPayment.ContractId,
      walletCodegen.TerminatedAppPayment,
    ](store, walletCodegen.TerminatedAppPayment.COMPANION) {

  override def completeTask(
      task: AssignedContract[
        walletCodegen.TerminatedAppPayment.ContractId,
        walletCodegen.TerminatedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      transferInProgressO <- store.lookupTransferInProgress(task.contract.payload.reference)
      _ <- transferInProgressO.value match {
        case None =>
          throw Status.INTERNAL
            .withDescription("No corresponding TransferInProgress for TerminatedAppPayment")
            .asRuntimeException()
        case Some(transferInProgress) =>
          for {
            _ <- connection
              .submit(
                Seq(store.providerParty),
                Seq.empty,
                transferInProgress.contractId.exerciseTransferInProgress_Terminate(
                  store.providerParty.toProtoPrimitive,
                  task.contract.contractId,
                ),
              )
              .withDomainId(task.domain)
              .noDedup
              .yieldUnit()
          } yield ()
      }
    } yield TaskSuccess("Archived TransferInProgress because croresponding payment got terminated")
  }
}
