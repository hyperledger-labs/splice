package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingParallelTaskExecutionTrigger, TaskFailed, TaskOutcome, TaskSuccess, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.FeaturedAppConfigUpdateInstruction
import org.lfdecentralizedtrust.splice.util.AssignedContract
import ExecuteFeaturedAppConfigUpdateInstructionTrigger.*
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import scala.concurrent.{ExecutionContext, Future}

class ExecuteFeaturedAppConfigUpdateInstructionTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SvTaskBasedTrigger[Task] {
  private val store = svTaskContext.dsoStore

  /** Override with how to retrieve the list of tasks that should be processed in parallel right now.
    *
    * The DB queries underlying it SHOULD be efficient enough to run in a loop.
    */
  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = {
    store.listFeaturedAppConfigUpdateInstructionSample()
      .map(_.map(Task(_)))
  }

  override protected def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val provider = PartyId.tryFromProtoPrimitive(task.instruction.payload.provider)
    for {
      featuredAppRight <- store.lookupFeaturedAppRight(provider)
      result <- featuredAppRight match {
        case None => Future.successful(TaskFailed(s"no FeaturedAppRight for provider $provider"))
        case Some(right) => for {
          dsoRules <- store.getDsoRules()
          cmd = dsoRules.exercise(
            _.exerciseDsoRules_ExecuteFeaturedAppConfigUpdateInstruction(
              task.instruction.contractId,
              right.contractId,
              controller
            )
          )
          _ <- svTaskContext
            .connection(SpliceLedgerConnectionPriority.Low)
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .withSynchronizerId(dsoRules.domain)
            .yieldUnit()
        } yield TaskSuccess(s"Executed update for provider $provider")
      }
    } yield result
  }

  /** Check whether a task has become stale and can be skipped.
    *
    * Note that a task can become stale for reasons other than 'completeTask' succeeding,
    * as there can be concurrent actions submitted to the ledger that make the task stale;
    * e.g., another party archiving a contract representing a request.
    */
  override protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(FeaturedAppConfigUpdateInstruction.COMPANION)(task.instruction.contractId)
      .map(_.isEmpty)
}

object ExecuteFeaturedAppConfigUpdateInstructionTrigger {
  final case class Task(
      instruction: AssignedContract[
        FeaturedAppConfigUpdateInstruction.ContractId,
        FeaturedAppConfigUpdateInstruction,
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("instruction", _.instruction.contractId.contractId.singleQuoted))
  }
}
