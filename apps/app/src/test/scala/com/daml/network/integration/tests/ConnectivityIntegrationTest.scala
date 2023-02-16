package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin.CoinRulesRequest
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
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

  "survive a disconnect" in { implicit env =>
    svc.startSync()
    svs.foreach(_.startSync())
    scan.startSync()

    clue("svc app should report as active")(svc.health.active shouldBe true)

    clue("sv apps should report as active")(svs.foreach(_.health.active shouldBe true))

    clue("alice's validator starts successfully")(aliceValidator.startSync())

    clue("disable all SV connections to the ledger API server") {
      svs.foreach(sv => toxiproxy.disable(s"${sv.name}-ledger-api"))
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
      svs.foreach(sv => toxiproxy.enable(s"${sv.name}-ledger-api"))
      eventually() {
        svs.foreach(_.health.active shouldBe true)
      }
    }

    clue("wait for bob's validator app to become active") {
      eventually()(bobValidator.health.active shouldBe true)
    }

    clue("check that there are no outstanding CoinRulesRequest contracts") {
      val results = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(CoinRulesRequest.COMPANION)(svcParty)
      inside(results)(_.size shouldBe 0)
    }
  }
}
