package com.daml.network.automation

import akka.NotUsed
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.HasHealth
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
) extends HasHealth
    with FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private[this] val backgroundServices: AtomicReference[Seq[HasHealth & AutoCloseable]] =
    new AtomicReference(
      Seq.empty
    )

  override def isHealthy: Boolean = backgroundServices.get().forall(_.isHealthy)

  /** Register a background service orchestrated by and required for this automation service.
    *
    * The background service is promptly closed when the automation service is closed.
    */
  final protected def registerService(service: HasHealth & AutoCloseable): Unit = {
    val _ = backgroundServices.getAndUpdate(_.prepended(service))
    ()
  }

  private[this] val resources: AtomicReference[Seq[AutoCloseable]] = new AtomicReference(Seq.empty)

  /** Register a resource that should be promptly closed when closing the automation service. */
  final protected def registerResource[T <: AutoCloseable](resource: T): T = {
    val _ = resources.getAndUpdate(_.prepended(resource))
    resource
  }

  /** Register a task handler driven from a source of tasks. */
  final protected def registerRequestHandler[T <: PrettyPrinting](
      name: String,
      source: Source[T, NotUsed],
  )(
      handler0: T => TraceContext => Future[Option[String]]
  ): Unit = {
    def handler(req: T): Future[Unit] = {
      withNewTrace(name) { implicit traceContext => _ =>
        logger.info(show"Running operation ${name.singleQuoted} for\n$req")
        retryProvider
          .retryForAutomation(
            name,
            handler0(req)(traceContext),
            callingService = this,
          )
          .transform {
            case Success(outcomeO) =>
              outcomeO match {
                case None => Success(())
                case Some(outcome) =>
                  logger.info(
                    show"Completed operation ${name.singleQuoted} with outcome: ${outcome.unquoted}"
                  )
                  Success(())
              }
            case Failure(ex) =>
              if (this.isClosing)
                logger.info(
                  show"Ignoring failure of operation ${name.singleQuoted}, as we are shutting down",
                  ex,
                )
              else
                logger.error(
                  show"Operation ${name.singleQuoted} failed fatally and is skipped due to",
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
      handler0: CantonTimestamp => TraceContext => Future[Option[String]]
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
        "Orchestrated services",
        Lifecycle.close(backgroundServices.get(): _*)(logger),
      ),
      SyncCloseable(
        "Managed resources",
        Lifecycle.close(resources.get(): _*)(logger),
      ),
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
  ) extends HasHealth
      with FlagCloseableAsync
      // TODO(#1747): review logging
      with NamedLogging
      with NoTracing {

    val (killSwitch, completed) = AkkaUtil.runSupervised(
      logger.error("Fatally failed to handle task", _),
      source
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreachAsync(parallelism = config.parallelism)(processTask))(Keep.both),
    )

    override def isHealthy: Boolean = !completed.isCompleted

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
