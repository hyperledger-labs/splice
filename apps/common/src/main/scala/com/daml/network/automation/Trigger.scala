package com.daml.network.automation

import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.util.retry.RetryUtil.ErrorKind
import com.daml.network.environment.LedgerClient
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import akka.{Done, NotUsed}
import cats.syntax.parallel.*
import com.daml.ledger.javaapi.data.{Template as CodegenTemplate}
import com.daml.ledger.javaapi.data.codegen.{InterfaceCompanion, ContractId, DamlRecord}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.CoinRetries
import com.daml.network.store.{AcsStore, CoinAppStore, MultiDomainAcsStore}
import com.daml.network.util.{HasHealth, Contract}
import Contract.Companion.Template as TemplateCompanion
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.FutureInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.{AkkaUtil, LoggerUtil}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Failure, Success}

/** Convenience class to capture the shared context required to instantiate triggers in an automation service. */
case class TriggerContext(
    config: AutomationConfig,
    timeouts: ProcessingTimeout,
    clock: Clock,
    retryProvider: CoinRetries,
    loggerFactory: NamedLoggerFactory,
)

sealed trait TaskOutcome extends Product with Serializable

/** Helper class for modelling the outcome of a task handled by a trigger.
  *
  * @param description in most cases a short description of how the task was completed, sometimes also a return value.
  *                Should be a Left-value if the task failed in some way.
  */
case class TaskSuccess(
    description: String
) extends TaskOutcome

case object TaskStale extends TaskOutcome with PrettyPrinting {
  override def pretty: Pretty[this.type] = {
    prettyOfString(_ => "skipped, as the task has become stale")
  }
}

/** Common base trait for all triggers. */
trait Trigger extends FlagCloseable with NamedLogging with Spanning with HasHealth {

  implicit def ec: ExecutionContext
  implicit def tracer: Tracer

  protected def context: TriggerContext

  protected def timeouts: ProcessingTimeout = context.timeouts

  protected def loggerFactory: NamedLoggerFactory = context.loggerFactory

  /** Run this trigger in the background. MUST be called exactly once. */
  def run(): Unit
}

/** An abstract interface for triggers that keep track of some list of tasks.
  *
  * Note that the vanilla [[Trigger]] and [[PollingTrigger]] are the only non-task based triggers.
  */
abstract class TaskbasedTrigger[T: Pretty]()(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends Trigger {

  /** How to complete a task.
    *
    * This MUST take all the actions necessary such that 'isStaleTask' returns true after successful completion.
    * We do not support tasks that should be retried after a specific delay. If you do need such support,
    * then we recommend changing the Daml workflows such that the ledger records that the task has been postponed.
    *
    * We make this decision to always require a task-handler to make progress to avoid problems with restarts
    * and slow-downs from too eager polling of tasks.
    *
    * If you find an example where that is not possible, then let's talk :)
    *
    * @return a short description of how the task was completed, i.e, its outcome
    */
  protected def completeTask(task: T)(implicit tc: TraceContext): Future[TaskOutcome]

  /** Check whether a task has become stale and can be skipped.
    *
    * Note that a task can become stale for reasons other than 'completeTask' succeeding,
    * as there can be concurrent actions submitted to the ledger that make the task stale;
    * e.g., another party archiving a contract representing a request.
    */
  protected def isStaleTask(task: T)(implicit tc: TraceContext): Future[Boolean]

  /** Processes the task with a retry and returns whether that was successful. */
  final protected def processTaskWithRetry(task: T): Future[Boolean] =
    // Creating a new trace here, as multiple requests can be processed in parallel.
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      def processTaskWithStalenessCheck(): Future[TaskOutcome] =
        completeTask(task)
          .recoverWith { case ex =>
            logger.info("Checking whether the task is stale, as its processing failed with ", ex)
            isStaleTask(task)
              .transform {
                case Success(true) =>
                  Success(TaskStale)
                case Success(false) =>
                  logger.debug(
                    s"Task that failed with following exception is not stale: ${ex.getLocalizedMessage} "
                  )
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
          logger,
        )
        .transform {
          case Success(taskOutcomeE) =>
            taskOutcomeE match {
              case TaskSuccess(description) =>
                logger.info(
                  show"Completed processing with outcome: ${description}"
                )
                Success(true)
              case TaskStale =>
                logger.info(
                  show"${TaskStale}"
                )
                Success(true)
            }

          case Failure(ex) =>
            if (context.retryProvider.isShuttingDown) {
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
            // We signal though that we failed, so that the trigger polling loop doesn't loop tightly when
            // all its tasks fail processing.
            Success(false)
        }
    }

}

/** A trigger receiving its tasks via an Akka source. */
abstract class SourceBasedTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends TaskbasedTrigger[T]
    with FlagCloseableAsync {

  /** The source from which to consume tasks. */
  protected def source: Source[T, NotUsed]

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
          if (context.retryProvider.isShuttingDown) {
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

object OnCreateTrigger {
  trait Companion[-C, TCid <: ContractId[_], T] {
    def streamContracts(acs: AcsStore)(companion: C): Source[Contract[TCid, T], NotUsed]
    def lookupContractById(acs: AcsStore)(companion: C)(id: TCid): Future[Option[Contract[TCid, T]]]
  }

  implicit def templateCompanion[TCid <: ContractId[
    T
  ], T <: CodegenTemplate]: Companion[TemplateCompanion[TCid, T], TCid, T] =
    new Companion[TemplateCompanion[TCid, T], TCid, T] {
      override def streamContracts(acs: AcsStore)(companion: TemplateCompanion[TCid, T]) =
        acs.streamContracts(companion)
      override def lookupContractById(acs: AcsStore)(companion: TemplateCompanion[TCid, T])(
          id: TCid
      ): Future[Option[Contract[TCid, T]]] =
        acs.lookupContractById(companion)(id)
    }

  implicit def interfaceCompanion[I, Id <: ContractId[I], View <: DamlRecord[View]] =
    new Companion[InterfaceCompanion[I, Id, View], Id, View] {
      override def streamContracts(acs: AcsStore)(companion: InterfaceCompanion[I, Id, View]) =
        acs.streamContracts(companion)
      override def lookupContractById(acs: AcsStore)(companion: InterfaceCompanion[I, Id, View])(
          id: Id
      ): Future[Option[Contract[Id, View]]] =
        acs.lookupContractById(companion)(id)
    }

  type Template[TCid <: ContractId[T], T <: CodegenTemplate] =
    OnCreateTrigger[TemplateCompanion[TCid, T], TCid, T]
}

/** A trigger for processing contract create events.
  * This trigger assumes that the created contract is archived as part of processing it.
  */
abstract class OnCreateTrigger[C, TCid <: ContractId[_], T](
    store: CoinAppStore[_, _],
    protected val getDomainId: () => Future[DomainId],
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: OnCreateTrigger.Companion[C, TCid, T],
) extends SourceBasedTrigger[Contract[TCid, T]] {

  private val acsFuture: Future[AcsStore] =
    getDomainId().flatMap(store.acs(_))

  override protected val source: Source[Contract[TCid, T], NotUsed] =
    Source
      .lazyFutureSource(() => acsFuture.map(companionClass.streamContracts(_)(companion)))
      .mapMaterializedValue(_ => NotUsed)

  override final def isStaleTask(
      task: Contract[TCid, T]
  )(implicit tc: TraceContext): Future[Boolean] =
    acsFuture
      .flatMap(companionClass.lookupContractById(_)(companion)(task.contractId))
      .map(_.isEmpty)
}

abstract class OnReadyForTransferInTrigger(
    store: CoinAppStore[_, _]
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SourceBasedTrigger[LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out] {

  override protected val source
      : Source[LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out, NotUsed] =
    store.multiDomainAcsStore.streamReadyForTransferIn()

  override final protected def isStaleTask(
      task: LedgerClient.GetTreeUpdatesResponse.TransferEvent.Out
  )(implicit tc: TraceContext): Future[Boolean] = {
    import MultiDomainAcsStore.TransferId
    store.multiDomainAcsStore.isReadyForTransferIn(TransferId.fromTransferOut(task)).map(!_)
  }

}

/** A trigger that regularly executes work.
  *
  * This is a very generic option for implementing a trigger.
  * Look at its child classes (`ctrl + h` for type hierarchy in IntelliJ) for useful specializations.
  */
trait PollingTrigger extends Trigger with FlagCloseableAsync {

  /** The main body of the polling trigger
    *
    * It should check whether there is work to be done and if yes, perform it.
    * The return value signals `true` if another iteration of `performWorkIfAvailable` should
    * be done immediately, and `false` if another iteration should only be done after
    * the polling trigger's configured delay.
    *
    * Typically, the you should signal `true` to loop immediately iff
    * there could be some more work to be done.
    */
  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean]

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  private val pollingLoopRef = new AtomicReference[Option[Future[Done]]](None)

  private val retryable = CoinRetries.RetryableError(
    "pollingTriggerTask",
    Seq.empty,
    "transient",
    "non-transient",
    s"restarting after ${context.config.pollingInterval}",
  )

  override def isHealthy: Boolean = pollingLoopRef.get().exists(!_.isCompleted)

  override def run(): Unit = LoggerUtil.logOnThrow {

    require(pollingLoopRef.get().isEmpty, "run must not be called twice")

    // We create a top-level tid for the trigger polling loop for ease of navigation in lnav using 'o' and 'O'
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      {

        // Construct a future that loops until the Trigger is closing.
        def pollingLoop(previousResult: Future[Boolean]): Future[Done] = LoggerUtil.logOnThrow {

          def exitPollingLoop(): Future[Done] =
            Future.successful(Done)

          def loopWithDelay(): Future[Done] = LoggerUtil.logOnThrow {
            val continueOrShutdownSignal = context.retryProvider.waitUnlessShutdown(
              context.clock
                .scheduleAfter(
                  _ => {
                    // No work done here, as we are only interested in the scheduling notification
                    ()
                  },
                  context.config.pollingInterval.duration,
                )
            )
            // Continue looping
            continueOrShutdownSignal.unwrap.flatMap {
              case UnlessShutdown.AbortedDueToShutdown =>
                exitPollingLoop()
              case UnlessShutdown.Outcome(()) =>
                pollingLoop(Future.successful(true))
            }
          }

          // Here we tie the knot and ensure that once the previous iteration completes, we kick off another iteration.
          previousResult.transformWith {
            case Failure(ex) =>
              // We only call this to get logging
              (retryable.retryOK(Failure(ex), logger)).discard[ErrorKind]
              loopWithDelay()

            case Success(workDone) =>
              if (context.retryProvider.isShuttingDown) {
                exitPollingLoop()
              } else if (workDone) {
                // If productive work was done in the previous iteration, then we loop without a delay.
                pollingLoop(performWorkIfAvailable())
              } else {
                logger.trace(
                  show"No work performed. Sleeping for ${context.config.pollingInterval}"
                )
                loopWithDelay()
              }
          }
        }(loggingContext)

        logger.debug(
          show"Starting trigger polling loop (polling interval: ${context.config.pollingInterval})"
        )

        // kick-off the first iteration, and store the handle to its final outcome
        val loopF = pollingLoop(Future.successful(true)).transform(
          context.retryProvider.logTerminationAndRecoverOnShutdown("trigger polling loop", logger)
        )
        pollingLoopRef.set(Some(loopF))
      }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      AsyncCloseable(
        "trigger polling loop",
        pollingLoopRef.get().getOrElse(Future.successful(Done)),
        timeouts.shutdownNetwork.duration,
      )
    )
}

/** A trigger that regularly polls for new tasks and executes them in parallel.
  *
  * Unless you implement an one-off trigger, you likely want to write or use a specialization of this trigger as a base
  * trigger implementation.
  */
abstract class PollingParallelTaskExecutionTrigger[T: Pretty]()(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends TaskbasedTrigger[T]
    with PollingTrigger {

  /** Override with how to retrieve the list of tasks that should be processed in parallel right now.
    *
    * The DB queries underlying it SHOULD be efficient enough to run in a loop.
    */
  // TODO(M3-83): consider retrieving a Source of tasks so that the Source can run down an index and thus avoid
  // expensive queries in Postgres due to having to skip deleted, but not yet VACUUMED tuples!
  // This can be done using the seek method from https://use-the-index-luke.com/sql/partial-results/fetch-next-page
  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[T]]

  /** Returns whether some useful work was done, i.e., at least one task completed. */
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      tasks <- retrieveTasks()
      // TODO(M3-83): review our triggers for whether the task retrieval for time-based triggers performs sufficiently well
      // TODO(M3-83): consider building support for batching the commands resulting from the different tasks
      tasksSucceeded <- tasks.parTraverse(processTaskWithRetry)
    } yield tasksSucceeded.exists(succeeded => succeeded)
}

/** A trigger for scheduling tasks that only become ready after some future date. */
abstract class ScheduledTaskTrigger[T: Pretty](implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ScheduledTaskTrigger.ReadyTask[T]] {

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
    TCid <: ContractId[T],
    T <: CodegenTemplate,
](
    acs: Future[AcsStore],
    listExpiredContracts: ExpiredContractTrigger.ListExpiredContracts[TCid, T],
    templateCompanion: TemplateCompanion[TCid, T],
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[Contract[TCid, T]] {

  override final protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[TCid, T]]] =
    listExpiredContracts(now, limit)

  override final protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[Contract[TCid, T]]
  )(implicit tc: TraceContext): Future[Boolean] = for {
    acs <- acs
    ct <- acs.lookupContractById(templateCompanion)(task.work.contractId)
  } yield ct.isEmpty
}

object ExpiredContractTrigger {
  type ListExpiredContracts[TCid <: ContractId[_], T] =
    (CantonTimestamp, Int) => Future[Seq[Contract[TCid, T]]]
}
