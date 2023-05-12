package com.daml.network.util

import com.digitalasset.canton.config.RequireTypes.PositiveDouble

/** Temporarily hard-coded values for Domain Fees PoC.
  * TODO(#3816): Revisit this as part of the clean-up after PoC.
  */
object DomainFeesConstants {

  val assumedCoinTxSizeBytes: PositiveDouble = PositiveDouble.tryCreate(20_000.0) // 20kB

}
