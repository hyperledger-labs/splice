package com.daml.network.automation

import akka.NotUsed
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{NoTracing, Spanning, TraceContext}
import com.digitalasset.canton.util.AkkaUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private[this] val automationServices: AtomicReference[Seq[AutoCloseable]] = new AtomicReference(
    Seq.empty
  )

  /** Register a resource that should be promptly closed when closing the automation service. */
  final protected def registerResource[T <: AutoCloseable](resource: T): T = {
    val _ = automationServices.getAndUpdate(_.prepended(resource))
    resource
  }

  /** Special case of 'registerResource' for registering a service that
    * runs in the background and should be promptly closed when closing the automation service.
    *
    * Kept separate from 'registerResource' to simplify calling it, and to prepare for future
    * extensions where extra information about a registered service is tracked by the automation service.
    */
  final protected def registerService(service: AutoCloseable): Unit = {
    val _ = registerResource(service)
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
      automationConfig,
      name,
      source,
      handler,
      loggerFactory,
      timeouts,
    )
    registerService(service)
  }

  /** Register a task handler invoked periodically with the current (ledger) time.
    *  When Canton runs with wall-clock time, the handler is triggered on each `interval`.
    *  When Canton runs with simulation time, the handler is *instead* triggered
    *  every time the ledger time changes.
    */
  final protected def registerTimeHandler(
      name: String,
      interval: FiniteDuration,
      connection: CoinLedgerConnection, // for querying ledger time when simtime is used
  )(
      handler0: CantonTimestamp => TraceContext => Future[String]
  ): Unit = {
    withNewTrace("getTime") { implicit traceContext => _ =>
      {
        val timeSource = clockConfig match {
          case ClockConfig.SimClock => {
            logger.info(
              "Running in SimClock mode; will invoke handler based on time service."
            )
            connection.time()
          }
          case ClockConfig.WallClock(_) => {
            logger.info(
              "Running in WallClock mode; will invoke handler based on wall clock time."
            )
            // The first tick is immediately, for simplicity.
            Source
              .tick(0.second, interval, ())
              .map(_ => CantonTimestamp.now())
          }
          case _: ClockConfig.RemoteClock =>
            sys.error(
              "Remote clock mode is unsupported for CN apps, use either SimClock or WallClock"
            )
        }
        registerRequestHandler(
          name,
          timeSource.preMaterialize()._2,
        )(handler0)
      }
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq[AsyncOrSyncCloseable](
      SyncCloseable(
        "Automation services",
        Lifecycle.close(automationServices.get(): _*)(logger),
      )
    )
}

object AutomationService {

  private class TaskHandlerService[T](
      config: AutomationConfig,
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
        .mapAsync(parallelism = config.parallelism)(processTask)
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
