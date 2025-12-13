package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.*
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AnsSubscriptionRenewalPaymentTrigger,
  ExpiredLockedAmuletTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  SplitwellTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.time.Duration

class WalletTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          // without this, the test "generate app rewards correctly" has as flaky balance.
          // None of the other tests care about it.
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          // without this, alice's validator gets AppRewardCoupons that complicate testing
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )

  "A wallet" should {

    "allow a user to list multiple subscriptions in different states" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Alice gets some amulets") {
        aliceWalletClient.tap(50)
      }
      aliceWalletClient.listSubscriptions() shouldBe empty

      bracket(
        clue("Creating 3 subscriptions, 10 days apart") {
          for ((name, i) <- List("alice1", "alice2", "alice3").map(perTestCaseName).zipWithIndex) {

            val (_, requestId) = actAndCheck(
              "Request ANS entry", {
                aliceAnsExternalClient
                  .createAnsEntry(name, testEntryUrl, testEntryDescription)
              },
            )(
              "the corresponding subscription request is created",
              { _ =>
                inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
                  r.contractId
                }
              },
            )
            actAndCheck(
              "Accept subscription request", {
                aliceWalletClient.acceptSubscriptionRequest(requestId)
              },
            )(
              "subscription is created",
              _ => {
                val subs = aliceWalletClient.listSubscriptions()
                subs should have length (i + 1L)
              },
            )
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(10))
            advanceTimeToRoundOpen
          }
        },
        cancelAllSubscriptions(aliceWalletClient),
      ) {
        val ansSubscriptionRenewalPaymentTriggers =
          activeSvs.map(_.dsoDelegateBasedAutomation.trigger[AnsSubscriptionRenewalPaymentTrigger])
        setTriggersWithin(
          triggersToPauseAtStart = ansSubscriptionRenewalPaymentTriggers,
          triggersToResumeAtStart = Seq.empty,
        ) {
          actAndCheck(
            "Wait for a payment on the first subscription to become possible", {
              // Renewal duration is 30 days and entry lifetime is 90 days.
              // To reach the renewal period of alice1 subscription, we need to advance (90 - 30 days) = 60 days
              // We have already advanced by 30 days previously (10 days * 3)
              // So we will have to advance  (90 - 30 - 30 days) = 30 days to make alice1 subscription expired
              // TODO (#996): consider replacing with stopping and starting triggers
              advanceTimeAndWaitForRoundAutomation(Duration.ofDays(30))
              advanceTimeToRoundOpen
            },
          )(
            "2 idle subscriptions and 1 payment are listed",
            _ => {
              val subs = aliceWalletClient.listSubscriptions()
              subs should have length 3
              subs
                .collect(_.state match {
                  case s: HttpWalletAppClient.SubscriptionIdleState => s
                }) should have length 2
              subs
                .collect(_.state match {
                  case s: HttpWalletAppClient.SubscriptionPayment => s
                }) should have length 1
            },
          )
        }
      }
    }

    // TODO(#977): this one was trickier to make a non-time-based test
    "auto-expire locked amulet" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      // The previous test may leave a round half-open which makes this test behave differently when it runs in isolation vs the whole suite.
      // We therefore first advance a full round to make this test start from a more deterministic state.
      advanceRoundsToNextRoundOpening

      clue("Alice taps 0.11001 amulets") {
        aliceWalletClient.tap(0.11001)
        eventually() {
          aliceWalletClient.list().amulets should have length 1
          aliceWalletClient.list().lockedAmulets should have length 0
        }
      }
      val startRound = aliceWalletClient.list().amulets.head.round

      lockAmulets(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().amulets,
        0.000005,
        sv1ScanBackend,
        Duration.ofMinutes(1),
        getLedgerTime,
      )

      clue("Check wallet after locking amulets") {
        aliceWalletClient.list().amulets should have length 1
        eventually()(aliceWalletClient.list().lockedAmulets should have length 1)

        aliceWalletClient.list().amulets.head.round shouldBe startRound
        // we have 0 holding fees because the amulets were created in the same round we are currently in
        aliceWalletClient.list().amulets.head.accruedHoldingFee shouldBe 0
        assertInRange(
          aliceWalletClient.list().amulets.head.effectiveAmount,
          (0, walletUsdToAmulet(1)),
        )

        aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound
        aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWalletClient.list().lockedAmulets.head.effectiveAmount, (0, 1))
      }

      // advance 2 rounds.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound + 2)
        logger.debug(
          s"lockedAmulet has round: ${aliceWalletClient.list().lockedAmulets.head.round}"
        )
        aliceWalletClient.list().lockedAmulets should have length 1

        // The locked amulet is expired but not yet archived.
        // It will be archived when no amulets can be used as transfer input.
        // ie, in 2 rounds
        aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee shouldBe 0.000005
        aliceWalletClient.list().lockedAmulets.head.effectiveAmount shouldBe 0
      }

      // advance 2 more rounds.
      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger]),
      ) {
        clue("Check wallet after advancing to next 2 rounds") {
          eventually()(aliceWalletClient.list().lockedAmulets shouldBe empty)
        }
      }
    }

    "generate rewards for subscriptions" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue(
        "Advance seven rounds to ensure that rewards from previous test cases were claimed or expired"
      ) {
        Range(1, 8).foreach(_ => advanceRoundsToNextRoundOpening)
      }

      val respond = clue("Alice requests an ANS entry") {
        aliceAnsExternalClient.createAnsEntry(
          testEntryName,
          testEntryUrl,
          testEntryDescription,
        )
      }
      bracket(
        clue("Alice obtains some amulets and accepts the subscription") {
          aliceWalletClient.tap(50.0)
          aliceWalletClient.acceptSubscriptionRequest(respond.subscriptionRequestCid)
        },
        cancelAllSubscriptions(aliceWalletClient),
      ) {
        clue("Getting Alice's new entry") {
          eventuallySucceeds() {
            sv1ScanBackend.lookupEntryByName(testEntryName)
          }
        }

        clue("Wait for reward coupons to be issued") {
          eventually()({
            aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
            aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 2
            sv1Backend.participantClient.ledger_api_extensions.acs
              .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
                dsoParty,
                _.data.provider == dsoParty.toProtoPrimitive,
              ) should have length 1
          })
        }

        actAndCheck(
          "Advance six rounds - all rewards should be claimed or expired",
          Range(1, 7).foreach(_ => advanceRoundsToNextRoundOpening),
        )(
          "",
          _ => {
            aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
            aliceValidatorWalletClient.listValidatorRewardCoupons() should be(empty)
            sv1Backend.participantClient.ledger_api_extensions.acs
              .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
                dsoParty,
                _.data.provider == dsoParty.toProtoPrimitive,
              ) should be(empty)
          },
        )

        actAndCheck(
          "Advance time until ANS entry is up for renewal", {
            // We time the advances so that automation doesn't trigger before payments can be made.
            // TODO (#996): consider replacing with stopping and starting triggers
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(17)))
            advanceTimeToRoundOpen
          },
        )(
          "Wait for another coupon to be generated upon renewal",
          _ => aliceValidatorWalletClient.listAppRewardCoupons() should have length 1,
        )
      }
    }

  }

}
