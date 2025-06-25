// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.Timed
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.InitializationTrigger.InitializationTriggerState

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success}

/** A trigger that executes a background initialization task that should be run once at application startup.
  *
  * The trigger is intended to behave like a synchronous initialization task,
  * meaning it should retry the task for a while, but then give up if the task cannot be completed.
  * If the task fails, the trigger (and thus the whole application) will be put into a permanently unhealthy state,
  * and we'll rely on the deployment/monitoring system to restart the application.
  *
  * The `complete()` method is called exactly once, it is the responsibility of the trigger implementation
  * to implement appropriate retries.
  */
abstract class InitializationTrigger()(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends Trigger
    with FlagCloseableAsync {
  private implicit val mc: MetricsContext = MetricsContext(
    "trigger_name" -> this.getClass.getSimpleName,
    "trigger_type" -> "initialization",
  ).withExtraLabels(extraMetricLabels*)

  /** Completes the one-time initialization task.
    *
    * This method is not retried automatically; it is the responsibility of the trigger implementation
    * to handle retries.
    */
  def complete()(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit]

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  private val stateRef =
    new AtomicReference[InitializationTriggerState](InitializationTriggerState.Initial)
  private val startedPromise = Promise[UnlessShutdown[Unit]]()
  private val finishedPromise = Promise[UnlessShutdown[Unit]]()

  // If this one-time task fails, it will put the trigger (and thus the whole application) into a permanently unhealthy state.
  override def isHealthy: Boolean = stateRef.get() != InitializationTriggerState.Failed

  override def run(paused: Boolean): Unit = LoggerUtil.logOnThrow {
    if (!paused) {
      startedPromise.trySuccess(UnlessShutdown.unit).discard
    }

    // We create a top-level tid for the trigger polling loop for ease of navigation in lnav using 'o' and 'O'
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      {
        (for {
          _ <- FutureUnlessShutdown(startedPromise.future)
          _ = metrics.attempted.mark()
          _ = stateRef.set(InitializationTriggerState.Running)
          _ = logger.info("Initialization task starting.")
          _ <- Timed.future(metrics.latency, complete())
        } yield ())
          .onComplete {
            case Success(_) =>
              logger.info("Initialization task completed successfully.")
              stateRef.set(InitializationTriggerState.Completed)
              metrics.completed.mark()
              finishedPromise.complete(Success(UnlessShutdown.unit))
            case Failure(exception) =>
              logger.error("Initialization task failed.", exception)
              stateRef.set(InitializationTriggerState.Failed)
              metrics.completed.mark()
              finishedPromise.complete(Success(UnlessShutdown.unit))
          }

      }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      SyncCloseable(
        "promise blocking trigger body",
        startedPromise.trySuccess(UnlessShutdown.AbortedDueToShutdown).discard,
      ),
      AsyncCloseable(
        "initialization trigger body",
        finishedPromise.future,
        NonNegativeDuration.tryFromDuration(timeouts.shutdownNetwork.duration),
      ),
    )

  override def pause(): Future[Unit] = {
    Future.failed(
      new UnsupportedOperationException(
        "InitializationTrigger cannot be paused interactively. It can only be started in a paused state."
      )
    )
  }

  override def resume(): Unit = blocking {
    assert(!startedPromise.isCompleted, "InitializationTrigger is already started.")
    startedPromise.trySuccess(UnlessShutdown.unit).discard
  }

  def finished: FutureUnlessShutdown[Unit] = {
    FutureUnlessShutdown(finishedPromise.future)
  }
}

object InitializationTrigger {

  sealed trait InitializationTriggerState
  object InitializationTriggerState {
    final case object Initial extends InitializationTriggerState
    final case object Running extends InitializationTriggerState
    final case object Completed extends InitializationTriggerState
    final case object Failed extends InitializationTriggerState
  }

  private case class ConfigSummary(config: AutomationConfig) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("pollingInterval", _.config.pollingInterval),
        param("pollingJitter", _.config.pollingJitter.toString.unquoted),
      )
    }
  }
}
