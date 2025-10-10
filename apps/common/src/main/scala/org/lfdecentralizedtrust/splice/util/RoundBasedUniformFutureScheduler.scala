// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.PollingTrigger.DelayedFutureScheduler
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.util.RoundBasedUniformFutureScheduler.OpenRoundReader

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

class RoundBasedUniformFutureScheduler(
    openRoundReader: OpenRoundReader,
    clock: Clock,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
) extends DelayedFutureScheduler
    with NamedLogging {

  override def scheduleNextPoll(
      action: => Unit
  )(implicit ec: ExecutionContext, tc: TraceContext): FutureUnlessShutdown[Unit] = {
    FutureUnlessShutdown
      .outcomeF(
        openRoundReader.getOpenRounds
      )
      .flatMap { openRounds =>
        val now = clock.now.toInstant
        @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
        val firstOpenRound = openRounds.minBy(round => {
          if (round.opensAt.isAfter(now)) {
            round.opensAt
          } else round.targetClosesAt
        })
        val minSchedulingTimeForOpenRound = if (firstOpenRound.opensAt.isAfter(now)) {
          firstOpenRound.opensAt
        } else {
          firstOpenRound.targetClosesAt
        }
        // the triggers always do a run during startup so we can ignore that scenario as it would cover any rounds missed previosly
        // therefore we always schedule just for the next open round
        scheduleBetween(
          action,
          minSchedulingTimeForOpenRound,
          minSchedulingTimeForOpenRound.plus(maxSchedulingInterval(firstOpenRound)),
        )
      }
  }

  private def maxSchedulingInterval(
      openRound: OpenMiningRound
  ) = {
    java.time.Duration.of(
      openRound.tickDuration.microseconds,
      ChronoUnit.MICROS,
    )
  }

  private def scheduleBetween(
      action: => Unit,
      minSchedulingTime: Instant,
      maxSchedulingTime: Instant,
  )(implicit ec: ExecutionContext, tc: TraceContext) = {
    def nanosBetweenSchedulingInterval = {
      java.time.Duration
        .between(
          minSchedulingTime,
          maxSchedulingTime,
        )
        .abs
        .toNanos
    }
    val delayFromMinSchedulingTime = java.time.Duration.ofNanos(
      Random.nextLong(
        nanosBetweenSchedulingInterval
      )
    )
    val scheduleAt = minSchedulingTime.plus(delayFromMinSchedulingTime)

    logger.info(
      s"Schedling next poll at $scheduleAt for interval $minSchedulingTime -> $maxSchedulingTime"
    )

    retryProvider.scheduleAtUnlessShutdown(
      Future.successful(action),
      clock,
      CantonTimestamp.tryFromInstant(
        scheduleAt
      ),
    )
  }
}

object RoundBasedUniformFutureScheduler {
  trait OpenRoundReader {
    def getOpenRounds(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Seq[OpenMiningRound]]
  }
}
