// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.lfdecentralizedtrust.splice.automation.Trigger
import com.digitalasset.canton.config.{NonNegativeFiniteDuration, PositiveFiniteDuration}
import com.digitalasset.canton.topology.PartyId

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
      * use a lower polling interval of 1/3 tick with tick = 600s
      */
    rewardOperationPollingInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration.ofSeconds(200),
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
    /** Polling interval to check for domain connection changes.
      * There are a large number of domain stores, e.g. one per user on the
      * validator app; with few domain changes, more checks aren't needed.
      */
    domainIngestionPollingInterval: NonNegativeFiniteDuration =
      NonNegativeFiniteDuration ofSeconds 30,
    /** Polling interval to recompute and export topology metrics.
      *
      * Set to None to disable the topology metrics trigger.
      */
    topologyMetricsPollingInterval: Option[NonNegativeFiniteDuration] = None,
    /** Maximal number of retries that the time-based triggers retry transient failures w/o raising a warning.
      */
    maxNumSilentPollingRetries: Int = 3,
    /** Only intended for testing. Disables the polling trigger that periodically collects rewards
      * and merges amulets.
      */
    enableAutomaticRewardsCollectionAndAmuletMerging: Boolean = true,
    /** Allows disabling expiration of validator faucets. This is currently required
      * as this does not work with unavailable validators.
      * TODO(DACH-NY/canton-network-node#11828) Remove this option
      */
    enableExpireValidatorFaucet: Boolean = false,
    /** Only intended for testing. Disables the expiration of Amulet.
      */
    enableExpireAmulet: Boolean = false,
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

    /** Maximum allowed domain delay before automation is paused.
      * Defaults to 2min = participant delay + polling interval + max domain time lag.
      */
    maxAllowedDomainTimeDelay: PositiveFiniteDuration = PositiveFiniteDuration.ofMinutes(2),

    /** The amount of time that we allow a future to be completed without a new one to be started
      * before we consider the trigger to be in an unhealthy state.
      */
    futureCompletionGracePeriod: PositiveFiniteDuration = PositiveFiniteDuration.ofSeconds(1L),
    ignoredExpiredRewardsPartyIds: Set[PartyId] = Set.empty,
    ignoredExpiredAmuletPartyIds: Set[PartyId] = Set.empty,
) {
  def withPausedTrigger[T <: Trigger](implicit tag: ClassTag[T]): AutomationConfig = copy(
    pausedTriggers = pausedTriggers + tag.runtimeClass.getCanonicalName
  )
  def withResumedTrigger[T <: Trigger](implicit tag: ClassTag[T]): AutomationConfig = copy(
    pausedTriggers = pausedTriggers - tag.runtimeClass.getCanonicalName
  )
  def topupTriggerPollingInterval_ : NonNegativeFiniteDuration =
    topupTriggerPollingInterval.getOrElse(pollingInterval)
}
