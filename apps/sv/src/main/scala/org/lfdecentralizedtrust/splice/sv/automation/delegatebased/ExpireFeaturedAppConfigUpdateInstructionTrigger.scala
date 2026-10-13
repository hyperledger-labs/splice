package org.lfdecentralizedtrust.splice.sv.automation.delegatebased

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{MultiDomainExpiredContractTrigger, ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.instruction.FeaturedAppConfigUpdateInstruction
import org.lfdecentralizedtrust.splice.util.AssignedContract
import ExpireFeaturedAppConfigUpdateInstructionTrigger.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}

class ExpireFeaturedAppConfigUpdateInstructionTrigger(
 override protected val context: TriggerContext,
 override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit override val ec: ExecutionContext, mat: Materializer, tracer: Tracer) extends MultiDomainExpiredContractTrigger.Template[
  FeaturedAppConfigUpdateInstruction.ContractId,
  FeaturedAppConfigUpdateInstruction,
](
  svTaskContext.dsoStore.multiDomainAcsStore,
  svTaskContext.dsoStore.listExpiredFeaturedAppConfigUpdateInstruction,
  FeaturedAppConfigUpdateInstruction.COMPANION,
)
  with SvTaskBasedTrigger[Task] {

  private val store = svTaskContext.dsoStore

  override protected def completeTaskAsDsoDelegate(task: Task, controller: String)(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ExpireFeaturedAppConfigUpdateInstruction(task.work.contractId, controller))
      _ <- svTaskContext.connection(SpliceLedgerConnectionPriority.Low)
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup.yieldUnit()
    } yield TaskSuccess("expired featured app config update instruction")
  }
}

object ExpireFeaturedAppConfigUpdateInstructionTrigger {
  type Task = ScheduledTaskTrigger.ReadyTask[
    AssignedContract[
      FeaturedAppConfigUpdateInstruction.ContractId,
      FeaturedAppConfigUpdateInstruction,
    ]
  ]
}
