package com.daml.network.integration.tests.connectivity

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class HealthEndpointsConnectivityIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withManualStart

  private val toxiproxy = UseToxiproxy(createSvLedgerApiProxies = true)
  registerPlugin(toxiproxy)

  "sv1 app should report liveness and readiness" in { implicit env =>
    startAllSync(sv1, sv1Scan, svc)

    clue("svc app should report as active")(svc.health.active shouldBe true)

    sv1.httpLive shouldBe true
    sv1.httpReady shouldBe true

    actAndCheck(
      "disable all SV connections to the ledger API server",
      toxiproxy.disableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1.name)),
    )(
      "sv1 app should report as inactive",
      _ => {
        sv1.httpHealth.successOption.exists(_.active) shouldBe false
      },
    )

    sv1.httpLive shouldBe true

    loggerFactory.assertLogs(
      sv1.httpReady shouldBe false,
      _.errorMessage should include("503"),
    )

    actAndCheck(
      "re-enable the connection and wait for sv1 app to report healthy again",
      toxiproxy.enableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1.name)),
    )(
      "sv1 app should report as active",
      _ => {
        sv1.httpHealth.successOption.exists(_.active) shouldBe true
      },
    )

    sv1.httpLive shouldBe true
    sv1.httpReady shouldBe true
  }
}
