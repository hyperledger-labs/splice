package com.daml.network.sv.automation.leaderbased

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TerminatedSubscriptionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      subsCodegen.TerminatedSubscription.ContractId,
      subsCodegen.TerminatedSubscription,
    ](svTaskContext.svcStore, subsCodegen.TerminatedSubscription.COMPANION)
    with SvTaskBasedTrigger[AssignedContract[
      subsCodegen.TerminatedSubscription.ContractId,
      subsCodegen.TerminatedSubscription,
    ]] {

  private val svcParty = svTaskContext.svcStore.key.svcParty

  override def completeTaskAsLeader(
      task: AssignedContract[
        subsCodegen.TerminatedSubscription.ContractId,
        subsCodegen.TerminatedSubscription,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      cnsEntryContextO <- svTaskContext.svcStore.lookupCnsEntryContext(
        task.contract.payload.reference
      )
      _ <- cnsEntryContextO match {
        case None =>
          // TODO(#7934) Log an error here.
          Future.successful(
            TaskSuccess(
              "Ignoring TerminatedSubscription as there is no corresponding CnsEntryContext"
            )
          )
        case Some(cnsEntryContext) =>
          for {
            _ <- svTaskContext.connection
              .submit(
                Seq(svcParty),
                Seq.empty,
                cnsEntryContext.exercise(
                  _.exerciseCnsEntryContext_Terminate(
                    svcParty.toProtoPrimitive,
                    task.contract.contractId,
                  )
                ),
              )
              .withDomainId(task.domain)
              .noDedup
              .yieldUnit()
          } yield ()
      }
    } yield TaskSuccess(
      "Archived CnsEntrytContext because corresponding subscription got terminated"
    )
  }
}
