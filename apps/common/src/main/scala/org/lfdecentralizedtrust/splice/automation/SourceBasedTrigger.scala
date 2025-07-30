// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.PekkoUtil
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

/** A trigger receiving its tasks via an Akka source. */
abstract class SourceBasedTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends TaskbasedTrigger[T]
    with FlagCloseableAsync {

  /** The source from which to consume tasks. */
  protected def source(implicit traceContext: TraceContext): Source[T, NotUsed]

  private implicit val elc: ErrorLoggingContext =
    ErrorLoggingContext(logger, Map.empty, TraceContext.empty)

  private case class ExecutionHandle(killSwitch: UniqueKillSwitch, completed: Future[Done])

  private[this] val executionHandleRef: AtomicReference[Option[ExecutionHandle]] =
    new AtomicReference(None)

  private[this] val pauseControl = new PausableStreamControl()

  // When node-level shutdown is initiated, we need to kill the akka source.
  context.retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name: String = s"terminate source processing loop"
    override def done: Boolean = executionHandleRef.get().exists(_.completed.isCompleted)
    override def run()(implicit tc: TraceContext): Unit =
      executionHandleRef
        .get()
        .foreach(handle => {
          logger.debug("Terminating source processing loop, as we are shutting down.")(
            TraceContext.empty
          )
          handle.killSwitch.shutdown()
        })
  })(TraceContext.empty)

  override def run(paused: Boolean): Unit = blocking {
    // Using synchronized here, as we otherwise have to write cleanup code for recovering from a concurrent call
    synchronized {
      withNewTrace("run processing loop")(implicit tc =>
        _ => {

          def go(task: T): Future[Unit] = processTaskWithRetry(task).map(_ =>
            // ignoring the return value here, as we don't care anymore about whether the task was successful or not
            ()
          )
          require(executionHandleRef.get().isEmpty, "run was called twice")
          val pausedOnStart = if (paused) {
            pauseControl.pauseOnStart()
          } else Future.successful(())

          logger.debug(
            s"Starting source processing loop with parallelism ${context.config.parallelism}"
          )
          val (killSwitch: UniqueKillSwitch, completed0: Future[Done]) = PekkoUtil.runSupervised(
            source
              .mapAsync(1)(task => pausedOnStart.map(_ => task))
              .via(pauseControl.flow)
              .viaMat(KillSwitches.single)(Keep.right)
              .toMat(Sink.foreachAsync[T](context.config.parallelism)(go))(
                Keep.both
              ),
            errorLogMessagePrefix = "Fatally failed to handle task",
          )
          val completed = completed0.transform(
            context.retryProvider
              .logTerminationAndRecoverOnShutdown("source processing loop", logger)
          )
          executionHandleRef.set(Some(ExecutionHandle(killSwitch, completed)))
          // Beware: the termination signal might have arrived before setting the reference above
          if (context.retryProvider.isClosing) {
            logger.debug(
              "Detected race of shutdown signal with setup of source processing loop: triggering termination now."
            )
            killSwitch.shutdown()
          }
        }
      )
    }
  }

  override def isHealthy: Boolean = executionHandleRef.get().exists(!_.completed.isCompleted)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq[AsyncOrSyncCloseable](
      AsyncCloseable(
        "waiting for termination of source processing loop",
        executionHandleRef.get().fold(Future.successful(Done.done()))(_.completed),
        timeouts.shutdownProcessing,
      )
    )
  }

  override def pause(): Future[Unit] = withNewTrace(this.getClass.getSimpleName) {
    implicit traceContext => _ =>
      logger.info("Pausing trigger.")
      pauseControl.pause()
  }

  override def resume(): Unit = withNewTrace(this.getClass.getSimpleName) {
    implicit traceContext => _ =>
      logger.info("Resuming trigger.")
      pauseControl.resume()
  }
}

class PausableStreamControl {
  private val pausePromise = Promise[Unit]()
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile private var paused = false

  // pause before the first task element is processed, which completes immediately
  def pauseOnStart(): Future[Unit] = {
    paused = true
    Future.successful()
  }
  // pause after the first element is processed, which completes when at least one element is received from the source since pausing
  // returns a Future that completes when a task element is received
  def pause(): Future[Unit] = {
    paused = true
    pausePromise.future
  }

  def resume(): Unit = {
    paused = false
    if (!pausePromise.isCompleted)
      pausePromise.success(())
  }

  def flow[T] = Flow[T].statefulMapConcat { () =>
    val buffer = mutable.Queue[T]()

    { elem =>
      if (paused) {
        buffer.enqueue(elem)
        if (!pausePromise.isCompleted) {
          pausePromise.success(())
        }
        Nil
      } else {
        if (buffer.nonEmpty) {
          val toEmit = buffer.dequeueAll(_ => true) :+ elem
          toEmit
        } else {
          List(elem)
        }
      }
    }
  }
}
