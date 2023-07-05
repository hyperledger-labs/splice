package com.daml.network.validator.util

import com.daml.network.codegen.java.cc.globaldomain.DomainFeesConfig
import com.daml.network.validator.config.BuyExtraTrafficConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration

import scala.math.BigDecimal.RoundingMode

/** Parameters used by the top-up automation to buy extra traffic for the validator.
  *
  * These values are computed taking into account both the global domain fees configuration published by the SVC
  * as well as the local configuration of the validator app done by the validator operator using the
  * constructor defined in the companion object for this class.
  *
  * @param topupAmount amount of extra traffic purchased on each top-up
  * @param minTopupInterval minimal time duration between two successive top-ups
  */
case class ExtraTrafficTopupParameters(
    topupAmount: Long,
    minTopupInterval: NonNegativeFiniteDuration,
)

object ExtraTrafficTopupParameters {
  def apply(
      globalConfig: DomainFeesConfig,
      validatorConfig: BuyExtraTrafficConfig,
      topupTriggerPollingInterval: NonNegativeFiniteDuration,
  ): ExtraTrafficTopupParameters = {
    val targetRateBytesPerSecond = validatorConfig.targetThroughput.value
    if (targetRateBytesPerSecond <= 0L) {
      // the topup interval in this case is irrelevant
      ExtraTrafficTopupParameters(0L, NonNegativeFiniteDuration.ofSeconds(0))
    } else {
      // ensure minTopupInterval is at least equal to the polling interval
      val minTopupInterval =
        maximumOfDuration(validatorConfig.minTopupInterval, topupTriggerPollingInterval)
      val expectedTopupParameters = roundUpIntervalAndCalculateAmount(
        targetRateBytesPerSecond,
        minTopupInterval,
        topupTriggerPollingInterval,
      )
      if (expectedTopupParameters.topupAmount >= globalConfig.minTopupAmount)
        expectedTopupParameters
      else {
        // if the minTopupAmount is higher than expectedTopupAmount, adjust the topupInterval to
        // provide target traffic rate.
        // Note that the target rate is greater than the base rate at this point => the denominator is positive.
        val topupIntervalMillis = (
          BigDecimal(
            globalConfig.minTopupAmount
          ) / targetRateBytesPerSecond * 1e3
        ).setScale(0, RoundingMode.CEILING).toLong
        roundUpIntervalAndCalculateAmount(
          targetRateBytesPerSecond,
          NonNegativeFiniteDuration.ofMillis(topupIntervalMillis),
          topupTriggerPollingInterval,
        )
      }
    }
  }

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
