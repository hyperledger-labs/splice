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

class XNodeSvAppLedgerApiConnectivityIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyX(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withManualStart

  private val toxiproxy = UseToxiproxy(createSvLedgerApiProxies = true)
  registerPlugin(toxiproxy)

  "sv1 app should recover and correctly report their activeness status after a disconnect" in {
    implicit env =>
      startAllSync(sv1, sv1Scan, svc)

      clue("svc app should report as active")(svc.health.active shouldBe true)

      clue("sv1 app should report as active")(eventually() {
        sv1.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
      })

      clue("alice's validator starts successfully")(aliceValidator.startSync())

      clue("disable all SV connections to the ledger API server") {
        toxiproxy.disableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1.name))
      }

      clue("sv1 app should report as inactive") {
        eventually() {
          sv1.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe false
        }
      }

      clue(
        "start bob's validator"
      ) {
        bobValidator.start()
      }
      clue("bob's validator reports as not active") {
        eventually() {
          bobValidator.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe false
        }
      }

      clue("re-enable the connection and wait for sv1 app to report healthy again") {
        toxiproxy.enableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1.name))
        eventually() {
          sv1.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
        }
      }

      clue("wait for bob's validator app to become active") {
        eventually() {
          bobValidator.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
        }
      }
  }
}
