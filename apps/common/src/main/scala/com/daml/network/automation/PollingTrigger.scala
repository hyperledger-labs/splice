// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import com.daml.metrics.api.MetricsContext
import com.daml.network.automation.PollingTrigger.PollingTriggerState
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.LoggerUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.retry.RetryUtil
import org.apache.pekko.Done

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{blocking, Future, Promise}
import scala.util.Failure

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
          // TODO(#8526) refactor for better latency reporting
          val latencyTimer = metrics.latency.startAsync()
          metrics.iterations.mark()
          waitForReadyToWork()
            .flatMap(_ => performWorkIfAvailable())
            .transform { performedWork =>
              MetricsContext.withExtraMetricLabels(("work_done", performedWork.toString)) { m =>
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
    new AtomicReference[Option[Future[(PollingTriggerState, Boolean)]]](None)

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

  override def isHealthy: Boolean = pollingLoopRef.get().exists(!_.isCompleted)

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
                  val errorKind = retryable.retryOK(Failure(ex), logger, None)
                  // Determine if we need to log a warning due to repeated transient errors
                  val isTransientFailure = errorKind match {
                    case RetryUtil.NoErrorKind => false
                    case RetryUtil.TransientErrorKind => true
                    case RetryUtil.FatalErrorKind => false
                    case RetryUtil.SpuriousTransientErrorKind => true
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
                val workResult = performWork
                current match {
                  case Some(ref) =>
                    Some(
                      ref.flatMap(_ => workResult)
                    )
                  case None => Some(workResult)
                }
              }
              .foreach(_.foreach { case (state, workDone) =>
                if (workDone && !context.retryProvider.isClosing) {
                  pollingLoop(state)
                } else {
                  loopWithDelay(state)
                }
              })
          }
        }
        logger.debug(
          show"Starting trigger polling loop, ${PollingTrigger.ConfigSummary(context.config)}"
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

  private case class ConfigSummary(config: AutomationConfig) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("pollingInterval", _.config.pollingInterval),
        param("pollingJitter", _.config.pollingJitter.toString.unquoted),
        param("maxNumSilentPollingRetries", _.config.maxNumSilentPollingRetries),
      )
    }
  }
}
