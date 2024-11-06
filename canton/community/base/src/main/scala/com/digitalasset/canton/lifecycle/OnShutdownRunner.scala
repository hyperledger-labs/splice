// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.lifecycle

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.TryUtil.*
import com.google.common.annotations.VisibleForTesting

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.util.Try

trait OnShutdownRunner { this: AutoCloseable =>

  private val status: AtomicReference[OnShutdownRunner.Status] = new AtomicReference(
    OnShutdownRunner.Status.Running
  )

  private val incrementor: AtomicLong = new AtomicLong(0L)
  // CN change: we want to ensure that some tasks (shutting down RetryProviders) happen before others
  private val priorityOnShutdownTasks: TrieMap[Long, RunOnShutdown] =
    TrieMap.empty[Long, RunOnShutdown]
  private val onShutdownTasks: TrieMap[Long, RunOnShutdown] = TrieMap.empty[Long, RunOnShutdown]

  protected def logger: TracedLogger

  /** Check whether we're closing.
    * Susceptible to race conditions; unless you're using this as a flag to the retry lib or you really know
    * what you're doing, prefer `performUnlessClosing` and friends.
    */
  def isClosing: Boolean = status.get() != OnShutdownRunner.Status.Running

  /** Register a task to run when shutdown is initiated.
    *
    * You can use this for example to register tasks that cancel long-running computations,
    * whose termination you can then wait for in "closeAsync".
    */
  def runOnShutdown_[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Unit =
    runOnShutdown(task).discard

  /** Same as [[runOnShutdown_]] but returns a token that allows you to remove the task explicitly from being run
    * using [[cancelShutdownTask]]
    */
  def runOnShutdown[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Long = {
    runOnShutdown(onShutdownTasks, task)
  }

  def runOnShutdownWithPriority_[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Unit = {
    runOnShutdownWithPriority(task).discard
  }

  def runOnShutdownWithPriority[T](
      task: RunOnShutdown
  )(implicit traceContext: TraceContext): Long = {
    runOnShutdown(priorityOnShutdownTasks, task)
  }

  private def runOnShutdown(storage: TrieMap[Long, RunOnShutdown], task: RunOnShutdown)(implicit
      tc: TraceContext
  ) = {
    val token = incrementor.getAndIncrement()
    storage
      // First remove the tasks that are done
      .filterInPlace { case (_, run) =>
        !run.done
      }
      // Then add the new one
      .put(token, task)
      .discard
    if (isClosing) runOnShutdownTasks()
    token
  }

  /** Removes a shutdown task from the list using a token returned by [[runOnShutdown]]
    */
  def cancelShutdownTask(token: Long): Unit = {
    priorityOnShutdownTasks.remove(token).discard
    onShutdownTasks.remove(token).discard
  }
  def containsShutdownTask(token: Long): Boolean = {
    onShutdownTasks.contains(token) || priorityOnShutdownTasks.contains(token)
  }

  private def runOnShutdownTasks()(implicit traceContext: TraceContext): Unit = {
    runOnShutdownTasks(priorityOnShutdownTasks)
    runOnShutdownTasks(onShutdownTasks)
  }

  private def runOnShutdownTasks(
      storage: TrieMap[Long, RunOnShutdown]
  )(implicit traceContext: TraceContext): Unit = {
    storage.toList.foreach { case (token, task) =>
      Try {
        storage
          .remove(token)
          .filterNot(_.done)
          // TODO(#8594) Time limit the shutdown tasks similar to how we time limit the readers in FlagCloseable
          .foreach(_.run())
      }.forFailed(t => logger.warn(s"Task ${task.name} failed on shutdown!", t))
    }
  }

  @VisibleForTesting
  protected def runStateChanged(waitingState: Boolean = false): Unit = {} // used for unit testing

  protected def onFirstClose(): Unit

  /** Blocks until all earlier tasks have completed and then prevents further tasks from being run.
    */
  protected[this] override def close(): Unit = {
    val previousStatus = status.getAndSet(OnShutdownRunner.Status.Closing)
    logger.debug("Initiating shutdown.")(TraceContext.empty)
    runStateChanged()
    if (previousStatus != OnShutdownRunner.Status.Closing) {
      // First run onShutdown tasks.
      // Important to run them in the beginning as they may be used to cancel long-running tasks.
      runOnShutdownTasks()(TraceContext.empty)

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
