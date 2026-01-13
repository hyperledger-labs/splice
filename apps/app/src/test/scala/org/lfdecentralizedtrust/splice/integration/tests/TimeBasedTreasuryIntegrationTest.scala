package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
  updateAllSvAppFoundDsoConfigs_,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletTrigger
import com.digitalasset.canton.HasExecutionContext
import monocle.macros.syntax.lens.*

import java.time.Duration

class TimeBasedTreasuryIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil {

  // We increase holding fees for this test so we can expire amulets within a few rounds
  // without running into problematic because of, e.g., create fees.
  val holdingFee = BigDecimal(0.5)

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAllSvAppFoundDsoConfigs_(c => c.focus(_.initialHoldingFee).replace(holdingFee))(
          config
        )
      )
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "automatically merge transfer inputs when the automation is triggered" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)
    val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

    // create two amulets in alice's wallet
    aliceWalletClient.tap(10)
    checkWallet(alice, aliceWalletClient, Seq(exactly(10)), holdingFee)

    // create some rewards for alice's validator
    createRewards(
      validatorRewards = Seq((alice, 0.43)),
      appRewards = Seq((aliceValidatorParty, 0.43, false)),
    )
    eventually()(aliceValidatorWalletClient.listAppRewardCoupons() should have size 1)
    eventually()(aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1)
    TriggerTestUtil.setTriggersWithin(
      triggersToPauseAtStart = Seq(
        aliceValidatorBackend
          .userWalletAutomation(aliceWalletClient.config.ledgerApiUser)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]
      )
    ) {
      // and give alice another amulet.
      aliceWalletClient.tap(50)
      checkWallet(alice, aliceWalletClient, Seq((9, 10), exactly(50)), holdingFee)
    }

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
      checkWallet(alice, aliceWalletClient, Seq((59, 61)), holdingFee)
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
      aliceWalletClient.tap(1100)

      checkBalance(aliceWalletClient, Some(1), exactly(1100), exactly(0), exactly(0))
      // leads to archival of open round 0
      advanceRoundsToNextRoundOpening

      lockAmulets(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().amulets,
        100,
        sv1ScanBackend,
        Duration.ofDays(1),
        getLedgerTime,
      )
      checkBalance(
        aliceWalletClient,
        Some(2),
        (999, 1000),
        exactly(100),
        // due to merge in this round, no holding fees.
        exactly(0),
      )

      // leads to latest round being round 3
      advanceRoundsToNextRoundOpening

      checkBalance(
        aliceWalletClient,
        Some(3),
        (999, 1000),
        exactly(100),
        exactly(holdingFee),
      )
  }

  "merge also amulet that should be expired" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val mergeAmuletsTrigger = aliceValidatorBackend
      .userWalletAutomation(aliceUserName)
      .futureValue
      .trigger[CollectRewardsAndMergeAmuletsTrigger]

    clue("Pause amulet merges and expires") {
      sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].pause().futureValue
      mergeAmuletsTrigger.pause().futureValue
    }

    actAndCheck(
      "Tap amulet that will expire in a round", {
        aliceWalletClient.tap(holdingFee)
        aliceWalletClient.tap(holdingFee)
      },
    )(
      "Alice has 2 Amulets",
      _ => {
        aliceWalletClient.list().amulets should have length 2
      },
    )

    clue("Advance rounds so that holding fees become relevant") {
      advanceRoundsToNextRoundOpening
    }

    actAndCheck(
      "Resume amulets merging automation", {
        mergeAmuletsTrigger.resume()
      },
    )(
      "Amulets got merged",
      _ => {
        aliceWalletClient.list().amulets should have length 1
      },
    )
    clue("Final sanity check") {
      checkBalance(
        aliceWalletClient,
        Some(2),
        (holdingFee, 2 * holdingFee),
        exactly(0),
        // We just merged => fresh amulet
        exactly(0),
      )
    }
  }
}
