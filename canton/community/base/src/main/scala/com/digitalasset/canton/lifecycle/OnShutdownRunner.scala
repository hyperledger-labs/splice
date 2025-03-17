// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.lifecycle

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.OnShutdownRunner.RunOnShutdownHandle
import com.digitalasset.canton.logging.{ErrorLoggingContext, TracedLogger}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.TryUtil.*
import com.digitalasset.canton.util.TwoPhasePriorityAccumulator
import com.google.common.annotations.VisibleForTesting

import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

trait OnShutdownRunner { this: AutoCloseable =>

  private val status: AtomicReference[OnShutdownRunner.Status] = new AtomicReference(
    OnShutdownRunner.Status.Running
  )
  private val onShutdownTasks: TwoPhasePriorityAccumulator[RunOnShutdown, Unit] =
    new TwoPhasePriorityAccumulator[RunOnShutdown, Unit](Some(_.done))

  protected def logger: TracedLogger

  /** Check whether we're closing. Susceptible to race conditions; unless you're using this as a
    * flag to the retry lib or you really know what you're doing, prefer `performUnlessClosing` and
    * friends.
    */
  def isClosing: Boolean = status.get() != OnShutdownRunner.Status.Running

  def runOnShutdownWithPriority_(
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Unit = {
    runOnShutdownWithPriority(task, 0).discard
  }

  /** Register a task to run when shutdown is initiated.
    *
    * You can use this for example to register tasks that cancel long-running computations, whose
    * termination you can then wait for in "closeAsync".
    */
  def runOnShutdown_[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Unit =
    runOnShutdown(task).discard

  /** Same as [[runOnShutdown_]] but returns a token that allows you to remove the task explicitly
    * from being run using
    * [[com.digitalasset.canton.lifecycle.OnShutdownRunner.RunOnShutdownHandle.cancel]]
    */
  def runOnShutdown[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): RunOnShutdownHandle = {
    val priority = 10
    runOnShutdownWithPriority(task, priority)
  }

  private def runOnShutdownWithPriority[T](task: RunOnShutdown, priority: Int)(implicit
      traceContext: TraceContext
  ) = {
    onShutdownTasks.accumulate(task, priority) match {
      case Right(handle) =>
        new RunOnShutdownHandle.ProxyItemHandle(handle)
      case Left(_) =>
        runTaskUnlessDone(task)
        RunOnShutdownHandle.dummy
    }
  }

  private def runTaskUnlessDone(task: RunOnShutdown)(implicit traceContext: TraceContext): Unit =
    Try {
      // TODO(#8594) Time limit the shutdown tasks similar to how we time limit the readers in FlagCloseable
      if (!task.done) task.run()
    }.forFailed(t => logger.warn(s"Task ${task.name} failed on shutdown!", t))

  private def runOnShutdownTasks()(implicit traceContext: TraceContext): Unit = {
    implicit val errorLoggingContext: ErrorLoggingContext =
      ErrorLoggingContext.fromTracedLogger(logger)
    onShutdownTasks.drain().foreach { case (task, _) => runTaskUnlessDone(task) }
  }

  @VisibleForTesting
  protected def runStateChanged(waitingState: Boolean = false): Unit = {} // used for unit testing

  protected def onFirstClose(): Unit

  /** Blocks until all earlier tasks have completed and then prevents further tasks from being run.
    */
  protected[this] override def close(): Unit = {
    import TraceContext.Implicits.Empty.*
    status.set(OnShutdownRunner.Status.Closing)
    logger.debug("Initiating shutdown.")(TraceContext.empty)

    val firstCallToClose = onShutdownTasks.stopAccumulating(()).isEmpty
    runStateChanged()
    if (firstCallToClose) {
      // First run onShutdown tasks.
      // Important to run them in the beginning as they may be used to cancel long-running tasks.
      runOnShutdownTasks()

      onFirstClose()
    } else {
      // TODO(i8594): Ensure we call close only once
    }
  }

  /** Marks this instance as closing, but does *not* initiate shutdown tasks.
    * Used to mark all CN RetryProviders as closing before cancelling any running tasks,
    * thus avoiding any spurious warns/errors that may happen due to cancellation exceptions being thrown.
    */
  def setAsClosing(): Unit = {
    logger.debug("Setting as closing.")(TraceContext.empty)
    status.set(OnShutdownRunner.Status.SetAsClosing)
  }
}

object OnShutdownRunner {

  sealed trait RunOnShutdownHandle {
    def cancel(): Unit
    def isScheduled: Boolean
  }
  object RunOnShutdownHandle {
    private[OnShutdownRunner] object dummy extends RunOnShutdownHandle {
      override def cancel(): Unit = ()
      override def isScheduled: Boolean = false
    }

    private[OnShutdownRunner] final class ProxyItemHandle(
        handle: TwoPhasePriorityAccumulator.ItemHandle
    ) extends RunOnShutdownHandle {
      override def cancel(): Unit = handle.remove().discard[Boolean]

      override def isScheduled: Boolean = handle.accumulated
    }
  }

  /** A closeable container for managing [[RunOnShutdown]] tasks and nothing else. */
  class PureOnShutdownRunner(override protected val logger: TracedLogger)
      extends AutoCloseable
      with OnShutdownRunner {
    override protected def onFirstClose(): Unit = ()
    override def close(): Unit = super.close()
  }

  private sealed trait Status
  private object Status {
    case object Running extends Status
    case object SetAsClosing extends Status
    case object Closing extends Status
  }
}

/** Trait that can be registered with a [FlagCloseable] to run on shutdown */
trait RunOnShutdown {

  /** the name, used for logging during shutdown */
  def name: String

  /** true if the task has already run (maybe elsewhere) */
  def done: Boolean

  /** invoked by [FlagCloseable] during shutdown */
  def run(): Unit
}
