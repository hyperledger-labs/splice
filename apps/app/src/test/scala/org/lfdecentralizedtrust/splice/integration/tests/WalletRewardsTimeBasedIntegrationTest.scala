package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_ModifyValidatorLicenses
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ValidatorLicenseChange,
  ValidatorLicensesModification,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.validatorlicensechange.VLC_ChangeWeight
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReceiveFaucetCouponTrigger

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class WalletRewardsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Tap amulet and do a transfer from alice to bob
      aliceWalletClient.tap(walletAmuletToUsd(50))

      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 40.0)

      // Retrieve transferred amulet in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWalletClient.list().amulets should have size 1)
      p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0)

      val openRounds = eventually() {
        import math.Ordering.Implicits.*
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually(40.seconds) {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 1
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        aliceValidatorWalletClient.listAppRewardCoupons() should have size 1
        aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        bobValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
        aliceValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
      }

      // avoid messing with the computation of balance
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val prevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually() {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 0
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0
        bobValidatorWalletClient.listValidatorLivenessActivityRecords() should have size 0

        val newBalance = bobValidatorWalletClient.balance().unlockedQty

        // We just check that the balance has increased by roughly the right amount,
        // rather then repeating the calculation for the reward amount
        // 2.85 USD per faucet coupon
        val faucetCouponAmountUsd = 2.85 * openRounds.size
        assertInRange(
          newBalance - prevBalance,
          (
            walletUsdToAmulet(-0.1 + faucetCouponAmountUsd),
            walletUsdToAmulet(0.5 + faucetCouponAmountUsd),
          ),
        )
      }
    }

    // This test verifies that the OpenMiningRoundSummary correctly sums
    // ValidatorLivenessActivityRecord weights and that rewards are
    // appropriately capped when total weights in a round are high.
    // See: test_ValidatorLivenessWeightInRunNextIssuance for similar test in daml
    "OpenMiningRoundSummary calculation uses validator activity record weights" in { implicit env =>
      val info = sv1Backend.getDsoInfo()
      val dsoParty = info.dsoParty
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      def getValidatorLicense(party: com.digitalasset.canton.topology.PartyId) = {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorLicense.COMPANION)(
            dsoParty,
            c => c.data.validator == party.toProtoPrimitive,
          )
      }

      // Change Alice's weight to a very high value
      // This should cause the per-unit issuance to be less than the default of 2.85
      val aliceWeight = BigDecimal(15000.0)
      val aliceAction = new ARC_DsoRules(
        new SRARC_ModifyValidatorLicenses(
          new ValidatorLicensesModification(
            List[ValidatorLicenseChange](
              new VLC_ChangeWeight(
                aliceValidatorParty.toProtoPrimitive,
                aliceWeight.bigDecimal,
              )
            ).asJava
          )
        )
      )

      actAndCheck(
        "Create and execute vote to change weight",
        sv1Backend.createVoteRequest(
          info.svParty.toProtoPrimitive,
          aliceAction,
          "url",
          "description",
          info.dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "license weight is updated",
        _ => {
          val licenses = getValidatorLicense(aliceValidatorParty)
          licenses should have length 1
          licenses.head.data.weight.toScala.map(BigDecimal(_)) shouldBe Some(aliceWeight)
        },
      )

      val openRounds = eventually() {
        import math.Ordering.Implicits.*
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      advanceTimeForRewardAutomationToRunForCurrentRound

      eventually() {
        aliceValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
        bobValidatorWalletClient
          .listValidatorLivenessActivityRecords() should have size openRounds.size.toLong
      }

      // Pause faucet coupon triggers to avoid balance changes during measurement
      aliceValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val alicePrevBalance = aliceValidatorWalletClient.balance().unlockedQty
      val bobPrevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Advance rounds to collect validator faucet rewards
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceTimeForRewardAutomationToRunForCurrentRound

      clue("validator faucet coupons have been collected") {
        eventually() {
          aliceValidatorWalletClient.listValidatorLivenessActivityRecords() should have size 0
          bobValidatorWalletClient.listValidatorLivenessActivityRecords() should have size 0
        }
      }

      val aliceNewBalance = aliceValidatorWalletClient.balance().unlockedQty
      val bobNewBalance = bobValidatorWalletClient.balance().unlockedQty

      val aliceReward = aliceNewBalance - alicePrevBalance
      val bobReward = bobNewBalance - bobPrevBalance

      // the per-unit issuance should be less than 2.85.
      val expectedBobRewardPerRoundMin = 2.5
      val expectedBobRewardPerRoundMax = 2.55

      val expectedBobTotalMin = expectedBobRewardPerRoundMin * openRounds.size
      val expectedBobTotalMax = expectedBobRewardPerRoundMax * openRounds.size

      assertInRange(
        bobReward,
        (
          walletUsdToAmulet(expectedBobTotalMin),
          walletUsdToAmulet(expectedBobTotalMax),
        ),
      )

      val expectedAliceTotalMin = expectedBobTotalMin * aliceWeight.toDouble
      val expectedAliceTotalMax = expectedBobTotalMax * aliceWeight.toDouble

      assertInRange(
        aliceReward,
        (
          walletUsdToAmulet(expectedAliceTotalMin),
          walletUsdToAmulet(expectedAliceTotalMax),
        ),
      )
    }

    "validator with weight 0 does not record liveness activity but still reports active" in {
      implicit env =>
        val info = sv1Backend.getDsoInfo()
        val dsoParty = info.dsoParty
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        def getAliceLicense() = {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(ValidatorLicense.COMPANION)(
              dsoParty,
              c => c.data.validator == aliceValidatorParty.toProtoPrimitive,
            )
        }

        // Get initial lastActiveAt
        val initialLicense = eventually() {
          val licenses = getAliceLicense()
          licenses should have length 1
          licenses.head
        }
        val initialLastActiveAt = initialLicense.data.lastActiveAt

        // Change validator license weight to 0
        val zeroWeight = BigDecimal(0.0)
        val action = new ARC_DsoRules(
          new SRARC_ModifyValidatorLicenses(
            new ValidatorLicensesModification(
              List[ValidatorLicenseChange](
                new VLC_ChangeWeight(
                  aliceValidatorParty.toProtoPrimitive,
                  zeroWeight.bigDecimal,
                )
              ).asJava
            )
          )
        )

        actAndCheck(
          "Create and execute vote to change weight",
          sv1Backend.createVoteRequest(
            info.svParty.toProtoPrimitive,
            action,
            "url",
            "description",
            info.dsoRules.payload.config.voteRequestTimeout,
            None,
          ),
        )(
          "license weight is updated",
          _ => {
            val licenses = getAliceLicense()
            licenses should have length 1
            licenses.head.data.weight.toScala.map(BigDecimal(_)) shouldBe Some(zeroWeight)
          },
        )

        advanceTimeForRewardAutomationToRunForCurrentRound

        // Wait for trigger to run and verify no liveness activity records are created for Alice
        // Bob (with the default weight) should still have activity records
        eventually() {
          aliceValidatorWalletClient
            .listValidatorLivenessActivityRecords() should have size 0
          bobValidatorWalletClient
            .listValidatorLivenessActivityRecords() should not be empty
        }

        // Advance time past activityReportMinInterval (1 hour) to trigger ReportActive
        actAndCheck(
          "Advance time past activityReportMinInterval",
          advanceTime(java.time.Duration.ofHours(2)),
        )(
          "lastActiveAt gets updated even with weight 0",
          _ => {
            val updatedLicense = getAliceLicense()
            updatedLicense should have length 1
            updatedLicense.head.data.lastActiveAt should not be initialLastActiveAt
          },
        )
    }
  }
}
