package com.daml.network.util

import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveNumeric}

/** Temporarily hard-coded values for Domain Fees PoC.
  * TODO(#3816): Revisit this as part of the clean-up after PoC.
  */
object DomainFeesConstants {

  /** default thronughput in traffic units per second
    *
    * TODO(#4325): This will eventually be configured on the ledger in the SvcRules contract.
    */
  val defaultThroughput: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(2.0)

  /** burst window size for default traffic in seconds
    *
    * TODO(#4325): This will eventually be configured on the ledger in the SvcRules contract.
    */
  val defaultTrafficBurstWindow: PositiveNumeric[Double] = PositiveNumeric.tryCreate(2.0)

  /** target throughput in traffic units per second
    *
    * TODO(#4324): This will eventually be configured by each validator operator.
    */
  val targetThroughput: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(5.0)

  /** minimum interval between extra traffic purchases in seconds
    *
    * This allows validator operators to control the frequency at which the top-up trigger
    * will charge them domain fees making spends more predictable. This must be greater than the
    * polling interval of the top-up trigger.
    *
    * TODO(#4324): This will eventually be configured by each validator operator.
    */
  val minTopupWaitTime: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(5.0)

  /** minimum extra traffic topup amount in MB
    *
    * Fixed global parameter that would allow controlling the frequency of top-up transactions.
    * TODO(#4325): This will eventually be configured on the ledger in the CoinRules contract.
    */
  val minTopupAmount: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(16.0)

}
