package com.daml.network.integration.tests.auth

import com.daml.network.console.SvAppClientReference
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.tests.SpliceTests.{SpliceTestConsoleEnvironment, TestCommon}
import com.daml.network.integration.tests.runbook.PreflightIntegrationTestUtil
import com.daml.network.util.Auth0Util
import com.digitalasset.canton.integration.BaseIntegrationTest

trait PreflightAuthUtil extends PreflightIntegrationTestUtil {
  this: BaseIntegrationTest[
    EnvironmentImpl,
    SpliceTestConsoleEnvironment,
  ] & TestCommon =>
  val clientIds = Map(
    "sv1" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV1"),
    "sv2" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV2"),
    "sv3" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV3"),
    "sv4" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV4"),
    "sv1_validator" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV1_VALIDATOR"),
    "sv2_validator" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV2_VALIDATOR"),
    "sv3_validator" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV3_VALIDATOR"),
    "sv4_validator" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SV4_VALIDATOR"),
    "validator1" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_VALIDATOR1"),
    "splitwell_validator" -> sys.env("SPLICE_OAUTH_DEV_CLIENT_ID_SPLITWELL_VALIDATOR"),
  )

  protected def svClientWithToken(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference = {
    val clientId = clientIds.get(name).value
    val token = eventuallySucceeds() {
      Auth0Util.getAuth0ClientCredential(
        clientId,
        sys.env("OIDC_AUTHORITY_SV_AUDIENCE"),
        sys.env("SPLICE_OAUTH_DEV_AUTHORITY"),
      )(noTracingLogger)
    }

    val sv = env.svs.remote.find(sv => sv.name == name).value
    sv.copy(token = Some(token))
  }

}
