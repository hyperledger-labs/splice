// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice
package environment

import scala.concurrent.duration.*

/** The intended use of a retry, expressed in terms like number of retries and
  * backoff.  Use a definition in the companion rather than constructing ''ad hoc''.
  *
  * `duplicateCommandIsFatal` gives up on DUPLICATE_COMMAND instead of retrying. It is set
  * for client calls, where a duplicate cannot resolve within the deduplication window, and
  * left off for automation, which retries and then finds its task stale.
  */
final case class RetryFor(
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Duration,
    resetRetriesAfter: Option[FiniteDuration],
    duplicateCommandIsFatal: Boolean = false,
)

object RetryFor {

  /** A retry intended for automation calls that are expected to succeed
    * eventually. Retries a bounded number of times.
    */
  val Automation: RetryFor = RetryFor(
    maxRetries = 35,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
  )

  /** A retry intended for app initialization steps that depend on another app
    * that may itself be initializing, or yet to even begin initializing.
    * Retries a bounded number of times.
    */
  val WaitingOnInitDependency: RetryFor = RetryFor(
    maxRetries = 120,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
  )

  /** A retry intended for app initialization steps that might take a long time to execute.
    * Eg: after a migration waiting for store ingestion
    */
  val WaitingOnInitDependencyLong: RetryFor = RetryFor(
    maxRetries = 500,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
  )

  /** A retry intended for automation that is expected to run forever, e.g.,
    * ledger ingestion. Retries are bounded but reset after a period of
    * no errors. This should usually be wrapped in an outer retry loop that
    * retries forever but with a very slow retry interval.
    */
  val LongRunningAutomation: RetryFor = RetryFor(
    maxRetries = 35,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = Some(1.minute),
  )

  /** A retry intended for client calls, thus timing out relatively quickly. */
  val ClientCalls: RetryFor = RetryFor(
    maxRetries = 12,
    initialDelay = 100.millis,
    maxDelay = 1.seconds,
    resetRetriesAfter = None,
    duplicateCommandIsFatal = true,
  )

  /** A retry intended for client calls during the init phase, timing out slower compared to the regular client calls to allow for more contention that happens during initialization. */
  val InitializingClientCalls: RetryFor = RetryFor(
    maxRetries = 20,
    initialDelay = 100.millis,
    maxDelay = 3.seconds,
    resetRetriesAfter = None,
    duplicateCommandIsFatal = true,
  )
}
