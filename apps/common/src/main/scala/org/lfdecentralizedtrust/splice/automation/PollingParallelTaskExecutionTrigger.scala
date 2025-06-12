// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}

import scala.concurrent.{ExecutionContext, Future}

/** A trigger that regularly polls for new tasks and executes them in parallel.
  *
  * Unless you implement an one-off trigger, you likely want to write or use a specialization of this trigger as a base
  * trigger implementation.
  */
abstract class PollingParallelTaskExecutionTrigger[T: Pretty]()(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends TaskbasedTrigger[T]
    with PollingTrigger {

  /** Override with how to retrieve the list of tasks that should be processed in parallel right now.
    *
    * The DB queries underlying it SHOULD be efficient enough to run in a loop.
    */
  // TODO(M3-83): consider retrieving a Source of tasks so that the Source can run down an index and thus avoid
  // expensive queries in Postgres due to having to skip deleted, but not yet VACUUMED tuples!
  // This can be done using the seek method from https://use-the-index-luke.com/sql/partial-results/fetch-next-page
  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[T]]

  /** Returns whether some useful work was done, i.e., at least one task completed. */
  // TODO(DACH-NY/canton-network-internal#495): Reconsider/adapt this logic w.r.t. SV task-based triggers that can busy-loop for followers.
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      tasks <- retrieveTasks()
      // TODO(M3-83): review our triggers for whether the task retrieval for time-based triggers performs sufficiently well
      // TODO(M3-83): consider building support for batching the commands resulting from the different tasks
      tasksSucceeded <- Source(tasks)
        .mapAsyncUnordered(context.config.parallelism)(processTaskWithRetry)
        .runWith(Sink.seq)
    } yield tasksSucceeded.exists(succeeded => succeeded)

  override def runOnce()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      tasks <- retrieveTasks()
      workDone <- tasks.headOption match {
        case None => Future.successful(false)
        case Some(task) => processTaskWithRetry(task)
      }
    } yield workDone
  }
}
