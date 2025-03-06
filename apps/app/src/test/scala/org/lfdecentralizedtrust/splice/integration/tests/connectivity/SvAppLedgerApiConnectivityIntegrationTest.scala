package org.lfdecentralizedtrust.splice.integration.tests.connectivity

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy.UseToxiproxy
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

import scala.concurrent.duration.*

class SvAppLedgerApiConnectivityIntegrationTest extends IntegrationTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart

  private val toxiproxy = UseToxiproxy(createSvLedgerApiProxies = true)
  registerPlugin(toxiproxy)

  "sv1 app should recover and correctly report their activeness status after a disconnect" in {
    implicit env =>
      startAllSync(sv1Backend, sv1ScanBackend)

      clue("sv1 app should report as active")(eventually() {
        sv1Backend.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
      })

      clue("alice's validator starts successfully")(aliceValidatorBackend.startSync())

      clue("disable all SV connections to the ledger API server") {
        toxiproxy.disableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1Backend.name))
      }

      clue("sv1 app should report as inactive") {
        eventually() {
          sv1Backend.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe false
        }
      }

      clue(
        "start bob's validator"
      ) {
        bobValidatorBackend.start()
      }
      clue("bob's validator reports as not active") {
        eventually() {
          bobValidatorBackend.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe false
        }
      }

      clue("re-enable the connection and wait for sv1 app to report healthy again") {
        toxiproxy.enableConnectionViaProxy(UseToxiproxy.ledgerApiProxyName(sv1Backend.name))
        eventually() {
          sv1Backend.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
        }
      }

      clue("wait for bob's validator app to become active") {
        eventually(40.seconds) {
          bobValidatorBackend.httpHealth.successOption.map(_.active).getOrElse(false) shouldBe true
        }
      }
  }
}
