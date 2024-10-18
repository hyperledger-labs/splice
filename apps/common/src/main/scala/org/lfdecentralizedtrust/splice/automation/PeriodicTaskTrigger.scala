// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger that should periodically execute some work.
  *
  * The work is is represented as a task, which is expected to always successfully complete.
  * If your task has preconditions that might not be met, consider using a [[ScheduledTaskTrigger]]
  * or [[PollingParallelTaskExecutionTrigger]] instead.
  */
abstract class PeriodicTaskTrigger(
    executionInterval: NonNegativeFiniteDuration,
    triggerContext: TriggerContext,
    quiet: Boolean = false,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends TaskbasedTrigger[PeriodicTaskTrigger.PeriodicTask](quiet = quiet)
    with PollingTrigger {

  def this(context: TriggerContext, quiet: Boolean)(implicit ec: ExecutionContext, tracer: Tracer) =
    this(context.config.pollingInterval, context, quiet)(ec, tracer)

  override protected def context: TriggerContext = triggerContext.copy(
    config = triggerContext.config.copy(
      pollingInterval = executionInterval
    )
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    processTaskWithRetry(PeriodicTaskTrigger.PeriodicTask(context.clock.now))
      .map(_ => false)
  }

  override def isStaleTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit tc: TraceContext): Future[Boolean] = {
    // Periodic tasks are never stale, as they have no precondition for their execution.
    Future.successful(false)
  }

}

object PeriodicTaskTrigger {
  case class PeriodicTask(
      now: CantonTimestamp
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = {
      prettyOfClass(param("for", _.now))
    }
  }
}
