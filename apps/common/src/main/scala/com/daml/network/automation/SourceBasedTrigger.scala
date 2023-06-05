package com.daml.network.automation

import akka.{Done, NotUsed}
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.AkkaUtil
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{blocking, ExecutionContext, Future}

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

  // When node-level shutdown is initiated, we need to kill the akka source.
  context.retryProvider.runOnShutdown(new RunOnShutdown {
    override def name: String = s"terminate source processing loop"
    override def done: Boolean = executionHandleRef.get().exists(_.completed.isCompleted)
    override def run(): Unit =
      executionHandleRef
        .get()
        .foreach(handle => {
          logger.debug("Terminating source processing loop, as we are shutting down.")(
            TraceContext.empty
          )
          handle.killSwitch.shutdown()
        })
  })(TraceContext.empty)

  override def run(): Unit = blocking {
    // Using synchronized here, as we otherwise have to write cleanup code for recovering from a concurrent call
    synchronized {
      withNewTrace("run processing loop")(implicit tc =>
        _ => {
          def go(task: T): Future[Unit] = processTaskWithRetry(task).map(_ =>
            // ignoring the return value here, as we don't care anymore about whether the task was successful or not
            ()
          )

          require(executionHandleRef.get().isEmpty, "run was called twice")
          logger.debug("Starting source processing loop")
          val (killSwitch: UniqueKillSwitch, completed0: Future[Done]) = AkkaUtil.runSupervised(
            logger.error("Fatally failed to handle task", _),
            source
              .viaMat(KillSwitches.single)(Keep.right)
              .toMat(Sink.foreachAsync[T](context.config.parallelism)(go))(
                Keep.both
              ),
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
        timeouts.shutdownShort.unwrap,
      )
    )
  }

}
