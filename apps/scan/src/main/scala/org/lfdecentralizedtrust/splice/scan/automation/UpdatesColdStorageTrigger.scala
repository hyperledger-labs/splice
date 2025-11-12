package org.lfdecentralizedtrust.splice.scan.automation

import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{PollingParallelTaskExecutionTrigger, TaskOutcome, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore

import scala.concurrent.{ExecutionContext, Future}

class UpdatesColdStorageTrigger(
                        acsSnapshotStore: AcsSnapshotStore,
                        override protected val context: TriggerContext,
                        )(implicit
                          ec: ExecutionContext,
                          tracer: Tracer,
                          mat: Materializer,
                          // we always return 1 task, so PollingParallelTaskExecutionTrigger in effect does nothing in parallel
                        )extends PollingParallelTaskExecutionTrigger[UpdatesColdStorageTrigger.Task] {
  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[UpdatesColdStorageTrigger.Task]] = ???

  override protected def completeTask(task: UpdatesColdStorageTrigger.Task)(implicit tc: TraceContext): Future[TaskOutcome] = ???

  override protected def isStaleTask(task: UpdatesColdStorageTrigger.Task)(implicit tc: TraceContext): Future[Boolean] = ???
}
object UpdatesColdStorageTrigger {
  case class Task() extends PrettyPrinting {
    override protected def pretty: Pretty[Task.this.type] = ???
  }
}
