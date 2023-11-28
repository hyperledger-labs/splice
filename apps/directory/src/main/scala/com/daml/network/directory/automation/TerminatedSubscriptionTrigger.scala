package com.daml.network.directory.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.store.MultiDomainAcsStore.ContractState.Assigned
import com.daml.network.util.{AssignedContract, ContractWithState}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TerminatedSubscriptionTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      subsCodegen.TerminatedSubscription.ContractId,
      subsCodegen.TerminatedSubscription,
    ](store, subsCodegen.TerminatedSubscription.COMPANION) {

  override def completeTask(
      task: AssignedContract[
        subsCodegen.TerminatedSubscription.ContractId,
        subsCodegen.TerminatedSubscription,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      directoryEntryContextO <- store.lookupDirectoryEntryContext(task.contract.payload.reference)
      _ <- directoryEntryContextO match {
        case None =>
          // TODO(#7934) Log an error here.
          Future.successful(
            TaskSuccess(
              "Ignoring TerminatedSubscription as there is no corresponding DirectoryEntryContext"
            )
          )
        case Some(ContractWithState(_, decState)) if decState != Assigned(task.domain) =>
          Future failed Status.FAILED_PRECONDITION
            .withDescription(
              s"DirectoryEntryContext in state $decState, does not match TerminatedSubscription"
            )
            .asRuntimeException()
        case Some(directoryEntryContext) =>
          connection
            .submit(
              Seq(store.providerParty),
              Seq.empty,
              task.exercise { taskContractId =>
                directoryEntryContext.contractId.exerciseDirectoryEntryContext_Terminate(
                  store.providerParty.toProtoPrimitive,
                  taskContractId,
                )
              },
            )
            .noDedup
            .yieldUnit()
      }
    } yield TaskSuccess(
      "Archived DirectoryEntryContext because corresponding subscription got terminated"
    )
  }
}
