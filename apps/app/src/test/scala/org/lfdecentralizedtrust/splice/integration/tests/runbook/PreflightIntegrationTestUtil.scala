package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import scala.concurrent.duration.*

trait PreflightIntegrationTestUtil extends TestCommon {

  // Give more time to the checks in cluster preflights on devnet only, to account for slower domains
  private def preflightTimeUntilSuccess: FiniteDuration = {
    sys.env.get("PREFLIGHT_DEFAULT_TIMEOUT_SECONDS").getOrElse("20").toInt.seconds
  }

  override def eventually[T](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      retryOnTestFailuresOnly: Boolean = true,
  )(testCode: => T): T =
    super.eventually(timeUntilSuccess, maxPollInterval, retryOnTestFailuresOnly)(testCode)

  override def eventuallySucceeds[T](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
      suppressErrors: Boolean = true,
  )(testCode: => T): T =
    super.eventuallySucceeds(timeUntilSuccess, maxPollInterval, suppressErrors)(testCode)

  override def actAndCheck[T, U](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
  )(
      action: String,
      actionExpr: => T,
  )(check: String, checkFun: T => U): (T, U) =
    super.actAndCheck(timeUntilSuccess, maxPollInterval)(action, actionExpr)(check, checkFun)

}
