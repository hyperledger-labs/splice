package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class ScanTimeBasedIntegrationTestWithAutomation
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )

  "total collected rewards are computed correctly" in { implicit env =>
    val (_, bobUserParty) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)
    waitForWalletUser(bobValidatorWalletClient)

    clue("Tap to get some coins") {
      aliceWalletClient.tap(100.0)
      aliceValidatorWalletClient.tap(100.0)
    }

    // First transfer
    actAndCheck(
      "Alice transfers some CC to Bob",
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0),
    )(
      "Bob has received the CC",
      _ => bobWalletClient.balance().unlockedQty should be > BigDecimal(39.0),
    )

    clue("Advance rounds by one tick to space out the second transfer") {
      advanceRoundsByOneTick
    }

    // Second transfer
    actAndCheck(
      "Alice's validator transfers some CC to Bob (using her app & validator rewards)",
      p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0),
    )(
      "Bob has received the CC",
      _ => {
        bobWalletClient.balance().unlockedQty should be > BigDecimal(49.0)
      },
    )

    // It takes 3 ticks for the IssuingMiningRound 1 to be created and open.
    clue("Advance rounds by 2 ticks") {
      advanceRoundsByOneTick
      advanceRoundsByOneTick
    }

    // This is the round where the rewards for the first transfer are collected.
    val rewardCollectionRoundForFirstTransfer =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number

    val rewardsCollectedForFirstTransfer =
      clue("correct rewards are returned for the round and for the total") {
        eventually() {
          val rewardsCollectedForFirstTransfer = sv1ScanBackend.getRewardsCollectedInRound(
            rewardCollectionRoundForFirstTransfer
          )
          rewardsCollectedForFirstTransfer should beWithin(BigDecimal(0.3), BigDecimal(0.4))

          sv1ScanBackend.getTotalRewardsCollectedEver() should equal(
            rewardsCollectedForFirstTransfer
          )
          rewardsCollectedForFirstTransfer
        }
      }

    clue("Advance rounds by one more tick") {
      advanceRoundsByOneTick
    }

    // This is the round where the rewards for the second transfer are collected.
    val rewardCollectionRoundForSecondTransfer =
      sv1ScanBackend.getLatestOpenMiningRound(getLedgerTime).contract.payload.round.number

    clue("correct rewards are returned for the old round, the new round and for the total") {
      eventually() {
        val rewardsCollectedForFirstTransferComputedAgain =
          sv1ScanBackend.getRewardsCollectedInRound(
            rewardCollectionRoundForFirstTransfer
          )

        rewardsCollectedForFirstTransferComputedAgain should equal(rewardsCollectedForFirstTransfer)

        val rewardsCollectedForSecondTransfer = sv1ScanBackend.getRewardsCollectedInRound(
          rewardCollectionRoundForSecondTransfer
        )
        rewardsCollectedForSecondTransfer should beWithin(BigDecimal(0.1), BigDecimal(0.15))

        sv1ScanBackend.getTotalRewardsCollectedEver() should equal(
          rewardsCollectedForSecondTransfer + rewardsCollectedForFirstTransfer
        )
      }
    }
  }
}
