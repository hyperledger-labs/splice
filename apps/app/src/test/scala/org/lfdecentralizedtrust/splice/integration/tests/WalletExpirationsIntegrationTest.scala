package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredAmuletTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{SplitwellTestUtil, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.{
  ExpireAppPaymentRequestsTrigger,
  ExpireTransferOfferTrigger,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import java.util.UUID

class WalletExpirationsIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          // without this, alice's validator gets AppRewardCoupons that complicate testing
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )
      // Very short round ticks
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )

  "A wallet" should {
    "auto-expire payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceUserName = aliceWalletClient.config.ledgerApiUser

      setTriggersWithin(
        Seq(
          aliceValidatorBackend
            .userWalletAutomation(aliceUserName)
            .futureValue
            .trigger[ExpireAppPaymentRequestsTrigger]
        ),
        Seq.empty,
      ) {
        actAndCheck(
          "Create a payment request, which expires after 0.1 second",
          createSelfPaymentRequest(
            aliceValidatorBackend.participantClientWithAdminToken,
            aliceWalletClient.config.ledgerApiUser,
            aliceUserParty,
            expirationTime = Duration.ofMillis(100),
          ),
        )(
          "Check that we can see the created payment request",
          _ => aliceWalletClient.listAppPaymentRequests() should have length 1,
        )
      }

      clue("The payment request has been expired after resuming the trigger") {
        eventually() {
          aliceWalletClient.listAppPaymentRequests() shouldBe empty
        }
      }
    }

    "support expiring transfer offers" in { implicit env =>
      val (_, bob) = onboardAliceAndBob()
      val aliceUserName = aliceWalletClient.config.ledgerApiUser
      aliceWalletClient.tap(100.0)

      setTriggersWithin(
        Seq(
          aliceValidatorBackend
            .userWalletAutomation(aliceUserName)
            .futureValue
            .trigger[ExpireTransferOfferTrigger]
        ),
        Seq.empty,
      ) {
        val expiration = CantonTimestamp.now().plus(Duration.ofMillis(100))
        val (_, _) = actAndCheck(
          "Alice creates a transfer offer", {
            aliceWalletClient.createTransferOffer(
              bob,
              1.0,
              "should expire before accepted",
              expiration,
              UUID.randomUUID.toString,
            )
          },
        )(
          "Wait for new offer to be ingested",
          _ => {
            aliceWalletClient.listTransferOffers() should have length 1
            bobWalletClient.listTransferOffers() should have length 1
          },
        )
      }

      clue("Wait for the offer to expire") {
        eventually() {
          aliceWalletClient.listTransferOffers() should have length 0
          aliceWalletClient.listAcceptedTransferOffers() should have length 0
        }
      }
    }

    "auto-expire amulet" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val tapAmountUsd = 0.000005
      clue(s"Alice taps $tapAmountUsd USD") {
        aliceWalletClient.tap(tapAmountUsd)
        eventually() {
          aliceWalletClient.list().amulets should have length 1
          aliceWalletClient.list().lockedAmulets should have length 0
          // we have 0 holding fees because the amulets were created in the same round we are currently in
          aliceWalletClient.list().amulets.head.accruedHoldingFee shouldBe 0
          assertInRange(aliceWalletClient.list().amulets.head.effectiveAmount, (0, 1))
        }
      }

      val startRound = aliceWalletClient.list().amulets.head.round

      // advance 2 rounds.
      advanceRoundsByOneTickViaAutomation()
      advanceRoundsByOneTickViaAutomation()

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWalletClient.list().amulets.head.round shouldBe startRound + 2)
        aliceWalletClient.list().amulets should have length 1

        // The amulet is expired but not yet archived.
        // They will be archived when no amulets can be used as transfer input.
        // ie, in 2 round
        aliceWalletClient
          .list()
          .amulets
          .head
          .accruedHoldingFee shouldBe walletUsdToAmulet(tapAmountUsd)
        aliceWalletClient.list().amulets.head.effectiveAmount shouldBe 0
      }

      // advance 2 more rounds.
      advanceRoundsByOneTickViaAutomation()
      advanceRoundsByOneTickViaAutomation()

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger]),
      ) {
        clue("Check wallet after advancing to next 2 rounds") {
          eventually()(aliceWalletClient.list().amulets shouldBe empty)
        }
      }
    }

  }
}
