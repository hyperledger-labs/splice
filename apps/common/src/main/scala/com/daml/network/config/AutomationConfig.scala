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
    /** Only intended for testing. Disables the polling trigger that periodically collects rewards
      * and merges coins.
      */
    enableAutomaticRewardsCollectionAndCoinMerging: Boolean = true,
    /** TODO(M3-63) Disables the attempt to expire unclaimed rewards. Remove this once we are resilient to unavailable validators
      */
    enableUnclaimedRewardExpiration: Boolean = false,
    /** Only intended for testing. Disables the generation of SV rewards.
      */
    enableSvRewards: Boolean = true,
    /** Only intended for testing. Disables the expiration of Coin.
      */
    enableExpireCoin: Boolean = false,
    /** Only intended for testing. Allows disabling leader elections based on inactivity detection for simtime tests so elections are not triggered unexpectedly.
      */
    enableLeaderReplacement: Boolean = true,
    /** Only intended for testing. Allows disabling governance automation.
      */
    enableSvcGovernance: Boolean = true,
    /** Only intended for testing. Allows disabling archival of closed rounds.
      */
    enableClosedRoundArchival: Boolean = true,
    /** Only intended for testing. Allows disabling cometbft config
      * publish/reconcile automation.
      */
    enableCometbftReconciliation: Boolean = true,
    /** Only intended for PoC. Enables using MemberTraffic contracts instead of ValidatorTraffic contracts for domain fees.
      *
      * TODO(#7081): Remove this once we've switched over entirely to MemberTraffic contracts
      */
    useMemberTrafficInsteadOfValidatorTraffic: Boolean = false,
) {}
