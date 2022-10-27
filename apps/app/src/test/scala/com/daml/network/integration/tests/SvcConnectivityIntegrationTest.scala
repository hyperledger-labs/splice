package com.daml.network.integration.tests

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

class SvcConnectivityIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  private val toxiproxy = new UseToxiproxy()
  registerPlugin(toxiproxy)

  "survive a 5 second disconnect" in { implicit env =>
    // Sync with background automation that onboards validator.
    eventually()({
      val requests = svc.remoteParticipant.ledger_api.acs
        .filter(svcParty, OpenMiningRound)
      inside(requests) { case Seq(request) =>
        request.value.observers should have length 4
      }
    })

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
}
