package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin._
import com.daml.network.codegen.java.cc.round._
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest

import scala.jdk.CollectionConverters.*

class SvcIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    // TODO(M1-92): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
  }

  "round management" in { implicit env =>
    val coinPrice: BigDecimal = 23.0

    // Sync with background automation that onboards validator.
    eventually()({
      val requests = svc.remoteParticipant.ledger_api.acs
        .filterJava(OpenMiningRound.COMPANION)(svcParty)
      inside(requests) { case Seq(request) =>
        request.data.observers should have length 4
      }
    })

    val closingRound = svc.startClosingRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(ClosingMiningRound.COMPANION)(svcParty)
      .map(_.id) shouldBe Seq(closingRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(OpenMiningRound.COMPANION)(svcParty) shouldBe empty

    val issuingRoundResponse = svc.startIssuingRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(IssuingMiningRound.COMPANION)(svcParty)
      .map(
        _.id
      ) shouldBe Seq(issuingRoundResponse.issuingRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(ClosingMiningRound.COMPANION)(svcParty) shouldBe empty

    val closedRound = svc.closeRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(ClosedMiningRound.COMPANION)(svcParty)
      .map(_.id) shouldBe Seq(closedRound)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(IssuingMiningRound.COMPANION)(svcParty) shouldBe empty

    svc.archiveRound(0)
    svc.remoteParticipant.ledger_api.acs
      .filterJava(ClosedMiningRound.COMPANION)(svcParty) shouldBe empty

    remoteSvc.openRound(1, coinPrice)
    inside(svc.remoteParticipant.ledger_api.acs.filterJava(OpenMiningRound.COMPANION)(svcParty)) {
      case Seq(round) => round.data.round == new Round(1)
    }
  }

  "total burn calculation" in { implicit env =>
    // 3 app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(1.0).bigDecimal,
        new Round(0),
      ),
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(2.0).bigDecimal,
        new Round(0),
      ),
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(4.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
    )
    // Create a bunch of rewards directly
    svc.remoteParticipant.ledger_api.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )
    svc.startClosingRound(0)
    val result = svc.startIssuingRound(0)
    result.totalBurnQuantity shouldBe BigDecimal(1 + 2 + 3 + 4)
  }
}
