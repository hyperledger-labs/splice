package com.daml.network.integration.tests.runbook

import com.daml.network.util.{Auth0Util, K8sUtil}
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.typesafe.scalalogging.Logger

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
  )(testCode: => T): T = super.eventuallySucceeds(timeUntilSuccess, maxPollInterval)(testCode)

  override def actAndCheck[T, U](
      timeUntilSuccess: FiniteDuration = this.preflightTimeUntilSuccess,
      maxPollInterval: FiniteDuration = 5.seconds,
  )(
      action: String,
      actionExpr: => T,
  )(check: String, checkFun: T => U): (T, U) =
    super.actAndCheck(timeUntilSuccess, maxPollInterval)(action, actionExpr)(check, checkFun)

  protected def getAuth0ClientCredential(
      clientId: String,
      audience: String,
      auth0: Auth0Util,
  )(implicit logger: Logger): String = {
    // lookup token from a cached k8s secret, or request a new one from auth0 if not found or expired
    val cachedToken =
      K8sUtil.PreflightTokenAccessor.getPreflightToken(clientId)

    cachedToken.getOrElse {
      val token = auth0.getToken(clientId, audience)
      K8sUtil.PreflightTokenAccessor.savePreflightToken(clientId, token)
      token
    }.accessToken
  }
}
