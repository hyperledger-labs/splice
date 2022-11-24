package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.*
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.plugins.toxiproxy.UseToxiproxy
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens._

class SvcConnectivityIntegrationTest extends CoinIntegrationTest {

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

    val summarizingRound = svc.startSummarizingRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(SummarizingMiningRound.COMPANION)(svcParty)
      .map(_.id) shouldBe Seq(summarizingRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(OpenMiningRound.COMPANION)(svcParty) shouldBe empty

    clue("disable connection from SVC app to the ledger API server for 5 seconds") {
      loggerFactory.suppressWarnings {
        toxiproxy.disable("svc-ledger-api")
        Threading.sleep(5000)
      }
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        svc.startIssuingRound(0),
        a => a.errorMessage should startWith("Request failed for svc-app. Is the server running?"),
      )
      toxiproxy.enable("svc-ledger-api")
    }

    clue("progress round 0 to issuing and check ACS matches expectations") {
      // TODO(#1093): wait for the SVC app health check to succeed and only then issue the command
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
        .filterJava(IssuingMiningRound.COMPANION)(svcParty)
        .map(_.id) shouldBe Seq(issuingRoundResponse.issuingRound)
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }

    clue(
      "attempt to close the issuing round, which requires the store to recover from the disconnect"
    ) {
      // We need to suppress the intermittent errors raised by the server due to its view being out of
      // date while the store ingestion has not yet recovered.
      loggerFactory.suppressWarningsAndErrors {
        eventually() {
          try {
            svc.closeRound(0)
          } catch {
            case _: CommandFailure => fail()
          }
        }
      }

      svc.remoteParticipant.ledger_api.acs
        .filterJava(IssuingMiningRound.COMPANION)(svcParty)
        .map(_.id) shouldBe empty
    }
  }
}
