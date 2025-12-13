package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.HasExecutionContext
import org.slf4j.event.Level

import java.time.Duration

class TimeBasedTreasuryIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "automatically merge transfer inputs when the automation is triggered" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)

    // create two amulets in alice's wallet
    aliceWalletClient.tap(50)
    checkWallet(alice, aliceWalletClient, Seq(exactly(50)))

    // run a transfer such that alice's validator has some rewards
    p2pTransfer(aliceWalletClient, bobWalletClient, bob, 40.0)
    eventually()(aliceValidatorWalletClient.listAppRewardCoupons() should have size 1)
    eventually()(aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1)
    // and give alice another amulet.
    aliceWalletClient.tap(50)
    checkWallet(alice, aliceWalletClient, Seq((9, 10), exactly(50)))

    // advance by two ticks, so the issuing round of round 1 is created
    advanceRoundsToNextRoundOpening
    advanceRoundsToNextRoundOpening

    // advance time such that issuing round 1 is open to rewards collection.
    advanceRoundsToNextRoundOpening

    eventually()({
      // app rewards are automatically collected
      aliceValidatorWalletClient
        .listAppRewardCoupons()
        .filter(_.payload.round.number == 1) should have size 0
      // and amulets are automatically merged.
      checkWallet(alice, aliceWalletClient, Seq((59, 61)))
      // same for validator rewards
      aliceValidatorWalletClient
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == 1) should have size 0
    })
  }

  "allow calling tap, list the created amulets, and get the balance - locally and remotely" in {
    implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(110)

      checkBalance(aliceWalletClient, Some(1), exactly(110), exactly(0), exactly(0))
      // leads to archival of open round 0
      advanceRoundsToNextRoundOpening

      lockAmulets(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().amulets,
        10,
        sv1ScanBackend,
        Duration.ofDays(10),
        getLedgerTime,
      )
      checkBalance(
        aliceWalletClient,
        Some(2),
        (99, 100),
        exactly(10),
        // due to merge in this round, no holding fees.
        exactly(0),
      )

      // leads to latest round being round 3
      advanceRoundsToNextRoundOpening

      checkBalance(
        aliceWalletClient,
        Some(3),
        (99, 100),
        (9, 10),
        exactly(SpliceUtil.defaultHoldingFee.rate),
      )
  }

  "don't collect rewards if their collection is more expensive than they reward in amulets" in {
    implicit env =>
      val (_, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)

      // giving alice 2 amulets...
      aliceWalletClient.tap(1)
      aliceWalletClient.tap(1)
      eventually() {
        aliceWalletClient.list().amulets should have length 2
      }
      // ..so when she pays bob, she doesn't have to pay a transfer fee which
      // will result in alice validator's reward being small enough that its not worth it to collect the reward
      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 0.00001)
      eventually() {
        aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
        aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 1
      }

      // advancing the rounds so the rewards would be collectable.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          // reward is now collectable..
          advanceRoundsToNextRoundOpening
        },
        entries => {
          forAtLeast(1, entries)( // however, we see that we choose not to the validator reward..
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )

      loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          aliceValidatorWalletClient.tap(1)
          eventually() {
            aliceValidatorWalletClient.list().amulets should have length 1
          }
        },
        entries => {
          forAtLeast(
            1,
            entries,
          )( // .. even when alice's validator has another amulet and would only need to pay
            // an create-fee for collecting the reward.
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )
  }

  "don't run merge if rewards and amulets are too small" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    aliceWalletClient.tap(0.001)
    aliceWalletClient.tap(0.001)

    eventually() {
      aliceWalletClient.list().amulets should have length 2
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        // trigger automation.
        advanceRoundsToNextRoundOpening
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // but do nothing since our amulets are too small to be worth merging.
          _.message should include regex (
            "the total rewards and amulet quantity .* is smaller than the create-fee"
          )
        )
      },
    )
  }

}
