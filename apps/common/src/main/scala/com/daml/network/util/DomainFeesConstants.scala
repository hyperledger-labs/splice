package com.daml.network.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveDouble}

/** Temporarily hard-coded values for Domain Fees PoC.
  * TODO(#3816): Revisit this as part of the clean-up after PoC.
  */
object DomainFeesConstants {

  /** target throughput in bytes per second
    *
    * TODO(#4324): This will eventually be configured by each validator operator.
    */
  val targetThroughput: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(1000.0) // 1kBps

  /** minimum interval between extra traffic purchases in seconds
    *
    * This allows validator operators to control the frequency at which the top-up trigger
    * will charge them domain fees making spends more predictable. This must be greater than the
    * polling interval of the top-up trigger.
    *
    * TODO(#4324): This will eventually be configured by each validator operator.
    */
  val minTopupInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofMinutes(5)

  val assumedCoinTxSizeBytes: PositiveDouble = PositiveDouble.tryCreate(20_000.0) // 20kB

}
