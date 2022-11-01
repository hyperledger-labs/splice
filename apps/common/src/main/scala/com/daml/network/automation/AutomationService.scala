package com.daml.network.automation

import akka.NotUsed
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.daml.network.environment.CoinRetries
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{NoTracing, Spanning, TraceContext}
import com.digitalasset.canton.util.AkkaUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(retryProvider: CoinRetries)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private[this] val automationServices: AtomicReference[Seq[AutoCloseable]] = new AtomicReference(
    Seq.empty
  )

  /** Register a service to orchestrate, which currently means ensuring its prompt closure. */
  final protected def registerService(service: AutoCloseable): Unit = {
    val _ = automationServices.getAndUpdate(_.prepended(service))
    ()
  }

  /** Register a task handler driven from a source of tasks. */
  final protected def registerRequestHandler[T <: PrettyPrinting](
      name: String,
      source: Source[T, NotUsed],
  )(
      handler0: T => TraceContext => Future[String]
  ): Unit = {
    def handler(req: T): Future[Unit] = {
      lazy val prettiedReq = (req: PrettyPrinting).toString
      withNewTrace(name) { implicit traceContext => _ =>
        // TODO(#790): try out switching to show"my $prettyReq" interpolator
        logger.info(s"Running operation ${name.singleQuoted} for\n$prettiedReq")
        // TODO(#790): retryProvider should take an explicit logger as the argument to provide better precision as to who initiated the logging
        retryProvider
          .retryForAutomation(
            name,
            performUnlessClosingF(name)(handler0(req)(traceContext)),
            flagCloseable = this,
          )
          .onShutdown("aborted due to shutdown")
          .transform {
            case Success(outcome) =>
              logger.info(s"Completed operation ${name.singleQuoted} with outcome: $outcome")
              Success(())
            case Failure(ex) =>
              logger.error(
                s"Operation ${name.singleQuoted} failed fatally and is skipped due to",
                ex,
              )
              // Here we recover from the failure so that processing can continue for other tasks.
              Success(())
          }
      }
    }
    val service = new AutomationService.TaskHandlerService[T](
      name,
      source,
      handler,
      loggerFactory,
      timeouts,
    )
    registerService(service)
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Directory automation services",
        Lifecycle.close(automationServices.get(): _*)(logger),
      )
    )
}

object AutomationService {
  private class TaskHandlerService[T](
      name: String,
      source: Source[T, NotUsed],
      processTask: T => Future[Unit],
      override protected val loggerFactory: NamedLoggerFactory,
      override val timeouts: ProcessingTimeout,
  )(implicit
      mat: Materializer
  ) extends FlagCloseableAsync
      // TODO(#790): review logging
      with NamedLogging
      with NoTracing {

    val (killSwitch, completed) = AkkaUtil.runSupervised(
      logger.error("Fatally failed to handle task", _),
      source
        // we place the kill switch before the map operator, such that
        // we can shut down the operator quickly and signal upstream to cancel further sending
        .viaMat(KillSwitches.single)(Keep.right)
        // TODO(#790): make this configurable
        .mapAsync(4)(processTask)
        // and we get the Future[Done] as completed from the sink so we know when the last message
        // was processed
        .toMat(Sink.ignore)(Keep.both),
    )

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      Seq[AsyncOrSyncCloseable](
        SyncCloseable(s"killSwitch.shutdown $name", killSwitch.shutdown()),
        AsyncCloseable(
          s"graph.completed $name",
          completed,
          timeouts.shutdownShort.unwrap,
        ),
      )
    }
  }
}
