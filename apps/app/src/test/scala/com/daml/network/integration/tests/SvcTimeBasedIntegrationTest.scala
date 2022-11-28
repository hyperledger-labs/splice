package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.util.CoinTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration

class SvcTimeBasedIntegrationTest extends CoinIntegrationTest with CoinTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  // TODO(#1680): Adjust for removal of manual round management commands and 3 interlocked rounds
  "round management" in { implicit env =>
    val coinPrice: BigDecimal = 23.0

    // Sync with background automation that onboards validator.
    eventually()({
      val requests = svc.remoteParticipant.ledger_api.acs
        .filterJava(cc.round.OpenMiningRound.COMPANION)(svcParty)
      inside(requests) { case Seq(request) =>
        request.data.observers should have length 4
      }
    })
    // advance so that first round is closeable.
    advanceTime(Duration.ofSeconds(160))

    val summarizingRound = svc.startSummarizingRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(SummarizingMiningRound.COMPANION)(svcParty)
      .map(_.id) shouldBe Seq(summarizingRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(OpenMiningRound.COMPANION)(svcParty) shouldBe empty

    // background automation should eventually archive the summarizing round
    eventually() {
      svc.remoteParticipant.ledger_api.acs
        .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
    }

    // advance so issuing round is closeable.
    advanceTime(Duration.ofSeconds(160))
    val closedRound = svc.closeRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(ClosedMiningRound.COMPANION)(svcParty)
      .map(_.id) shouldBe Seq(closedRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(IssuingMiningRound.COMPANION)(svcParty) shouldBe empty

    eventually() {
      svc.remoteParticipant.ledger_api.acs
        .filterJava(ClosedMiningRound.COMPANION)(svcParty) shouldBe empty
    }

    remoteSvc.openRound(1, coinPrice)
    inside(svc.remoteParticipant.ledger_api.acs.filterJava(OpenMiningRound.COMPANION)(svcParty)) {
      case Seq(round) => round.data.round.number == 1
    }
  }

}
