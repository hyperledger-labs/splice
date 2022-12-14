package com.daml.network.automation

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import cats.syntax.parallel.*
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, ContractId}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.CoinRetries
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{HasHealth, JavaContract}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.{Failure, Success}

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
case class TriggerContext(
    config: AutomationConfig,
    timeouts: ProcessingTimeout,
    clock: Clock,
    retryProvider: CoinRetries,
    loggerFactory: NamedLoggerFactory,
)

/** Common base trait for all triggers. */
abstract class Trigger[T: Pretty](implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends FlagCloseable
    with NamedLogging
    with Spanning
    with HasHealth {
  protected def context: TriggerContext
  protected def timeouts: ProcessingTimeout = context.timeouts
  protected def loggerFactory: NamedLoggerFactory = context.loggerFactory

  /** How to process a task. */
  protected def processTask(task: T)(implicit tc: TraceContext): Future[Option[String]]

  /** How to check whether a task has become stale and can be skipped. */
  protected def isStaleTask(task: T)(implicit tc: TraceContext): Future[Boolean]

  /** Run this trigger in the background. MUST be called exactly once. */
  def run(): Unit

  final protected def processTaskWithRetry(task: T): Future[Unit] =
    // Creating a new trace here, as multiple requests can be processed in parallel.
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      def processTaskWithStalenessCheck(): Future[Option[String]] =
        processTask(task).recoverWith { case ex =>
          logger.info("Checking whether the task is stale, as its processing failed with ", ex)
          isStaleTask(task).transform {
            case Success(true) =>
              Success(Some(show"skipped, as the task has become stale"))
            case Success(false) =>
              Failure(ex)
            case Failure(staleCheckEx) =>
              logger.info("Encountered exception when checking task staleness", staleCheckEx)
              Failure(ex)
          }
        }

      logger.info(show"Processing\n$task")
      context.retryProvider
        .retryForAutomation(
          "processTaskWithRetry",
          processTaskWithStalenessCheck(),
          callingService = this,
        )
        .transform {
          // TODO(#1968): review whether we can get rid of the Option here so that every task's outcome is logged
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
            if (this.isClosing) {
              logger.info(
                "Ignoring processing failure, as we are shutting down",
                ex,
              )
            } else
              logger.error(
                show"Skipping processing of \n$task\ndue to unexpected failure",
                ex,
              )

            // Here we recover from the failure so that processing can continue for other tasks.
            Success(())
        }
    }
}

/** A trigger receiving its tasks via an Akka source. */
abstract class SourceBasedTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends Trigger[T] {

  /** The source from which to consume tasks. */
  protected def source: Source[T, NotUsed]

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private[this] var taskHandlerVar: Option[AutomationService.TaskHandlerService[T]] = None

  override def run(): Unit = blocking {
    synchronized {
      require(taskHandlerVar.isEmpty, "Run must be called at most once!")
      if (!isClosing) {
        taskHandlerVar = Some(
          new AutomationService.TaskHandlerService[T](
            context.config,
            source,
            processTaskWithRetry,
            false,
            logger,
            timeouts,
          )
        )
      }
    }
  }

  override def onClosed(): Unit = {
    blocking {
      synchronized {
        taskHandlerVar.foreach(_.close())
      }
    }
    super.onClosed()
  }

  override def isHealthy: Boolean = taskHandlerVar.exists(_.isHealthy)

}

/** A trigger for processing contract create events. */
abstract class OnCreateTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
    acs: AcsStore,
    templateCompanion: ContractCompanion[TC, TCid, T],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[JavaContract[TCid, T]] {

  override final protected val source: Source[JavaContract[TCid, T], NotUsed] =
    acs.streamContracts(templateCompanion)

  override final protected def isStaleTask(
      task: JavaContract[TCid, T]
  )(implicit tc: TraceContext): Future[Boolean] =
    acs
      .lookupContractById(templateCompanion)(task.contractId)
      .map(_.value.isEmpty)
}

/** A trigger based on regularly polling for new tasks.
  *
  * This is a very generic option for implementing a trigger.
  * Look at its child classes for useful specializations.
  */
abstract class PollingTrigger[T: Pretty]()(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends Trigger[T]
    with FlagCloseableAsync {

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  private val pollingLoopRef = new AtomicReference[Option[Future[Unit]]](None)

  /** Override with how to retrieve the list of tasks that should be processed in parallel right now.
    *
    * The DB queries underlying it SHOULD be efficient enough to run in a loop.
    */
  // TODO(M3-83): consider retrieving a Source of tasks so that the Source can run down an index and thus avoid
  // expensive queries in Postgres due to having to skip deleted, but not yet VACUUMED tuples!
  // This can be done using the seek method from https://use-the-index-luke.com/sql/partial-results/fetch-next-page
  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[T]]

  // TODO(tech-debt): expose such a shutdown signal as a future directly from `FlagCloseable`
  private val shutdownSignal = Promise[UnlessShutdown[Unit]]()
  runOnShutdown(new RunOnShutdown {

    /** the name, used for logging during shutdown */
    override def name: String = "Signal shutdown to polling loop"

    /** true if the task has already run (maybe elsewhere) */
    override def done: Boolean = shutdownSignal.isCompleted

    /** invoked by [FlagCloseable] during shutdown */
    override def run(): Unit = {
      val _ = shutdownSignal.tryComplete(Success(UnlessShutdown.AbortedDueToShutdown))
    }
  })(TraceContext.empty)

  override def isHealthy: Boolean = pollingLoopRef.get().exists(!_.isCompleted)

  override def run(): Unit = LoggerUtil.logOnThrow {
    require(pollingLoopRef.get().isEmpty, "run must not be called twice")

    // We create a top-level tid for the polling loop for ease of navigation in lnav using 'o' and 'O'
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      {

        // Construct a future that loops until the Trigger is closing.
        def pollingLoop(previousResult: Future[Boolean]): Future[Unit] = LoggerUtil.logOnThrow {

          def exitPollingLoop(): Future[Unit] = {
            logger.debug("Exiting polling loop, as we are shutting down.")
            Future.unit
          }

          def loopWithDelay(): Future[Unit] = LoggerUtil.logOnThrow {
            // Construct a promise that captures whether enough time has passed or we are shutting down
            val continueOrShutdownSignal = Promise[UnlessShutdown[Unit]]()
            continueOrShutdownSignal
              .completeWith(
                context.clock
                  .scheduleAfter(
                    _ => {
                      // No work done here, as we are only interested in the scheduling notification
                      ()
                    },
                    context.config.pollingInterval.duration,
                  )
                  .unwrap
              )
            continueOrShutdownSignal.completeWith(shutdownSignal.future)
            // Continue looping
            continueOrShutdownSignal.future.flatMap {
              case UnlessShutdown.AbortedDueToShutdown =>
                exitPollingLoop()
              case UnlessShutdown.Outcome(()) =>
                pollingLoop(Future.successful(true))
            }
          }

          // Here we tie the knot and ensure that once the previous iteration completes, we kick off another iteration.
          previousResult.transformWith {
            case Failure(ex) =>
              logger.error(
                s"Unexpected task list processing failure (restarting after ${context.config.pollingInterval})",
                ex,
              )
              loopWithDelay()

            case Success(workDone) =>
              if (this.isClosing) {
                exitPollingLoop()
              } else if (workDone) {
                // If productive work was done in the previous iteration, then we loop without a delay.
                pollingLoop(retrieveAndProcessTasks())
              } else {
                logger.trace(show"No tasks found. Sleeping for ${context.config.pollingInterval}")
                loopWithDelay()
              }
          }
        }(loggingContext)

        logger.debug(
          show"Starting polling loop (polling interval: ${context.config.pollingInterval})"
        )

        // kick-off the first iteration, and store the handle to its final outcome
        pollingLoopRef.set(Some(pollingLoop(Future.successful(true))))
      }
    }
  }

  private def retrieveAndProcessTasks()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      tasks <- retrieveTasks()
      // TODO(M3-83): review our triggers for whether the task retrieval for time-based triggers performs sufficiently well
      // TODO(M3-83): consider building support for batching the commands resulting from the different tasks
      case () <- tasks.parTraverse_(processTaskWithRetry)
    } yield
    //  We return 'false' here to always loop with a delay, as we have one case (unfunded subscription payments) that would otherwise loop tightly
    // TODO(#1968): switch this to be more specific as to whether skipping was requested
    false

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      AsyncCloseable(
        "polling loop",
        // TODO(tech-debt): this kind of wrapping is repeated in multiple places => move it into a shared utility
        pollingLoopRef
          .get()
          .getOrElse(Future.unit)
          .recover(ex => logger.info("Ignoring exception due to shutdown", ex)(TraceContext.empty)),
        timeouts.shutdownNetwork.duration,
      )
    )
}

/** A trigger for scheduling tasks that only become ready after some future date. */
abstract class ScheduledTaskTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends PollingTrigger[ScheduledTaskTrigger.ReadyTask[T]] {

  /** Retrieve a list of tasks that are ready for execution now. */
  protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[T]]

  override final protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ScheduledTaskTrigger.ReadyTask[T]]] = {
    // We shift the clock-reading by a small grace period to account for potential clock skew
    val now = context.clock.now.minus(context.config.clockSkewAutomationDelay.duration)
    // TODO(M3-83): review whether we should introduce a separate task list size parameter
    listReadyTasks(now, context.config.parallelism)
      .map(_.map(ScheduledTaskTrigger.ReadyTask(now, _)))
  }

}

object ScheduledTaskTrigger {
  case class ReadyTask[T: Pretty](
      readyAt: CantonTimestamp,
      work: T,
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] = {
      prettyOfClass(param("readyAt", _.readyAt), param("work", _.work))
    }
  }
}

/** A trigger for processing expired contracts whose expiry archives exactly them.
  *
  * Use [[ScheduledTaskTrigger]] for more complex expiry choices.
  */
// TODO(tech-debt): if we happen to find LOTS of instances that just expire the contract based on its expiry date, then we should consider introducing a Daml-level interface 'ExpiringContract' and handle all of them using single trigger.
abstract class ExpiredContractTrigger[
    TC <: Contract[TCid, T],
    TCid <: ContractId[T],
    T <: Template,
](
    acs: AcsStore,
    listExpiredContracts: (CantonTimestamp, Int) => Future[QueryResult[Seq[JavaContract[TCid, T]]]],
    templateCompanion: ContractCompanion[TC, TCid, T],
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[JavaContract[TCid, T]] {

  override final protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[JavaContract[TCid, T]]] = {
    listExpiredContracts(now, limit).map(_.value)
  }

  override final protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[JavaContract[TCid, T]]
  )(implicit tc: TraceContext): Future[Boolean] =
    acs
      .lookupContractById(templateCompanion)(task.work.contractId)
      .map(_.value.isEmpty)
}
