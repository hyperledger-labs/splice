package com.daml.network.util

import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveNumeric}

/** Temporarily hard-coded values for Domain Fees PoC.
  * TODO(#3816): Revisit this as part of the clean-up after PoC.
  */
object DomainFeesConstants {

  /** default thronughput in traffic units per second
    * This will eventually be configured on the ledger in the CoinRules contract.
    */
  val defaultThroughput: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(2.0)

  /** burst window size for default traffic in seconds
    * This will eventually be configured on the ledger in the CoinRules contract.
    */
  val defaultTrafficBurstWindow: PositiveNumeric[Double] = PositiveNumeric.tryCreate(2.0)

  /** target throughput in traffic units per second
    * This will eventually be configured by each validator operator.
    */
  val targetThroughput: NonNegativeNumeric[Double] = NonNegativeNumeric.tryCreate(5.0)
}
