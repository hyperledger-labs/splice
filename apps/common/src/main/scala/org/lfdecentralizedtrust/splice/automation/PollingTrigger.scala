// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.config.{NonNegativeDuration, NonNegativeFiniteDuration}
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.ErrorKind.*
import org.apache.pekko.Done
import org.lfdecentralizedtrust.splice.automation.PollingTrigger.PollingTriggerState
import org.lfdecentralizedtrust.splice.environment.RetryProvider

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise, blocking}
import scala.util.{Failure, Success}

/** A trigger that regularly executes work.
  *
  * This is a very generic option for implementing a trigger.
  * Look at its child classes (`ctrl + h` for type hierarchy in IntelliJ) for useful specializations.
  */
trait PollingTrigger extends Trigger with FlagCloseableAsync {
  private implicit val mc: MetricsContext = MetricsContext(
    "trigger_name" -> this.getClass.getSimpleName,
    "trigger_type" -> "polling",
  ).withExtraLabels(extraMetricLabels*)

  protected def isRewardOperationTrigger: Boolean = false

  private val (pollingInterval, pollingJitter) =
    if (isRewardOperationTrigger)
      (context.config.rewardOperationPollingInterval, context.config.rewardOperationPollingJitter)
    else (context.config.pollingInterval, context.config.pollingJitter)

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

  private def performWorkIfNotPaused()(implicit
      traceContext: TraceContext
  ): Future[Boolean] =
    blocking {
      synchronized {
        if (pausedVar) {
          // If the trigger is paused, wait until the next polling interval
          logger.trace("Skipping work, trigger is paused")
          Future.successful(false)
        } else {
          assert(
            runningTaskFinishedVar.isEmpty,
            "performWorkIfNotPaused may not be called concurrently",
          )
          runningTaskFinishedVar = Some(Promise())
          // TODO(#842) refactor for better latency reporting
          val latencyTimer = metrics.latency.startAsync()
          metrics.iterations.mark()
          waitForReadyToWork()
            .flatMap(_ => performWorkIfAvailable())
            .transform { performedWork =>
              val performedWorkMetricsString = performedWork match {
                case Success(value) => s"Success($value)"
                // `.toString`ing an exception might end up exceeding the max cardinality, see #16132
                // so instead we just include the exception name
                case Failure(exception) => s"Failure(${exception.getClass.getName})"
              }
              MetricsContext.withExtraMetricLabels(("work_done", performedWorkMetricsString)) { m =>
                latencyTimer.stop()(m)
              }

              assert(runningTaskFinishedVar.nonEmpty)
              runningTaskFinishedVar.foreach(_.success(()))
              runningTaskFinishedVar = None

              performedWork
            }
        }
      }
    }

  implicit private val loggingContext: ErrorLoggingContext =
    ErrorLoggingContext.fromTracedLogger(logger)(TraceContext.empty)

  private val pollingLoopRef =
    new AtomicReference[Option[Future[(PollingTriggerState, Boolean, Instant)]]](None)

  private val retryable = RetryProvider.RetryableError(
    "pollingTriggerTask",
    Seq.empty,
    Map.empty,
    "transient",
    "non-transient",
    s"restarting after $pollingInterval",
    context.metricsFactory,
    mc.labels,
    context.retryProvider,
  )

  override def isHealthy: Boolean = pollingLoopRef
    .get()
    .exists(_.value match {
      case Some(Success((_, _, completionTime))) =>
        completionTime
          .plus(context.config.futureCompletionGracePeriod.asJava)
          .isAfter(Instant.now())
      case _ => true
    })

  override def run(paused: Boolean): Unit = LoggerUtil.logOnThrow {

    require(pollingLoopRef.get().isEmpty, "run must not be called twice")
    pausedVar = paused

    // We create a top-level tid for the trigger polling loop for ease of navigation in lnav using 'o' and 'O'
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      {

        // Construct a future that loops until the Trigger is closing.
        def pollingLoop(
            state: PollingTriggerState
        ): Unit = LoggerUtil.logOnThrow {

          def loopWithDelay(newState: PollingTriggerState) = {
            // Like below,
            // we use updateAndGet even if it can be reapplied during contention because we know there's no contention
            pollingLoopRef.updateAndGet(_.map(_.flatMap { state =>
              context.retryProvider
                .scheduleAfterUnlessShutdown(
                  Future.successful(pollingLoop(newState)),
                  context.pollingClock,
                  pollingInterval,
                  pollingJitter,
                )
                .onShutdown(())
                .map(_ => state)
            }))
          }

          if (context.retryProvider.isClosing) {
            ()
          } else {
            def performWork = {
              performWorkIfNotPaused()
                .map(
                  PollingTrigger.initialPollingTriggerState -> _
                )
                .recover { case ex =>
                  // Call this to get the default logging
                  val errorKind = retryable.determineExceptionErrorKind(ex, logger)
                  // Determine if we need to log a warning due to repeated transient errors
                  val isTransientFailure = errorKind match {
                    case TransientErrorKind(_) => true
                    case FatalErrorKind => false
                    case NoSuccessErrorKind => false
                    case UnknownErrorKind => false
                  }
                  val numConsecutiveTransientFailures =
                    if (isTransientFailure) state.numConsecutiveTransientFailures + 1 else 0
                  val newState =
                    if (
                      numConsecutiveTransientFailures > context.config.maxNumSilentPollingRetries
                    ) {
                      logger.warn(
                        s"Encountered $numConsecutiveTransientFailures consecutive transient failures (polling interval $pollingInterval).\nThe last one was:",
                        ex,
                      )
                      PollingTrigger.initialPollingTriggerState
                    } else {
                      PollingTriggerState(numConsecutiveTransientFailures)
                    }
                  newState -> false
                }
            }

            // Ties the futures performing the work together here, avoiding a recursive flatMap solution
            // Reason: https://github.com/DACH-NY/canton-network-node/issues/12443
            // We use updateAndGet even if it can be reapplied during contention because we know there's no contention
            pollingLoopRef
              .updateAndGet { current =>
                val workResult = performWork.map { case (state, workDone) =>
                  (state, workDone, Instant.now())
                }
                current match {
                  case Some(ref) =>
                    Some(
                      ref.flatMap(_ => workResult)
                    )
                  case None => Some(workResult)
                }
              }
              .foreach(_.foreach { case (state, workDone, _) =>
                if (workDone && !context.retryProvider.isClosing) {
                  pollingLoop(state)
                } else {
                  loopWithDelay(state)
                }
              })
          }
        }
        logger.info(
          show"Starting trigger polling loop, ${PollingTrigger.ConfigSummary(pollingInterval, pollingJitter, context.config.maxNumSilentPollingRetries)}"
        )

        pollingLoop(PollingTrigger.initialPollingTriggerState)
      }
    }
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    Seq(
      AsyncCloseable(
        "trigger polling loop",
        pollingLoopRef.get().map(_.map(_ => Done)).getOrElse(Future.successful(Done)),
        NonNegativeDuration.tryFromDuration(timeouts.shutdownNetwork.duration),
      )
    )

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var pausedVar: Boolean = false

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var runningTaskFinishedVar: Option[Promise[Unit]] = None

  override def pause(): Future[Unit] = {
    withNewTrace(this.getClass.getSimpleName) { implicit traceContext => _ =>
      logger.debug("Pausing trigger.")
      blocking {
        synchronized {
          pausedVar = true
          runningTaskFinishedVar.fold(Future.unit)(_.future.map { _ =>
            logger.debug("Trigger completely paused.")
          })
        }
      }
    }
  }

  override def resume(): Unit = blocking {
    synchronized {
      logger.debug("Resuming trigger.")(TraceContext.empty)
      pausedVar = false
    }
  }

  /** Runs the trigger once.
    *
    * The resulting Future completes with true when the trigger is done executing the work,
    * or completes with false if there was nothing to do.
    * See [[pause()]] for a description of when work is "done".
    */
  def runOnce()(implicit traceContext: TraceContext): Future[Boolean] = {
    blocking {
      synchronized {
        assert(
          pausedVar,
          "The trigger must be paused, otherwise there might be concurrent invocations of performWorkIfAvailable",
        )
      }
    }
    performWorkIfAvailable()

  }
}

object PollingTrigger {

  private val initialPollingTriggerState = PollingTriggerState(0)
  private case class PollingTriggerState(numConsecutiveTransientFailures: Int)

  private case class ConfigSummary(
      pollingInterval: NonNegativeFiniteDuration,
      pollingJitter: Double,
      maxNumSilentPollingRetries: Int,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("pollingInterval", _.pollingInterval),
        param("pollingJitter", _.pollingJitter.toString.unquoted),
        param("maxNumSilentPollingRetries", _.maxNumSilentPollingRetries),
      )
    }
  }
}
