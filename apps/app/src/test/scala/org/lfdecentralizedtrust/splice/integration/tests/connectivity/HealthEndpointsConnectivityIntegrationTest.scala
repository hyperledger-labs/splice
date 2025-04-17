package org.lfdecentralizedtrust.splice.integration.tests.connectivity

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy.UseToxiproxy
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

class HealthEndpointsConnectivityIntegrationTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart

  private val toxiproxy = UseToxiproxy(createSvLedgerApiProxies = true)
  registerPlugin(toxiproxy)

  "sv1 app should report liveness and readiness" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend)

    sv1Backend.httpLive shouldBe true
    sv1Backend.httpReady shouldBe true
    sv1ScanBackend.httpLive shouldBe true
    sv1ScanBackend.httpReady shouldBe true

    actAndCheck(
      "disable all SV connections to the ledger API server",
      toxiproxy.disableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1Backend.name)),
    )(
      "sv1 app should report as inactive",
      _ => {
        sv1Backend.httpHealth.successOption.exists(_.active) shouldBe false
      },
    )

    sv1Backend.httpLive shouldBe true

    loggerFactory.assertLogs(
      sv1Backend.httpReady shouldBe false,
      _.errorMessage should include("503"),
    )

    actAndCheck(
      "re-enable the connection and wait for sv1 app to report healthy again",
      toxiproxy.enableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1Backend.name)),
    )(
      "sv1 app should report as active",
      _ => {
        sv1Backend.httpHealth.successOption.exists(_.active) shouldBe true
        sv1Backend.httpLive shouldBe true
        loggerFactory.assertLoggedWarningsAndErrorsSeq(
          sv1Backend.httpReady shouldBe true,
          // There will be an error log if the readiness check returns false
          lines => forAll(lines)(_.errorMessage should include("503")),
        )
      },
    )
  }
}
