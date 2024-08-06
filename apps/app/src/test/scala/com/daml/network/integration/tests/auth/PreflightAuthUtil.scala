package com.daml.network.integration.tests.auth

import com.daml.network.console.SvAppClientReference
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.tests.SpliceTests.{TestCommon, SpliceTestConsoleEnvironment}
import com.daml.network.integration.tests.runbook.PreflightIntegrationTestUtil
import com.digitalasset.canton.integration.BaseIntegrationTest

trait PreflightAuthUtil extends PreflightIntegrationTestUtil {
  this: BaseIntegrationTest[
    EnvironmentImpl,
    SpliceTestConsoleEnvironment,
  ] & TestCommon =>
  private val sv1ClientId = "OBpJ9oTyOLuAKF0H2hhzdSFUICt0diIn"
  private val sv2ClientId = "rv4bllgKWAiW9tBtdvURMdHW42MAXghz"
  private val sv3ClientId = "SeG68w0ubtLQ1dEMDOs4YKPRTyMMdDLk"
  private val sv4ClientId = "CqKgSbH54dqBT7V1JbnCxb6TfMN8I1cN"
  val clientIds = Map(
    "sv1" -> sv1ClientId,
    "sv2" -> sv2ClientId,
    "sv3" -> sv3ClientId,
    "sv4" -> sv4ClientId,
    "sv1_validator" -> "7YEiu1ty0N6uWAjL8tCAWTNi7phr7tov",
    "sv2_validator" -> "5N2kwYLOqrHtnnikBqw8A7foa01kui7h",
    "sv3_validator" -> "V0RjcwPCsIXqYTslkF5mjcJn70AiD0dh",
    "sv4_validator" -> "FqRozyrmu2d6dFQYC4J9uK8Y6SXCVrhL",
    "validator1" -> "cf0cZaTagQUN59C1HBL2udiIBdFh2CWq",
    "splitwell_validator" -> "hqpZ6TP0wGyG2yYwhH6NLpuo0MpJMQZW",
  )

  lazy val auth0 = auth0UtilFromEnvVars("https://canton-network-dev.us.auth0.com", "dev")
  protected def svClientWithToken(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference = {
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
