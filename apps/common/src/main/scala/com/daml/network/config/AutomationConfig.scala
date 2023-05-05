package com.daml.network.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

case class AutomationConfig(
    /** How many automation tasks should be run in parallel per kind of task. */
    parallelism: Int = 4,
    /** Duration that time-based automations are delayed by to account for possible clock skew between the wall clock
      *  used by the automation and the respective domain that the automation sequences ledger events across.
      */
    clockSkewAutomationDelay: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(5),
    /** Interval at which time-based automation triggers
      */
    pollingInterval: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(30),
    /** Duration after which leader is considered to be inactive
      */
    leaderInactiveTimeout: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(70),

    /** Only intended for testing. Disables the polling trigger that periodically collects rewards
      * and merges coins.
      */
    enableAutomaticRewardsCollectionAndCoinMerging: Boolean = true,
    /** TODO(M3-63) Disables the attempt to expire unclaimed rewards. Remove this once we are resilient to unavailable validators
      */
    enableUnclaimedRewardExpiration: Boolean = false,
    /** Only intended for testing. Disables the validator traffic balance topup loop except in domain fees related tests.
      * TODO(#3816) cleanup this flag after DomainFees PoC and replace with a per-validator spend limit
      */
    enableAutomaticValidatorTrafficBalanceTopup: Boolean = false,
) {

  /** Effective duration after which leader inactivity is detected
    */
  // TODO(#4440) Leader inactivity timeout configuration is SvApp-specific. Move this into a config value specific to the SvApp
  def effectiveLeaderInactiveTimeout: NonNegativeFiniteDuration =
    NonNegativeFiniteDuration.apply(
      leaderInactiveTimeout.duration
        .plus(pollingInterval.duration)
    )
}
