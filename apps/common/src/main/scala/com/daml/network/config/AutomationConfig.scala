package com.daml.network.config

import com.daml.network.automation.Trigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration

import scala.reflect.ClassTag

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
    /** Percentage of the polling interval to use for randomly distributing the actual polling duration.
      *
      * The duration used is sampled uniformly at random from
      * `[pollingInterval * (1 - 0.5*pollingJitter), pollingInterval * (1 + 0.5 * pollingJitter)]`
      */
    pollingJitter: Double = 0.2,
    /** Reward operations can result in spikes overloading sequencers on each round switch so we
      * use a lower polling interval of 1/2 tick with tick = 600s
      */
    rewardOperationPollingInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(300),
    /** Reward operations can result in spikes overloading sequencers on each round switch so we
      * use higher jitter.
      */
    rewardOperationPollingJitter: Double = 0.5,
    /** Polling interval used for the top-up trigger.
      *  Only set as different from the base pollingInterval for some tests.
      * This allows running the top-up trigger at a higher frequency to ensure that a validator's first top-up gets
      * done quickly enough during init preventing tests from failing due to lack of traffic.
      */
    topupTriggerPollingInterval: Option[NonNegativeFiniteDuration] = None,
    /** Maximal number of retries that the time-based triggers retry transient failures w/o raising a warning.
      */
    maxNumSilentPollingRetries: Int = 3,
    /** Only intended for testing. Disables the polling trigger that periodically collects rewards
      * and merges amulets.
      */
    enableAutomaticRewardsCollectionAndAmuletMerging: Boolean = true,
    /** Only intended for testing. Disables the expiration of Amulet.
      */
    enableExpireAmulet: Boolean = false,
    /** Only intended for testing. Allows disabling leader elections based on inactivity detection for simtime tests so elections are not triggered unexpectedly.
      */
    enableLeaderReplacementTrigger: Boolean = true,
    /** Only intended for testing. Allows disabling governance automation.
      */
    enableDsoGovernance: Boolean = true,
    /** Only intended for testing. Allows disabling archival of closed rounds.
      */
    enableClosedRoundArchival: Boolean = true,
    /** Only intended for testing. Allows disabling cometbft config
      * publish/reconcile automation.
      */
    enableCometbftReconciliation: Boolean = true,
    /** List of triggers (identified by the name of the corresponding scala
      * class) that start in a paused state. Unless such a trigger is resumed manually, it is
      * guaranteed to never perform any work.
      */
    pausedTriggers: Set[String] = Set.empty,
) {
  def withPausedTrigger[T <: Trigger](implicit tag: ClassTag[T]): AutomationConfig = copy(
    pausedTriggers = pausedTriggers + tag.runtimeClass.getCanonicalName
  )
  def withResumedTrigger[T <: Trigger](implicit tag: ClassTag[T]): AutomationConfig = copy(
    pausedTriggers = pausedTriggers - tag.runtimeClass.getCanonicalName
  )
}
