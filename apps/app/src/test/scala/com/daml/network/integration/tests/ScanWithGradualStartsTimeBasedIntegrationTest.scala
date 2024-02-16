package com.daml.network.integration.tests

import com.daml.network.util.WalletTestUtil
import com.daml.network.util.TimeTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.codegen.java.cc
import scala.util.Try

class ScanWithGradualStartsTimeBasedIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .withManualStart

  "initialize a scan app that joins late" in { implicit env =>
    startAllSync(sv1ScanBackend, sv1Backend, aliceValidatorBackend, bobValidatorBackend)

    val (aliceUserParty, _) = onboardAliceAndBob()

    clue("Tap some coin before sv2 scan app starts") {
      aliceWalletClient.tap(20)
      bobWalletClient.tap(3)
    }

    clue("Create an importCrate for alice with the same value as her existing coin") {
      val aliceCoin = aliceValidatorBackend.participantClient.ledger_api_extensions.acs
        .awaitJava(cc.coin.Coin.COMPANION)(aliceUserParty)
      sv1Backend.participantClient.ledger_api_extensions.commands.submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        readAs = Seq.empty,
        update = new cc.coinimport.ImportCrate(
          svcParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          new cc.coinimport.importpayload.IP_Coin(aliceCoin.data),
        ).create,
      )
    }

    clue("Start sv2 app and scan") {
      sv2Backend.startSync()
      sv2ScanBackend.startSync()
      val maxOpenRoundFromACS = sv2ScanBackend
        .getOpenAndIssuingMiningRounds()
        ._1
        .map(_.contract.payload.round.number)
        .max
      // sv2 scan sees round 3 as first round opening after ACS, will get round 2 aggregates from sv1 scan
      maxOpenRoundFromACS shouldBe 2
    }

    clue("Tap some more coin now that sv2 scan is up") {
      aliceWalletClient.tap(3)
    }

    actAndCheck(
      "Advancing time to close rounds",
      // TODO(#2930): Since we are reporting in getRoundOfLatestData() only the latest round that is aggregated (fully closed),
      // we must advance rounds until round 3 closes, which is the first round that sv2's scan is guaranteed to have seen.
      (1 to 7).foreach(_ => advanceRoundsByOneTick),
    )(
      "Waiting for scan apps to report rounds as closed",
      _ => {
        Try(sv2ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 3
        Try(sv1ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 3
      },
    )

    clue("Aggregated total coin balance on both scan apps should match") {
      val expectedTotalsRanges = Seq(
        (
          45.9,
          46.0,
        ), // Alice has 23 CC in Coin + 20 in ImportCrate, Bob has 3 CC, all minus holding fees
        (66642.1, 66642.2),
      )
      (0 to 1).foreach { i =>
        val round = 2L + i.toLong
        val total1 = sv1ScanBackend.getTotalCoinBalance(round)
        val total2 = sv2ScanBackend.getTotalCoinBalance(round)
        total1 shouldBe total2
        total1 should be >= BigDecimal(expectedTotalsRanges(i)._1)
        total1 should be <= BigDecimal(expectedTotalsRanges(i)._2)
      }
    }

    clue("Aggregated rewards collected on both scan apps should match") {
      val expectedTotalsRanges = Seq(
        (
          0.0,
          0.0,
        ),
        (5.6, 5.8), // validator rewards
      )
      (0 to 1).foreach { i =>
        val round = 2L + i.toLong
        val rewards1 = sv1ScanBackend.getRewardsCollectedInRound(round)
        val rewards2 = sv2ScanBackend.getRewardsCollectedInRound(round)
        rewards1 shouldBe rewards2
        rewards1 should be >= BigDecimal(expectedTotalsRanges(i)._1)
        rewards1 should be <= BigDecimal(expectedTotalsRanges(i)._2)
      }
    }
  }
}
