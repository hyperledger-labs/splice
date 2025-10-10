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
  Round,
}

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait RoundBasedDelayedFutureScheduler extends DelayedFutureScheduler with NamedLogging {

  private val lastRoundWeRanFor =
    new java.util.concurrent.atomic.AtomicReference[Option[Long]](None)

  def retryProvider: RetryProvider

  def clock: Clock

  def config: AutomationConfig

  private def scheduleBetween(
      selectedRound: Round,
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
      s"Schedling next poll at $scheduleAt for round $selectedRound interval $minSchedulingTime -> $maxSchedulingTime"
    )

    retryProvider.scheduleAtUnlessShutdown(
      Future.successful(action),
      clock,
      CantonTimestamp.tryFromInstant(
        scheduleAt
      ),
    )
  }

  protected def scheduleBasedOnRegularInterval(
      action: => Unit
  )(implicit ec: ExecutionContext): FutureUnlessShutdown[Unit] = {
    retryProvider.scheduleAfterUnlessShutdown(
      Future.successful(action),
      clock,
      config.rewardOperationPollingInterval,
      config.rewardOperationPollingJitter,
    )

  }

  protected def scheduleWithPossibleMissingRound(
      action: => Unit,
      selectedRound: Round,
      rounds: Seq[Round],
      maxSchedulingTime: Instant,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): FutureUnlessShutdown[Unit] = {
    val previousRoundWeRanFor = lastRoundWeRanFor.get()
    val isThereAMissedRound = previousRoundWeRanFor.exists(_ < selectedRound.number - 1)
    if (isThereAMissedRound) {
      rounds
        .filter(_.number > previousRoundWeRanFor.get)
        .minByOption(_.number) match {
        case Some(round) =>
          logger.warn(
            s"For last round $previousRoundWeRanFor we have detected missed round $round"
          )
          scheduleBetween(
            selectedRound,
            action,
            Seq(round.opensAt, clock.now.toInstant).max,
            Seq(round.targetClosesAt.minusSeconds(30), clock.now.toInstant).max,
          )
        case None =>
          logger.warn(
            s"For last round $previousRoundWeRanFor we have detected missed rounds for $rounds but no newer round is found. Scheduling with regular interval"
          )
          scheduleBasedOnRegularInterval(action)
      }
    } else {
      lastRoundWeRanFor.set(Some(selectedRound.number))
      val minSchedulingTimeForOpenRound = selectedRound.opensAt
      scheduleBetween(
        selectedRound,
        action,
        minSchedulingTimeForOpenRound,
        maxSchedulingTime,
      )
    }

  }
}

class OpenRoundBasedUniformFutureScheduler(
    openRoundReader: OpenRoundReader,
    val config: AutomationConfig,
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
            scheduleWithPossibleMissingRound(
              action,
              Round(
                opensAt = round.opensAt,
                targetClosesAt = round.targetClosesAt,
                number = round.round.number,
              ),
              openRounds.map(r =>
                Round(
                  opensAt = r.opensAt,
                  targetClosesAt = r.targetClosesAt,
                  number = r.round.number,
                )
              ),
              round.opensAt.plus(maxSchedulingInterval(round)),
            )
          case None =>
            logger.info("No future open round, scheduling next poll with fixed interval")
            scheduleBasedOnRegularInterval(action)
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
    val config: AutomationConfig,
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
            scheduleWithPossibleMissingRound(
              action,
              Round(
                opensAt = round.opensAt,
                targetClosesAt = round.targetClosesAt,
                number = round.round.number,
              ),
              futureRounds.map(r =>
                Round(
                  opensAt = r.opensAt,
                  targetClosesAt = r.targetClosesAt,
                  number = r.round.number,
                )
              ),
              maxSchedulingTimeForRound(round, issuingRounds),
            )
          case None =>
            logger.info("No future issuing round, scheduling next poll with fixed interval")
            scheduleBasedOnRegularInterval(action)
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

  case class Round(
      opensAt: Instant,
      targetClosesAt: Instant,
      number: Long,
  )
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
