package com.daml.network.integration.tests

import com.daml.network.util.WalletTestUtil
import com.daml.network.util.TimeTestUtil
import com.daml.network.util.UpgradeUtil
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
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
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
      val cmds = UpgradeUtil.downgradeImportCrateCreate(
        new cc.coinimport.ImportCrate(
          svcParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          new cc.coinimport.importpayload.IP_Coin(aliceCoin.data),
        )
      )
      sv1Backend.participantClient.ledger_api_extensions.commands.submitJava(
        applicationId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        commands = cmds,
        optTimeout = None,
      )
    }

    advanceRoundsByOneTick

    clue("Start sv2 app and scan") {
      sv2Backend.startSync()
      sv2ScanBackend.startSync()
    }

    clue("Tap some more coin now that sv2 scan is up") {
      aliceWalletClient.tap(3)
    }

    actAndCheck(
      "Advancing time to close rounds",
      // TODO(#2930): Since we are reporting in getRoundOfLatestData() only the latest round for which the log contains both the open and close,
      // we must advance rounds until round 3 closes, which is the first one that sv2's scan is guaranteed to have seen opening the round opening.
      (0 to 6).foreach(_ => advanceRoundsByOneTick),
    )(
      "Waiting for scan apps to report rounds as closed",
      _ => {
        Try(sv1ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 4
        Try(sv2ScanBackend.getRoundOfLatestData()._1).success.value shouldBe 4
      },
    )

    clue("total coin balance on both scan apps should match") {
      val expectedTotalsRanges = Seq(
        (0.0, 0.0), // No coin in round 0
        (
          42.9,
          43.0,
        ), // Alice has 20 CC in Coin + 20 in ImportCrate, Bob has 3 CC, all minus holding fees
        (45.9, 46.0),
      ) // Alice has 23 CC in Coin + 20 in ImportCrate, Bob has 3 CC, all minus holding fees
      (0 to 2).foreach(i => {
        val total1 = sv1ScanBackend.getTotalCoinBalance(i.toLong)
        val total2 = sv2ScanBackend.getTotalCoinBalance(i.toLong)
        total1 shouldBe total2
        total1 should be >= BigDecimal(expectedTotalsRanges(i)._1)
        total1 should be <= BigDecimal(expectedTotalsRanges(i)._2)
      })
    }
  }

}
