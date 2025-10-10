// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.PollingTrigger.DelayedFutureScheduler
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  IssuingMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.util.RoundBasedDelayedFutureScheduler.{
  IssuingRoundReader,
  OpenRoundReader,
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait RoundBasedDelayedFutureScheduler extends DelayedFutureScheduler with NamedLogging {

  def retryProvider: RetryProvider
  def clock: Clock

  protected def scheduleBetween(
      action: => Unit,
      minSchedulingTime: Instant,
      maxSchedulingTime: Instant,
  )(implicit ec: ExecutionContext, tc: TraceContext): FutureUnlessShutdown[Unit] = {
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

class OpenRoundBasedUniformFutureScheduler(
    openRoundReader: OpenRoundReader,
    config: AutomationConfig,
    val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
) extends RoundBasedDelayedFutureScheduler {

  override def scheduleNextPoll(
      action: => Unit
  )(implicit ec: ExecutionContext, tc: TraceContext): FutureUnlessShutdown[Unit] = {
    retryProvider
      .waitUnlessShutdown(
        openRoundReader.getOpenRounds
      )
      .flatMap { openRounds =>
        val firstRoundThatWillOpenInTheFuture =
          openRounds.filter(_.opensAt.isAfter(clock.now.toInstant)).minByOption(_.opensAt)
        firstRoundThatWillOpenInTheFuture match {
          case Some(round) =>
            val minSchedulingTimeForOpenRound = round.opensAt
            // the triggers always do a run during startup so we can ignore that scenario as it would cover any rounds missed previosly
            // therefore we always schedule just for the next open round
            scheduleBetween(
              action,
              minSchedulingTimeForOpenRound,
              minSchedulingTimeForOpenRound.plus(maxSchedulingInterval(round)),
            )
          case None =>
            logger.warn("No future open round, scheduling next poll with fixed interval")
            retryProvider.scheduleAfterUnlessShutdown(
              Future.successful(action),
              clock,
              config.rewardOperationPollingInterval,
              config.rewardOperationPollingJitter,
            )
        }
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

}

class IssuingRoundBasedUniformFutureScheduler(
    roundReader: IssuingRoundReader,
    config: AutomationConfig,
    val clock: Clock,
    val retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
) extends RoundBasedDelayedFutureScheduler {

  override def scheduleNextPoll(
      action: => Unit
  )(implicit ec: ExecutionContext, tc: TraceContext): FutureUnlessShutdown[Unit] = {
    retryProvider
      .waitUnlessShutdown(
        roundReader.getIssuingRounds
      )
      .flatMap { issuingRounds =>
        val futureRounds = issuingRounds.filter(_.opensAt.isAfter(clock.now.toInstant))
        futureRounds.minByOption(_.opensAt) match {
          case Some(round) =>
            val minSchedulingTimeForOpenRound = round.opensAt
            // the triggers always do a run during startup so we can ignore that scenario as it would cover any rounds missed previosly
            // therefore we always schedule just for the next open round
            scheduleBetween(
              action,
              minSchedulingTimeForOpenRound,
              maxSchedulingTimeForRound(round, futureRounds),
            )
          case None =>
            logger.warn("No future issuing round, scheduling next poll with fixed interval")
            retryProvider.scheduleAfterUnlessShutdown(
              Future.successful(action),
              clock,
              config.rewardOperationPollingInterval,
              config.rewardOperationPollingJitter,
            )
        }
      }
  }
  private def maxSchedulingTimeForRound(
      round: IssuingMiningRound,
      otherRounds: Seq[IssuingMiningRound],
  ) = {
    otherRounds.filter(_.round != round.round).minByOption(_.opensAt) match {
      case Some(nextOpeningRound) =>
        nextOpeningRound.opensAt
      case None =>
        round.targetClosesAt
    }
  }
}

object RoundBasedDelayedFutureScheduler {
  trait OpenRoundReader {
    def getOpenRounds(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Seq[OpenMiningRound]]
  }
  trait IssuingRoundReader {
    def getIssuingRounds(implicit
        ec: ExecutionContext,
        tc: TraceContext,
    ): Future[Seq[IssuingMiningRound]]
  }
}
