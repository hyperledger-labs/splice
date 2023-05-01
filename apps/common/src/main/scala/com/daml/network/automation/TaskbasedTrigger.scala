package com.daml.network.automation

import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
            if (context.retryProvider.isClosing) {
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
