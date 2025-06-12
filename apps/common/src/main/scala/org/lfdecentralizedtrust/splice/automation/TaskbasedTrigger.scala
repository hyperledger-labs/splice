// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.grpc.{GrpcException, GrpcStatus}
import com.daml.metrics.api.MetricsContext
import com.digitalasset.base.error.utils.ErrorDetails
import com.digitalasset.canton.ledger.error.groups.ConsistencyErrors.ContractNotFound
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.protocol.LocalRejectError.ConsistencyRejections.{
  InactiveContracts,
  LockedContracts,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.grpc.protobuf.StatusProto
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.environment.RetryProvider.{
  QuietNonRetryableException,
  RetryableConditions,
}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** An abstract interface for triggers that keep track of some list of tasks.
  *
  * Note that the vanilla [[Trigger]] and [[PollingTrigger]] are the only non-task based triggers.
  *
  * @param quiet true if only non-trivial outcomes should be logged
  */
abstract class TaskbasedTrigger[T: Pretty](
    quiet: Boolean = false
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends Trigger {
  private implicit val mc: MetricsContext = MetricsContext(
    "trigger_name" -> this.getClass.getSimpleName(),
    "trigger_type" -> "taskbased",
  ).withExtraLabels(extraMetricLabels*)

  private val isDsoDelegateTrigger: Boolean = this.getClass.getInterfaces
    .map(_.getSimpleName)
    .contains("SvTaskBasedTrigger")

  protected val taskRetry: RetryFor = RetryFor.Automation

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
  final protected def processTaskWithRetry(task: T): Future[Boolean] = {
    // Creating a new trace here, as multiple requests can be processed in parallel.
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      def processTaskWithStalenessCheck(): Future[TaskOutcome] =
        // TODO(#842) refactor for better latency reporting
        metrics.latency
          .timeFuture(completeTask(task))
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

      if (!quiet)
        logger.info(show"Processing\n$task")
      waitForReadyToWork()
        .flatMap(_ =>
          context.retryProvider
            .retry(
              taskRetry,
              "processTaskWithRetry",
              "processTaskWithRetry",
              // If the trigger is currently disabled, then this is delaying the retry until the trigger is enabled again.
              // `waitForReadyToWork` is expected to abort waiting if the retry provider is shutting down.
              // It does mean that the overall `processTaskWithRetry` call can take an arbitrarily long time to complete
              // if the trigger is disabled in the middle of retrying.
              waitForReadyToWork()
                .flatMap(_ => processTaskWithStalenessCheck())
                .transform { result =>
                  result match {
                    case Failure(ex) =>
                      ex match {
                        case GrpcException(status @ GrpcStatus(statusCode, _), trailers) =>
                          val statusProto = StatusProto.fromStatusAndTrailers(status, trailers)
                          val errorDetails = ErrorDetails.from(statusProto)
                          val errorCodeId = errorDetails
                            .flatMap {
                              case ed: ErrorDetails.ErrorInfoDetail =>
                                Some(ed.errorCodeId)
                              case _ => None
                            }
                            .headOption
                            .getOrElse("none")
                          errorCodeId match {
                            case ContractNotFound.id | LockedContracts.id | InactiveContracts.id =>
                              MetricsContext.withExtraMetricLabels(
                                ("statusCode", statusCode.toStatus.getCode.toString),
                                ("errorCodeId", errorCodeId),
                                ("isDsoDelegateTrigger", isDsoDelegateTrigger.toString),
                                ("contentionFailure", "true"),
                              ) { m =>
                                metrics.attempted.mark()(m)
                              }
                            case _ =>
                              MetricsContext.withExtraMetricLabels(
                                ("statusCode", statusCode.toStatus.getCode.toString),
                                ("errorCodeId", errorCodeId),
                                ("isDsoDelegateTrigger", isDsoDelegateTrigger.toString),
                                ("contentionFailure", "false"),
                              ) { m =>
                                metrics.attempted.mark()(m)
                              }
                          }
                          Failure(ex)
                        case _ =>
                          MetricsContext.withExtraMetricLabels(
                            ("statusCode", Status.UNKNOWN.getCode.toString),
                            ("errorCodeId", "none"),
                            ("isDsoDelegateTrigger", isDsoDelegateTrigger.toString),
                            ("contentionFailure", "false"),
                          ) { m =>
                            metrics.attempted.mark()(m)
                          }
                          Failure(ex)
                      }
                    case Success(_) =>
                      MetricsContext.withExtraMetricLabels(
                        ("statusCode", Status.OK.getCode.toString),
                        ("errorCodeId", "none"),
                        ("isDsoDelegateTrigger", isDsoDelegateTrigger.toString),
                        ("contentionFailure", "false"),
                      ) { m =>
                        metrics.attempted.mark()(m)
                      }
                      result
                  }
                },
              logger,
              additionalRetryableConditions,
              mc.labels,
            )
        )
        .transform {
          case Success(taskOutcomeE) =>
            taskOutcomeE match {
              case TaskSuccess(description) =>
                if (quiet)
                  logger.info(show"Completed processing $task with outcome: $description")
                else
                  logger.info(show"Completed processing with outcome: $description")
                MetricsContext.withExtraMetricLabels(("outcome", "success")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(true)
              case TaskFailed(description) =>
                if (quiet)
                  logger.warn(show"Failed processing $task with outcome: $description")
                else
                  logger.warn(show"Failed processing with outcome: $description")
                MetricsContext.withExtraMetricLabels(("outcome", "fail")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(false)
              case TaskNoop =>
                if (!quiet) logger.info(show"$TaskNoop")
                MetricsContext.withExtraMetricLabels(("outcome", "noop")) { m =>
                  metrics.completed.mark()(m)
                }
                // Signal to polling triggers that no work was done, and it's not worth retrying immediately.
                Success(false)
              case TaskStale =>
                if (!quiet) logger.info(show"$TaskStale")
                MetricsContext.withExtraMetricLabels(("outcome", "stale")) { m =>
                  metrics.completed.mark()(m)
                }
                Success(true)
            }

          case Failure(ex: QuietNonRetryableException) =>
            if (context.retryProvider.isClosing) {
              logger.info(
                "Ignoring processing failure, as we are shutting down",
                ex,
              )
            } else {
              MetricsContext.withExtraMetricLabels(("outcome", "expected_failure")) { m =>
                metrics.completed.mark()(m)
              }
              logger.warn(
                show"Skipping processing of \n$task\ndue to expected non-retryable failure",
                ex,
              )
            }
            Success(false)

          case Failure(ex) =>
            if (context.retryProvider.isClosing) {
              logger.info(
                "Ignoring processing failure, as we are shutting down",
                ex,
              )
            } else {
              MetricsContext.withExtraMetricLabels(("outcome", "failure")) { m =>
                metrics.completed.mark()(m)
              }
              logger.error(
                show"Skipping processing of \n$task\ndue to unexpected failure",
                ex,
              )
            }

            // Here we recover from the failure so that processing can continue for other tasks.
            // We signal though that we failed, so that the trigger polling loop doesn't loop tightly when
            // all its tasks fail processing.
            Success(false)
        }
    }
  }

  private[automation] def additionalRetryableConditions: RetryableConditions = Map.empty

}
