package com.daml.network
package environment

import scala.concurrent.duration.*

/** The intended use of a retry, expressed in terms like number of retries and
  * backoff.  Use a definition in the companion rather than constructing ''ad hoc''.
  */
final case class RetryFor private (
    maxRetries: Int,
    initialDelay: FiniteDuration,
    maxDelay: Duration,
    resetRetriesAfter: Option[FiniteDuration],
)

object RetryFor {

  /** A retry intended for automation calls that are expected to succeed
    * eventually. Retries a bounded number of times.
    */
  val Automation: RetryFor = RetryFor(
    // TODO(#7244) Reduce this from its WaitingOnInitDependency bumping
    maxRetries = 60,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
  )

  /** A retry intended for app initialization steps that depend on another app
    * that may itself be initializing, or yet to even begin initializing.
    * Retries a bounded number of times.
    */
  val WaitingOnInitDependency: RetryFor = RetryFor(
    maxRetries = 60,
    initialDelay = 200.millis,
    maxDelay = 5.seconds,
    resetRetriesAfter = None,
  )

  /** A retry intended for automation that is expected to run forever, e.g.,
    * ledger ingestion. Retries forever.
    */
  val LongRunningAutomation: RetryFor = RetryFor(
    maxRetries = Int.MaxValue,
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
  )
}
