package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coinrules.CoinRulesRequest
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

class ConnectivityIntegrationTest extends CoinIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // We manually start apps so we disable the default setup
      // that blocks on all apps being initialized.
      .withNoSetup()

  private val toxiproxy = new UseToxiproxy()
  registerPlugin(toxiproxy)

  "survive a 2 second disconnect" in { implicit env =>
    svc.startSync()
    scan.startSync()

    clue("svc app should report as active")(svc.health.active shouldBe true)

    clue("alice's validator starts successfully")(aliceValidator.startSync())

    clue("disable connection from SVC app to the ledger API server for 2 seconds") {
      toxiproxy.disable("svc-ledger-api")
      Threading.sleep(2)
    }

    clue("svc app should report as inactive")(svc.health.active shouldBe false)

    clue("start bob's validator and wait for its 'CoinRulesRequest' appearing on the SVC") {
      bobValidator.start()
      eventually() {
        // Using scan's remoteParticipant as that points to the non-toxied API
        val results = scan.remoteParticipant.ledger_api.acs
          .filterJava(CoinRulesRequest.COMPANION)(svcParty)
        inside(results)(_.size shouldBe 1)
      }
    }
    clue("bob's validator reports as not active") {
      bobValidator.health.active shouldBe false
    }

    clue("re-enable the connection and wait for svc app to report healthy again") {
      toxiproxy.enable("svc-ledger-api")
      eventually() {
        svc.health.active shouldBe true
      }
    }

    clue("wait for bob's validator app to become active") {
      eventually()(bobValidator.health.active shouldBe true)
    }

    clue("check that there are no outstanding CoinRulesRequest contracts") {
      val results = svc.remoteParticipant.ledger_api.acs
        .filterJava(CoinRulesRequest.COMPANION)(svcParty)
      inside(results)(_.size shouldBe 0)
    }
  }
}
