// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.RoundBasedRewardTrigger.RoundBasedTask

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

/** Returns to actual tasks to be executed based on the rounds of the task.
  * It pessimistically tries to execute the tasks in the first tick of the first open round that it did not already ran for in the past.
  * It chooses a random time between the round open time (or now if the rounds is already opened) and either one tick in the future or the close time of the previous round minus a buffer (to ensure we run at least twice for the same round).
  * The trigger will respect the parallel polling trigger behavior and execute until no work is left to be done. From that point it will not run again for the same round (we can't tell if the execution failed, but the next round will cover it again just in case).``
  * -
  */
abstract class RoundBasedRewardTrigger[T <: RoundBasedTask: Pretty]()(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[T] {
  private val triggerState =
    new AtomicReference[Option[RoundBasedRewardTrigger.RoundBasedTriggerState]](None)

  protected val isNewSchedulingLogicEnabled: Boolean =
    context.config.enableNewRewardTriggerScheduling

  // if the new logic is disable then use the old behaviour that uses increased polling intervals
  override protected def isRewardOperationTrigger: Boolean = !isNewSchedulingLogicEnabled

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[T]] = {
    if (isNewSchedulingLogicEnabled) {
      if (shouldRun) {
        val tasksToRun = retrieveAvailableTasksForRound()
        if (triggerState.get().exists(_.workStillToBeDone)) {
          logger.info(s"Running tasks ${triggerState.get()}")
          updateState(_.copy(startedWork = true))
          tasksToRun
        } else {
          tasksToRun.map(tasks => {
            val tasksToUseForScheduling =
              tasks.filter(task =>
                triggerState.get().forall { state =>
                  task.roundNumber > state.roundNumber
                }
              )
            def scheduleTasksBetween(
                roundNumber: Long,
                minRunTime: Instant,
                maxRunTime: Instant,
            ) = {
              val minScheduledTimeToRunAt = randomInstantBetween(
                minRunTime,
                maxRunTime,
              )
              triggerState.set(
                Some(
                  RoundBasedRewardTrigger.RoundBasedTriggerState(
                    roundNumber,
                    minScheduledTimeToRunAt,
                    startedWork = false,
                    workStillToBeDone = true,
                  )
                )
              )
              if (shouldRun) {
                logger.info(
                  s"Running for $tasks because the calculated run time $minScheduledTimeToRunAt is now (between $minRunTime and $maxRunTime)."
                )
                updateState(_.copy(startedWork = true))
                tasks
              } else {
                logger.info(
                  s"Will run for $tasks at min time $minScheduledTimeToRunAt (computed for interval between $minRunTime and $maxRunTime)."
                )
                Seq.empty
              }
            }

            val previousSchedulingRoundStillOpen =
              tasks
                .find(task => triggerState.get().map(_.roundNumber).contains(task.roundNumber))
                .filter(_.closesAt.isAfter(context.clock.now.toInstant))
            val schedulingRound = tasksToUseForScheduling
              .minByOption(_.opensAt)

            def closingTimeForRoundWIthBuffer(round: T) = {
              round.closesAt.minus(
                context.config.rewardOperationRoundsCloseBufferDuration.asJava
              )
            }

            schedulingRound match {
              case Some(schedulingRound) =>
                val minRunTime = Seq(schedulingRound.opensAt, context.clock.now.toInstant).max
                val previousRoundClosingTimeWithBuffer = previousSchedulingRoundStillOpen
                  .map(round => closingTimeForRoundWIthBuffer(round))
                val maxRunTime = (
                  previousRoundClosingTimeWithBuffer.toList :+ schedulingRound.scheduleAtMaxTargetTime
                ).min
                scheduleTasksBetween(schedulingRound.roundNumber, minRunTime, maxRunTime)
              case None =>
                val state = triggerState.get()
                previousSchedulingRoundStillOpen match {
                  case Some(previousRound) =>
                    val triggerRanForPreviousRound = state.exists(_.startedWork)
                    val triggerHasNoWorkLeftForPreviousRound = state.exists(!_.workStillToBeDone)
                    val previousRoundWasScheduledWithinTheWantedInterval = state
                      .exists(
                        _.earliestTimeTriggerCanRun.isBefore(previousRound.scheduleAtMaxTargetTime)
                      )
                    // task is still available but we already ran within the wanted timeframe, this means the task most likely failed, so we should reschedule before the closing time
                    if (
                      triggerRanForPreviousRound && triggerHasNoWorkLeftForPreviousRound && previousRoundWasScheduledWithinTheWantedInterval
                    ) {
                      val maxSchedulingTime = closingTimeForRoundWIthBuffer(previousRound)
                      logger.info(
                        s"Rescheduling for previous round ${previousRound.roundNumber} that is still open and we already ran for, before closing time with buffer $maxSchedulingTime."
                      )
                      scheduleTasksBetween(
                        previousRound.roundNumber,
                        context.clock.now.toInstant,
                        maxSchedulingTime,
                      )
                    } else {
                      logger.warn(
                        s"Trigger ran before round closing time but task is stil available, will not rerun ${triggerState.get()}."
                      )
                      Seq.empty
                    }
                  case None =>
                    logger.debug(
                      s"No new rounds to schedule for, last ran for ${triggerState.get()}."
                    )
                    Seq.empty
                }
            }
          })
        }
      } else Future.successful(Seq.empty)
    } else {
      retrieveAvailableTasksForRound()
    }
  }

  private def updateState(
      u: RoundBasedRewardTrigger.RoundBasedTriggerState => RoundBasedRewardTrigger.RoundBasedTriggerState
  ): Unit =
    triggerState.updateAndGet(_.map(u)).discard

  protected def retrieveAvailableTasksForRound()(implicit tc: TraceContext): Future[Seq[T]]

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    super.performWorkIfAvailable().map { workStillNeedsToBeDone =>
      updateState { state =>
        if (state.startedWork) {
          state.copy(
            workStillToBeDone = workStillNeedsToBeDone
          )
        } else state
      }
      workStillNeedsToBeDone
    }

  private def shouldRun = {
    triggerState
      .get()
      .fold(true) { state =>
        state.earliestTimeTriggerCanRun.isBefore(
          context.clock.now.toInstant
        ) || state.earliestTimeTriggerCanRun.equals(
          context.clock.now.toInstant
        )
      }
  }

  private def randomInstantBetween(start: Instant, end: Instant): Instant = {
    val range = Duration.between(start, end)
    if (start.isAfter(end) || range.toMillis <= 0)
      start
    else {
      val randomMillisInRange = Random.nextLong(range.toMillis)
      start.plusMillis(randomMillisInRange)
    }
  }

}

object RoundBasedRewardTrigger {

  private final case class RoundBasedTriggerState(
      roundNumber: Long,
      // the earliest time the trigger is allowed to run for this round, it can run multiple times after that depending on the value of `workStillToBeDone`
      earliestTimeTriggerCanRun: Instant,
      startedWork: Boolean,
      // starts with true and is set to the value of workDone returned by the trigger
      workStillToBeDone: Boolean,
  )

  trait RoundBasedTask {
    def roundNumber: Long
    def opensAt: Instant
    def scheduleAtMaxTargetTime: Instant
    def closesAt: Instant

  }
}
