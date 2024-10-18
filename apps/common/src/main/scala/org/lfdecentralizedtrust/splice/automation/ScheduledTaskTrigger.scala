// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger for scheduling tasks that only become ready after some future date. */
abstract class ScheduledTaskTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ScheduledTaskTrigger.ReadyTask[T]] {

  /** Retrieve a list of tasks that are ready for execution now. */
  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[T]]

  override final protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ScheduledTaskTrigger.ReadyTask[T]]] = {
    // We shift the clock-reading by a small grace period to account for potential clock skew
    val now = context.clock.now.minus(context.config.clockSkewAutomationDelay.asJava)
    // TODO(M3-83): review whether we should introduce a separate task list size parameter
    listReadyTasks(now, context.config.parallelism)
      .map(_.map(ScheduledTaskTrigger.ReadyTask(now, _)))
  }

}

object ScheduledTaskTrigger {
  case class ReadyTask[T: Pretty](
      readyAt: CantonTimestamp,
      work: T,
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = {
      prettyOfClass(param("readyAt", _.readyAt), param("work", _.work))
    }
  }
}
