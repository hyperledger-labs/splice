// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.RoundBasedRewardTrigger.RoundBasedTask

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

abstract class RoundBasedRewardTrigger[T <: RoundBasedTask: Pretty]()(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[T] {
  private val nextRunTime = new AtomicReference[Option[(Long, Instant)]](None)

  private val isNewSchedulingLogicEnabled: Boolean = context.config.enableNewRewardTriggerScheduling

  // if the new logic is disable then use the old behaviour that uses increased polling intervals
  override protected def isRewardOperationTrigger: Boolean = !isNewSchedulingLogicEnabled

  override protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[T]] = {
    if (isNewSchedulingLogicEnabled) {
      if (shouldRun) {
        retrieveAvailableTasksForRound().map(tasks => {
          tasks.minByOption(_.roundDetails._1) match {
            case Some(firstRound) =>
              val (roundNumber, roundOpening) = firstRound.roundDetails
              val lastRunWasForAnOlderRound = nextRunTime.get().forall(_._1 < roundNumber)
              if (lastRunWasForAnOlderRound) {
                @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
                val minRunTime = Seq(roundOpening, context.clock.now.toInstant).max
                val maxRunTime = roundOpening.plus(firstRound.tickDuration)
                val minRunAt = randomInstantBetween(minRunTime, maxRunTime)
                nextRunTime.set(
                  Some(
                    roundNumber -> minRunAt
                  )
                )
                if (shouldRun) {
                  logger.info(
                    s"Running for $tasks because the calculated run time $minRunAt is now (between $minRunTime and $maxRunTime)."
                  )
                  tasks
                } else {
                  logger.info(
                    s"Running for $tasks at min time $minRunAt (computed for interval between $minRunTime and $maxRunTime)."
                  )
                  Seq.empty
                }
              } else {
                logger.info(
                  s"Running for $tasks as the round still matches the next run ${nextRunTime.get()}."
                )
                tasks
              }
            case None =>
              // no tasks available
              tasks
          }
        })
      } else Future.successful(Seq.empty)
    } else {
      retrieveAvailableTasksForRound()
    }
  }

  protected def retrieveAvailableTasksForRound()(implicit tc: TraceContext): Future[Seq[T]]

  private def shouldRun = {
    nextRunTime
      .get()
      .fold(true) { case (_, runAt) =>
        runAt.isAfter(context.clock.now.toInstant)
      }
  }

  private def randomInstantBetween(start: Instant, end: Instant): Instant = {
    if (end.isBefore(start) || start == end)
      start
    else {
      val range = Duration.between(start, end)
      val randomMillisInRange = Random.nextLong(range.toMillis)
      start.plusMillis(randomMillisInRange)
    }
  }

}

object RoundBasedRewardTrigger {

  trait RoundBasedTask {
    def roundDetails: (Long, Instant)
    def tickDuration: Duration
  }
}
