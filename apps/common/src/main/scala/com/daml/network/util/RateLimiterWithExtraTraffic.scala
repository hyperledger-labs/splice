package com.daml.network.util

import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveDouble}
import com.digitalasset.canton.time.Clock

import java.util.concurrent.atomic.AtomicReference

/** Adapts com.digitalasset.canton.util.RateLimiter to provide a rate limiter that also has an additional
  * allowance for extra traffic over and above a default throughput rate. It also makes use of an
  * instance of com.digitalasset.canton.time.Clock to maintain time instead of a method returning a Long value.
  *
  * @param defaultMaxThroughput the maximum number of requests per second
  * @param maxBurstFactor the ratio of max requests per second at which the throttling should start to kick in
  * @param clock the clock instance used to keep time within the rate limiter.
  */
class RateLimiterWithExtraTraffic(
    defaultMaxThroughput: NonNegativeNumeric[Double],
    maxBurstFactor: PositiveDouble,
    clock: Clock,
) {

  private val currentState_ = new AtomicReference[State](
    State(approvedLastRequest = false, 0, 0.0, 0.0)
  )
  private val maxBurst: Double = maxBurstFactor.value * defaultMaxThroughput.value

  private case class State(
      approvedLastRequest: Boolean,
      lastUpdateMicros: Long,
      approvedRequests: Double,
      extraTrafficUsed: Double,
  ) {

    private def computeApprovedRequests(now: Long): Double = {
      // determine the time elapsed since we submitted last time
      val deltaMicros = now - lastUpdateMicros
      // determine the fractional number of requests that we were allowed to submit in that period
      val adjust = defaultMaxThroughput.value * deltaMicros.toDouble / 1e6
      // remove that number from the "approvedRequests"
      Math.max(0, approvedRequests - adjust)
    }

    def getDefaultTrafficBalance(now: Long): Double = {
      maxBurst - computeApprovedRequests(now)
    }

    def update(now: Long, extraTrafficLimit: Double): State = {
      val adjustedApprovedRequests = computeApprovedRequests(now)
      if (adjustedApprovedRequests + 1 <= maxBurst) {
        // if approving this request would not exceed the maxBurst threshold, the request is approved.
        State(approvedLastRequest = true, now, adjustedApprovedRequests + 1, extraTrafficUsed)
      } else {
        val defaultTrafficBalance = maxBurst - adjustedApprovedRequests
        // consume remaining default traffic balance
        // and adjust the extra traffic used with the remaining amount
        val adjustedExtraTrafficUsed = extraTrafficUsed - defaultTrafficBalance + 1
        if (adjustedExtraTrafficUsed <= extraTrafficLimit) {
          // if approving this request would not exceed the supplied extraTrafficLimit, the request is approved.
          State(approvedLastRequest = true, now, maxBurst, adjustedExtraTrafficUsed)
        } else {
          // else the request is rejected
          State(approvedLastRequest = false, now, adjustedApprovedRequests, extraTrafficUsed)
        }
      }
    }

  }

  /** Call this before making a new request.
    * @return whether the requests can be executed while still meeting the rate limit
    */
  final def checkAndUpdate(extraTrafficLimit: NonNegativeNumeric[Double]): Boolean = {
    val now = clock.nowInMicrosecondsSinceEpoch
    currentState_.updateAndGet(_.update(now, extraTrafficLimit.value)).approvedLastRequest
  }

  final def getDefaultTrafficBalance(): Double = {
    val now = clock.nowInMicrosecondsSinceEpoch
    currentState_.get().getDefaultTrafficBalance(now)
  }

  /** Obtain how much extra traffic can be consumed before requests are throttled to the default rate.
    * @return the difference between the given extra traffic limit and the amount of extra traffic already consumed.
    */
  final def getExtraTrafficBalance(extraTrafficLimit: NonNegativeNumeric[Double]): Double = {
    extraTrafficLimit.value - currentState_.get().extraTrafficUsed
  }

}
