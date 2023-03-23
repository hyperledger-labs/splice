package com.daml.network.integration.tests.connectivity

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SvAppLedgerApiConnectivityIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  private val toxiproxy = UseToxiproxy(createLedgerApiProxies = true)
  registerPlugin(toxiproxy)

  "sv app should recover and correctly report their activeness status after a disconnect" in {
    implicit env =>
      svc.startSync()
      svs.foreach(_.startSync())
      scan.startSync()

      clue("svc app should report as active")(svc.health.active shouldBe true)

      clue("sv apps should report as active")(svs.foreach(_.health.active shouldBe true))

      clue("alice's validator starts successfully")(aliceValidator.startSync())

      clue("disable all SV connections to the ledger API server") {
        svs.foreach(sv =>
          toxiproxy.disableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv.name))
        )
      }

      clue("sv apps should report as inactive") {
        eventually() {
          svs.foreach(_.health.active shouldBe false)
        }
      }

      clue(
        "start bob's validator"
      ) {
        bobValidator.start()
      }
      clue("bob's validator reports as not active") {
        bobValidator.health.active shouldBe false
      }

      clue("re-enable the connection and wait for sv apps to report healthy again") {
        svs.foreach(sv =>
          toxiproxy.enableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv.name))
        )
        eventually() {
          svs.foreach(_.health.active shouldBe true)
        }
      }

      clue("wait for bob's validator app to become active") {
        eventually()(bobValidator.health.active shouldBe true)
      }
  }
}
