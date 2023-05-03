package com.daml.network.automation

import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.util.retry.RetryUtil.ErrorKind
import akka.Done
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.util.LoggerUtil

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.util.{Failure, Success}

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

  private val retryable = RetryProvider.RetryableError(
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
                  context.config.pollingInterval.asJava,
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
              if (context.retryProvider.isClosing) {
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
