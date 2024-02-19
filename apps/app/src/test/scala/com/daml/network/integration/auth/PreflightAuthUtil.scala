package com.daml.network.integration.auth

import com.daml.network.console.SvAppClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.tests.runbook.PreflightIntegrationTestUtil
import com.digitalasset.canton.integration.BaseIntegrationTest

trait PreflightAuthUtil extends PreflightIntegrationTestUtil {
  this: BaseIntegrationTest[
    CNNodeEnvironmentImpl,
    CNNodeTestConsoleEnvironment,
  ] & CNNodeTestCommon =>
  private val sv1ClientId = "OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn"
  private val sv2ClientId = "rv4bllgKWAiW9tBtdvURMdHW42MAXghz"
  private val sv3ClientId = "SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk"
  private val sv4ClientId = "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN"
  private val clientIds = Map(
    "sv1" -> sv1ClientId,
    "sv2" -> sv2ClientId,
    "sv3" -> sv3ClientId,
    "sv4" -> sv4ClientId,
  )

  private lazy val auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com", "dev")
  protected def svclWithToken(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): SvAppClientReference = {
    val clientId = clientIds.get(name).value
    val token = eventuallySucceeds() {
      getAuth0ClientCredential(
        clientId,
        "https://canton.network.global",
        auth0,
      )(noTracingLogger)
    }

    val sv = env.svs.remote.find(sv => sv.name == name).value
    sv.copy(token = Some(token))
  }

}
