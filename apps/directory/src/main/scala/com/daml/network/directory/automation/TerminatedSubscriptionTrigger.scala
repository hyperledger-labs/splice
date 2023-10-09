package com.daml.network.directory.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
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
        case Some(directoryEntryContext) =>
          for {
            _ <- connection
              .submit(
                Seq(store.providerParty),
                Seq.empty,
                directoryEntryContext.contractId.exerciseDirectoryEntryContext_Terminate(
                  store.providerParty.toProtoPrimitive,
                  task.contract.contractId,
                ),
              )
              .withDomainId(task.domain)
              .noDedup
              .yieldUnit()
          } yield ()
      }
    } yield TaskSuccess(
      "Archived DirectoryEntrytContext because corresponding subscription got terminated"
    )
  }
}
