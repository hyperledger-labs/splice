package com.daml.network.integration.tests

import com.daml.network.codegen.CC
import com.daml.network.codegen.CC.Round.*
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens._

class SvcConnectivityIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
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

  "survive a 5 second disconnect" in { implicit env =>
    svc.startSync()
    scan.startSync()

    val closingRound = svc.startClosingRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, ClosingMiningRound)
      .map(_.contractId) shouldBe Seq(closingRound)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, OpenMiningRound) shouldBe empty

    loggerFactory.suppressWarnings {
      toxiproxy.disable("svc-ledger-api")
      Threading.sleep(5000)
    }
    loggerFactory.assertThrowsAndLogs[CommandFailure](
      svc.startIssuingRound(0),
      a => a.errorMessage should startWith("Request failed for svc-app. Is the server running?"),
    )
    toxiproxy.enable("svc-ledger-api")

    val issuingRoundResponse = loggerFactory.suppressWarningsAndErrors {
      eventually() {
        try {
          svc.startIssuingRound(0)
        } catch {
          case _: CommandFailure => fail()
        }
      }
    }
    svc.remoteParticipant.ledger_api.acs
      .filter(svcParty, IssuingMiningRound)
      .map(
        _.contractId
      ) shouldBe Seq(issuingRoundResponse.issuingRound)
    svc.remoteParticipant.ledger_api.acs.filter(svcParty, ClosingMiningRound) shouldBe empty
  }

  "survive a network disconnect in acs ingestion" in { implicit env =>
    svc.startSync()
    scan.startSync()

    loggerFactory.suppressWarnings {
      toxiproxy.disable("svc-ledger-api")
      Threading.sleep(1000)
      // Sleeping for a bit to let the threads that complain about the connection loss do so while we're still suppressing warnings
    }

    aliceValidator.startSync()

    toxiproxy.enable("svc-ledger-api")

    // check that alice's validator can see the coinrules
    val aliceValidatorParty = aliceValidator.getValidatorPartyId()
    loggerFactory.assertThrowsAndLogs[IllegalStateException](
      // Until we fix the issue - this await command eventually times out with an IllegalStateException
      aliceValidator.remoteParticipant.ledger_api.acs
        .await(aliceValidatorParty, CC.CoinRules.CoinRules)
    )
  }
}
