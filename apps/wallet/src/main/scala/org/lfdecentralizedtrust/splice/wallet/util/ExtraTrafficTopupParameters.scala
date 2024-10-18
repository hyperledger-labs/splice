// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}

import scala.math.BigDecimal.RoundingMode

/** Parameters used by the top-up automation to buy extra traffic for the validator.
  *
  * These values are computed taking into account both the global domain fees configuration published by the DSO
  * as well as the local configuration of the validator app done by the validator operator using the
  * constructor defined in the companion object for this class.
  *
  * @param topupAmount amount of extra traffic purchased on each top-up
  * @param minTopupInterval minimal time duration between two successive top-ups
  */
case class ExtraTrafficTopupParameters(
    topupAmount: Long,
    minTopupInterval: NonNegativeFiniteDuration,
) extends PrettyPrinting {
  override def pretty: Pretty[ExtraTrafficTopupParameters.this.type] = prettyOfClass(
    param("topupAmount", _.topupAmount),
    param("minTopupInterval", _.minTopupInterval),
  )
}

object ExtraTrafficTopupParameters {
  def apply(
      targetThroughput: NonNegativeNumeric[BigDecimal],
      minTopupInterval: NonNegativeFiniteDuration,
      minTopupAmount: Long,
      topupTriggerPollingInterval: NonNegativeFiniteDuration,
  ): ExtraTrafficTopupParameters = {
    val targetRateBytesPerSecond = targetThroughput.value
    if (targetRateBytesPerSecond <= 0L) {
      // the topup interval in this case is irrelevant
      ExtraTrafficTopupParameters(0L, NonNegativeFiniteDuration.ofSeconds(0))
    } else {
      // ensure minTopupInterval is at least equal to the polling interval
      val minTopupInterval_ =
        maximumOfDuration(minTopupInterval, topupTriggerPollingInterval)
      val expectedTopupParameters = roundUpIntervalAndCalculateAmount(
        targetRateBytesPerSecond,
        minTopupInterval_,
        topupTriggerPollingInterval,
      )
      if (expectedTopupParameters.topupAmount >= minTopupAmount)
        expectedTopupParameters
      else {
        // If the minTopupAmount is higher than expectedTopupAmount, adjust the topupInterval to
        // provide target traffic rate.
        // Note that the target rate is greater than the base rate at this point => the denominator is positive.
        val topupIntervalMillis = (
          BigDecimal(minTopupAmount) / targetRateBytesPerSecond * 1e3
        ).setScale(0, RoundingMode.CEILING).toLong
        roundUpIntervalAndCalculateAmount(
          targetRateBytesPerSecond,
          NonNegativeFiniteDuration.ofMillis(topupIntervalMillis),
          topupTriggerPollingInterval,
        )
      }
    }
  }

  def apply(
      validatorTopupConfig: ValidatorTopupConfig,
      minTopupAmount: Long,
  ): ExtraTrafficTopupParameters =
    apply(
      validatorTopupConfig.targetThroughput,
      validatorTopupConfig.minTopupInterval,
      minTopupAmount,
      validatorTopupConfig.topupTriggerPollingInterval,
    )

  private def maximumOfDuration(
      duration1: NonNegativeFiniteDuration,
      duration2: NonNegativeFiniteDuration,
  ) =
    if (duration1.duration >= duration2.duration) duration1 else duration2

  private def roundUpIntervalAndCalculateAmount(
      targetRateBytesPerSecond: BigDecimal,
      topupInterval: NonNegativeFiniteDuration,
      topupTriggerPollingInterval: NonNegativeFiniteDuration,
  ) = {
    val topupIntervalMillis = topupInterval.duration.toMillis
    val pollingIntervalMillis = topupTriggerPollingInterval.duration.toMillis
    // round topupInterval up to the nearest multiple of the pollingInterval to determine when the
    // next top-up is expected to occur.
    val multiple = (topupIntervalMillis + pollingIntervalMillis - 1) / pollingIntervalMillis
    val nextTopupAfterMillis = multiple * pollingIntervalMillis
    // calculate topupAmount as the amount of traffic needed to deliver target rate till next top-up
    val topupAmountBytes =
      (targetRateBytesPerSecond / 1e3 * nextTopupAfterMillis)
        .setScale(0, RoundingMode.CEILING)
        .toLong
    ExtraTrafficTopupParameters(
      topupAmountBytes,
      NonNegativeFiniteDuration.ofMillis(nextTopupAfterMillis),
    )
  }
}

case class ValidatorTopupConfig(
    targetThroughput: NonNegativeNumeric[BigDecimal],
    minTopupInterval: NonNegativeFiniteDuration,
    topupTriggerPollingInterval: NonNegativeFiniteDuration,
)
