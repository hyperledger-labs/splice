package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.scan.store.ScanTxLogParser

class ScanIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) },
      )
      // The wallet automation periodically merges coins, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      // TODO(#7639) Test with the pause / resume trigger functionality to verify that merges and rewards show up correctly
      .withoutAutomaticRewardsCollectionAndCoinMerging

  "list recent activity" in { implicit env =>
    val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
    val bobTapAmount = 1000.0
    val aliceTapAmount = 1000.0

    clue("Tap to get some coins") {
      aliceWalletClient.tap(aliceTapAmount)
      bobWalletClient.tap(bobTapAmount)
    }
    clue("Alice's balance reflects taps")({
      eventually() {
        checkBalance(
          aliceWalletClient,
          None,
          expectedUnlockedQtyRange = (aliceTapAmount - 1, aliceTapAmount),
          exactly(0),
          exactly(0),
        )
      }
    })

    val transferAmount = 100.0
    clue("Transfer some CC to alice")({
      p2pTransfer(
        bobValidatorBackend,
        bobWalletClient,
        aliceWalletClient,
        aliceUserParty,
        transferAmount,
      )
    })

    clue("Alice receives the transfer")({
      eventually() {
        checkBalance(
          aliceWalletClient,
          None,
          expectedUnlockedQtyRange =
            (aliceTapAmount + transferAmount - 1, aliceTapAmount + transferAmount),
          exactly(0),
          exactly(0),
        )
      }
      eventually() {
        checkBalance(
          bobWalletClient,
          None,
          expectedUnlockedQtyRange =
            (bobTapAmount - transferAmount - 2, bobTapAmount - transferAmount),
          exactly(0),
          exactly(0),
        )
      }
    })

    eventually() {
      // only look at activities that bob sent to prevent flakes, some activities occur on startup before this test.
      val transferActivities =
        sv1ScanBackend.listRecentActivity(None, 10).filter { recentActivity =>
          PartyId.tryFromProtoPrimitive(
            recentActivity.sender.party
          ) == bobUserParty && recentActivity.activityType == ScanTxLogParser.TxLogEntry.RecentActivityType.Transfer.name
        }

      transferActivities should have size (1)
      val transferActivity = transferActivities.head
      // bob transferred 100 + fees
      BigDecimal(transferActivity.sender.amount) should beWithin(
        BigDecimal(-1 * (transferAmount + 1.1)),
        BigDecimal(-1 * transferAmount),
      )
      // alice receives transfer
      transferActivity.receivers
        .map(r => BigDecimal(r.amount))
        .sum shouldBe BigDecimal(transferAmount)

      val tapActivities = sv1ScanBackend.listRecentActivity(None, 10).filter { recentActivity =>
        recentActivity.activityType == ScanTxLogParser.TxLogEntry.RecentActivityType.Tap.name && recentActivity.receivers
          .map(r => PartyId.tryFromProtoPrimitive(r.party))
          .contains(bobUserParty)
      }

      val tapActivity = tapActivities.head
      // bob tapped
      tapActivity.receivers should have size (1)
      BigDecimal(tapActivity.receivers(0).amount) shouldBe (BigDecimal(bobTapAmount))
      // no sender for tap
      BigDecimal(tapActivity.sender.amount) shouldBe (BigDecimal(0))
    }
  }
}
