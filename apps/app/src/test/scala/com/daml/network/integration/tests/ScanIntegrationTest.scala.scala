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
    // TODO(#7633) Tap is currently not added to recent activity
    clue("Tap to get some coins") {
      aliceWalletClient.tap(500.0)
      aliceWalletClient.tap(500.0)
      bobWalletClient.tap(500.0)
      bobWalletClient.tap(500.0)
    }
    clue("Alice's balance reflects taps")({
      eventually() {
        checkBalance(
          aliceWalletClient,
          None,
          expectedUnlockedQtyRange = (1000.0 - 1, 1000.0),
          exactly(0),
          exactly(0),
        )
      }
    })

    clue("Transfer some CC to alice")({
      p2pTransfer(bobValidatorBackend, bobWalletClient, aliceWalletClient, aliceUserParty, 100.0)
    })

    clue("Alice receives the transfer")({
      eventually() {
        checkBalance(
          aliceWalletClient,
          None,
          expectedUnlockedQtyRange = (1100.0 - 1, 1100.0),
          exactly(0),
          exactly(0),
        )
      }
      eventually() {
        checkBalance(
          bobWalletClient,
          None,
          expectedUnlockedQtyRange = (900 - 2, 900),
          exactly(0),
          exactly(0),
        )
      }
    })

    eventually() {
      // only look at activities that bob sent to prevent flakes, some activities occur on startup before this test.
      val activities = sv1ScanBackend.listRecentActivity(None, 10).filter { recentActivity =>
        PartyId.tryFromProtoPrimitive(recentActivity.sender.party) == bobUserParty
      }

      activities should have size (1)
      val activityFromTest = activities.head
      // bob transferred 100 + fees
      BigDecimal(activityFromTest.sender.amount) should beWithin(
        BigDecimal(-100.0 - 1.1),
        BigDecimal(-100.0),
      )
      // alice receives transfer
      activityFromTest.receivers
        .map(r => BigDecimal(r.amount))
        .sum shouldBe BigDecimal(100.0)
    }
  }
}
