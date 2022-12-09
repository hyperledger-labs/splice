package com.daml.network.automation

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer}
import akka.{Done, NotUsed}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerConnection, CoinRetries}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLogging, TracedLogger}
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.{NoTracing, Spanning, TraceContext}
import com.digitalasset.canton.util.AkkaUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Shared base class for running ingestion and task-handler automation in applications. */
abstract class AutomationService(
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends HasHealth
    with FlagCloseableAsync
    with NamedLogging
    with Spanning {

  private[this] def getLoggerForTrigger(name: String): TracedLogger = {
    val sanitizedName = name.replace(' ', '_')
    loggerFactory.append("trigger", sanitizedName).getTracedLogger(this.getClass)
  }

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

  /** Register a trigger driven from a source of tasks. */
  final protected def registerTrigger[T <: PrettyPrinting](
      name: String,
      source: Source[T, NotUsed],
      sequential: Boolean = false,
  )(
      handler0: (T, TracedLogger) => TraceContext => Future[Option[String]]
  ): Unit = {
    // This logger shadows the one provided by the [[NamedLogging]] mixin trait.
    // Ideally, we'd avoid that shadowing, but that would be in conflict with making use of [[FlagAsyncCloseable]],
    // which expects exactly such a field to be available. So we just accept that potential logging infelicity.
    val logger = getLoggerForTrigger(name)
    def handler(req: T): Future[Unit] = {
      // Creating a new trace here, as multiple requests can be processed in parallel.
      withNewTrace(name) { implicit traceContext => _ =>
        logger.info(show"Processing\n$req")
        retryProvider
          .retryForAutomation(
            name,
            handler0(req, logger)(traceContext),
            callingService = this,
          )
          .transform {
            case Success(outcomeO) =>
              outcomeO match {
                case None => Success(())
                case Some(outcome) =>
                  logger.info(
                    show"Completed processing with outcome: ${outcome.unquoted}"
                  )
                  Success(())
              }
            case Failure(ex) =>
              if (this.isClosing)
                logger.info(
                  "Ignoring processing failure, as we are shutting down",
                  ex,
                )
              else
                logger.error(
                  show"Skipping processing of \n$req\ndue to unexpected failure",
                  ex,
                )

              // Here we recover from the failure so that processing can continue for other tasks.
              Success(())
          }
      }
    }
    val service = new AutomationService.TaskHandlerService[T](
      automationConfig,
      source,
      handler,
      sequential,
      logger,
      timeouts,
    )
    registerService(service)
  }

  final protected def registerNewStyleTrigger(trigger: SourceBasedTrigger[?]): Unit = {
    registerService(trigger)
    trigger.run()
  }

  /** Register a trigger invoked periodically with the current (ledger) time.
    *  When Canton runs with wall-clock time, the handler is triggered on each `interval`.
    *  When Canton runs with simulation time, the handler is *instead* triggered
    *  every time the ledger time changes.
    */
  final protected def registerPollingTrigger(
      name: String,
      interval: NonNegativeFiniteDuration,
      connection: CoinLedgerConnection, // for querying ledger time when simtime is used
  )(
      handler0: (CantonTimestamp, TracedLogger) => TraceContext => Future[Option[String]]
  ): Unit = {
    withNewTrace("getTime") { implicit traceContext => _ =>
      {
        val logger = getLoggerForTrigger(name)
        val timeSource = clockConfig match {
          case ClockConfig.SimClock => {
            logger.info(
              "Running in SimClock mode; will invoke handler based on time service."
            )
            connection.getTimeSource()
          }
          case ClockConfig.WallClock(_) => {
            logger.info(
              "Running in WallClock mode; will invoke handler based on wall clock time."
            )
            // The first tick is immediately, for simplicity.
            Source
              .tick(0.second, interval.toScala, ())
              .map(_ => CantonTimestamp.now())
          }
          case _: ClockConfig.RemoteClock =>
            sys.error(
              "Remote clock mode is unsupported for CN apps, use either SimClock or WallClock"
            )
        }
        registerTrigger(
          name,
          timeSource
            // Conflating to have slower downstream consumers only process the most recent timestamp.
            // Using .max for prudence.
            .conflate((oldTime, newTime) => Ordering[CantonTimestamp].max(oldTime, newTime))
            .preMaterialize()
            ._2,
          sequential = true,
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

  class TaskHandlerService[T](
      config: AutomationConfig,
      source: Source[T, NotUsed],
      processTask: T => Future[Unit],
      sequential: Boolean,
      override protected val logger: TracedLogger,
      override val timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ) extends HasHealth
      with FlagCloseableAsync
      with NoTracing {

    private implicit val elc: ErrorLoggingContext =
      ErrorLoggingContext(logger, Map.empty, TraceContext.empty)

    private val parallelism = if (sequential) 1 else config.parallelism

    private val (killSwitch, completed) = AkkaUtil.runSupervised(
      logger.error("Fatally failed to handle task", _),
      source
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.foreachAsync(parallelism)(processTask))(Keep.both),
    )

    override def isHealthy: Boolean = !completed.isCompleted

    override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
      Seq[AsyncOrSyncCloseable](
        SyncCloseable(s"terminating processing loop", killSwitch.shutdown()),
        AsyncCloseable(
          "processing loop terminated",
          completed.recover(ex => {
            // The retry loop terminates with the last exception it was retrying on. Ignore that.
            logger.info("Ignoring exception due to shutdown", ex)
            Done
          }),
          timeouts.shutdownShort.unwrap,
        ),
      )
    }
  }
}
