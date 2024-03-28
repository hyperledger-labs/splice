package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.codegen.java.splice.amuletrules.AppTransferContext
import com.daml.network.codegen.java.splice.ans.{
  AnsEntryContext,
  AnsEntryContext_CollectInitialEntryPayment,
}
import com.daml.network.codegen.java.splice.dsorules.Confirmation
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext
import com.daml.network.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.ANSRARC_CollectInitialEntryPayment
import com.daml.network.codegen.java.splice.wallet.subscriptions.{
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.*
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.automation.confirmation.AnsSubscriptionInitialPaymentTrigger
import com.daml.network.sv.automation.leaderbased.{
  AnsSubscriptionRenewalPaymentTrigger,
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
}
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.util.{
  Contract,
  SplitwellTestUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*

class WalletTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://ans-dir-url.com"
  private val testEntryDescription = "Sample CNS Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
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

    "list all amulets, including locked amulets, with additional position details" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        clue("Alice taps 50 amulets") {
          aliceWalletClient.list().amulets should have length 0
          aliceWalletClient.tap(50)
          eventually() {
            aliceWalletClient.list().amulets should have length 1
            aliceWalletClient.list().lockedAmulets should have length 0
          }
        }
        val startRound = aliceWalletClient.list().amulets.head.round
        val halfOriginalAmount =
          (walletUsdToAmulet(24), walletUsdToAmulet(25))

        lockAmulets(
          aliceValidatorBackend,
          aliceUserParty,
          aliceValidatorParty,
          aliceWalletClient.list().amulets,
          walletUsdToAmulet(25),
          sv1ScanBackend,
          Duration.ofDays(10),
        )

        clue("Check wallet after locking amulets") {
          aliceWalletClient.list().amulets should have length 1
          eventually()(aliceWalletClient.list().lockedAmulets should have length 1)

          aliceWalletClient.list().amulets.head.round shouldBe startRound
          // we have 0 holding fees because the amulets were created in the same round we are currently in
          aliceWalletClient.list().amulets.head.accruedHoldingFee shouldBe 0
          assertInRange(aliceWalletClient.list().amulets.head.effectiveAmount, halfOriginalAmount)

          aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound
          aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee shouldBe 0
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.effectiveAmount,
            halfOriginalAmount,
          )
        }

        // advance to next round.
        advanceRoundsByOneTick

        clue("Check wallet after advancing to next round") {
          val expectedFee =
            (walletUsdToAmulet(0.000004), walletUsdToAmulet(0.000005))
          eventually()(aliceWalletClient.list().amulets.head.round shouldBe startRound + 1)
          assertInRange(aliceWalletClient.list().amulets.head.accruedHoldingFee, expectedFee)
          assertInRange(aliceWalletClient.list().amulets.head.effectiveAmount, halfOriginalAmount)

          aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound + 1
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee,
            expectedFee,
          )
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.effectiveAmount,
            halfOriginalAmount,
          )
        }
    }

    "ensure balances are updated accordingly after a round" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(50)
      val startingBalance = eventually() {
        val startingBalance = aliceWalletClient.balance()
        startingBalance.unlockedQty shouldBe walletUsdToAmulet(50.0)
        startingBalance
      }
      val lockedQty = walletUsdToAmulet(25)

      advanceRoundsByOneTick

      lockAmulets(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().amulets,
        lockedQty,
        sv1ScanBackend,
        Duration.ofDays(10),
      )

      clue("Check balance after advancing round and locking amulets") {
        val feeCeiling = walletUsdToAmulet(1)
        eventually() {
          val expectedUnlockedAmulets = startingBalance.unlockedQty - lockedQty
          checkBalance(
            aliceWalletClient,
            Some(startingBalance.round + 1),
            (expectedUnlockedAmulets - feeCeiling, expectedUnlockedAmulets),
            (lockedQty - feeCeiling, lockedQty),
            (0, 1),
          )
        }
      }
    }

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
              "Request CNS entry", {
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
        val ansSubscriptionRenewalPaymentTrigger =
          sv1Backend.leaderBasedAutomation.trigger[AnsSubscriptionRenewalPaymentTrigger]
        setTriggersWithin(
          triggersToPauseAtStart = Seq(ansSubscriptionRenewalPaymentTrigger),
          triggersToResumeAtStart = Seq.empty,
        ) {
          actAndCheck(
            "Wait for a payment on the first subscription to become possible", {
              // Renewal duration is 30 days and entry lifetime is 90 days.
              // To reach the renewal period of alice1 subscription, we need to advance (90 - 30 days) = 60 days
              // We have already advanced by 30 days previously (10 days * 3)
              // So we will have to advance  (90 - 30 - 30 days) = 30 days to make alice1 subscription expired
              // TODO (#7609): consider replacing with stopping and starting triggers
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

    "reject ans initial subscription payment due to expired round even if confirmed acceptance exists previously" in {
      implicit env =>
        def ansSubscriptionInitialPaymentTrigger =
          sv1Backend.dsoAutomation.trigger[AnsSubscriptionInitialPaymentTrigger]

        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        clue("Alice gets some amulets") {
          aliceWalletClient.tap(50)
        }
        val entryName = "alice"
        val (_, requestId) = actAndCheck(
          "Request CNS entry", {
            aliceAnsExternalClient
              .createAnsEntry(perTestCaseName(entryName), testEntryUrl, testEntryDescription)
          },
        )(
          "the corresponding subscription request is created",
          { _ =>
            inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
              r.contractId
            }
          },
        )

        // pause ansSubscriptionInitialPaymentTrigger so payment is not accepted
        ansSubscriptionInitialPaymentTrigger.pause().futureValue

        val (_, initialPayment) = actAndCheck(
          "Accept subscription request", {
            aliceWalletClient.acceptSubscriptionRequest(requestId)
          },
        )(
          "SubscriptionInitialPayment is created",
          _ => {
            aliceWalletClient.listSubscriptions() shouldBe empty
            inside(aliceWalletClient.listSubscriptionInitialPayments()) {
              case Seq(initialPayment) => initialPayment
            }
          },
        )

        val roundCid = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .find(_.payload.round == initialPayment.payload.round)
          .value
          .contractId

        actAndCheck(
          "Advance rounds to make the round specified in SubscriptionInitialPayment closed", {
            (1 to 3).foreach(_ => advanceRoundsByOneTick)
          },
        )(
          s"The round $roundCid is closed",
          _ => {
            val openRounds = sv1Backend.listOpenMiningRounds().map(_.payload.round).toSet
            openRounds should not contain initialPayment.payload.round
          },
        )

        val now = aliceValidatorBackend.participantClient.ledger_api.time.get()
        val transferContext = sv1ScanBackend.getUnfeaturedAppTransferContext(now)
        val transferContextWithClosedRound = new AppTransferContext(
          transferContext.amuletRules,
          roundCid,
          transferContext.featuredAppRight,
        )
        actAndCheck(
          "Create a confirmation of accepting the ans initial payment with a closed round.", {
            createAnsAcceptInitialPaymentConfirmation(
              initialPayment,
              transferContextWithClosedRound,
            )
          },
        )(
          "The confirmation is created",
          _ => {
            lookupAnsAcceptInitialPaymentConfirmation(
              lookupAnsContextCid(initialPayment.payload.reference).value
            )
          },
        )

        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
          {
            actAndCheck(
              "Resume ansSubscriptionInitialPaymentTrigger to check if it should accept payment", {
                ansSubscriptionInitialPaymentTrigger.resume()
              },
            )(
              "The payment is rejected due to the round for collecting payment is no longer active",
              _ => {
                aliceWalletClient.listSubscriptionInitialPayments() shouldBe empty
              },
            )
          },
          lines => {
            forExactly(1, lines) { line =>
              line.message should include regex (s"confirmed to reject payment.+Round\\(.+\\) is no longer active")
            }
          },
        )
    }

    "auto-expire payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      actAndCheck(
        "Create a payment request, which expires after 1 minute",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          expirationTime = Duration.ofMinutes(1),
        ),
      )(
        "Check that we can see the created payment request",
        _ => aliceWalletClient.listAppPaymentRequests() should have length 1,
      )

      actAndCheck(
        "Advance time beyond the request's expiration",
        advanceTime(Duration.ofMinutes(3)),
      )(
        "Check that there are no more payment requests",
        _ => aliceWalletClient.listAppPaymentRequests() shouldBe empty,
      )
    }

    "support transfer offers" in { implicit env =>
      val (_, bob) = onboardAliceAndBob()
      aliceWalletClient.tap(100.0)

      val now = aliceValidatorBackend.participantClient.ledger_api.time.get()
      val expiration = now.plus(Duration.ofMinutes(1))

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

      advanceTime(Duration.ofMinutes(3))

      clue("Wait for the offer to expire")(eventually() {
        aliceWalletClient.listTransferOffers() should have length 0
        aliceWalletClient.listAcceptedTransferOffers() should have length 0
      })
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
      advanceRoundsByOneTick
      advanceRoundsByOneTick

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
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          Seq(sv1Backend.leaderBasedAutomation.trigger[ExpiredAmuletTrigger]),
      ) {
        clue("Check wallet after advancing to next 2 rounds") {
          eventually()(aliceWalletClient.list().amulets shouldBe empty)
        }
      }
    }

    "auto-expire locked amulet" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

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
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound + 2)
        aliceWalletClient.list().lockedAmulets should have length 1

        // The locked amulet is expired but not yet archived.
        // It will be archived when no amulets can be used as transfer input.
        // ie, in 2 rounds
        aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound + 2
        aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee shouldBe 0.000005
        aliceWalletClient.list().lockedAmulets.head.effectiveAmount shouldBe 0
      }

      // advance 2 more rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          Seq(sv1Backend.leaderBasedAutomation.trigger[ExpiredLockedAmuletTrigger]),
      ) {
        clue("Check wallet after advancing to next 2 rounds") {
          eventually()(aliceWalletClient.list().lockedAmulets shouldBe empty)
        }
      }
    }

    "generate app rewards correctly" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      actAndCheck(
        "Grant featuredAppRight on Alice's wallet",
        grantFeaturedAppRight(aliceValidatorWalletClient),
      )(
        "Check that Alice's wallet is granted",
        _ => {
          aliceValidatorClient.getValidatorUserInfo().featured shouldBe true
        },
      )

      aliceWalletClient.tap(20.0)

      eventually() {
        aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
      }

      clue("Check that no payment requests exist") {
        aliceWalletClient.listAppPaymentRequests() shouldBe empty
      }

      actAndCheck(
        "Alice creates a self-payment request",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Wait for request to be ingested",
        _ => aliceWalletClient.listAppPaymentRequests() should have length 1,
      )

      actAndCheck(
        "Alice accepts the payment request",
        aliceWalletClient
          .listAppPaymentRequests()
          .map(req => aliceWalletClient.acceptAppPaymentRequest(req.contractId)),
      )(
        "Request no longer exists",
        _ => aliceWalletClient.listAppPaymentRequests() should have length 0,
      )

      actAndCheck(
        "Advance rounds until reward coupons are issued",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for reward coupons",
        _ => {
          aliceValidatorWalletClient
            .listAppRewardCoupons() should have length 1 // Award for the first (locking) leg goes to the sender's validator
        },
      )

      inside(aliceValidatorWalletClient.listAppRewardCoupons()) { case Seq(c) =>
        c.payload.featured shouldBe true
      }

      val balanceBefore = aliceValidatorWalletClient.balance()

      actAndCheck(
        "Advance rounds until reward coupons can be collected",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for reward to be collected",
        _ => {
          aliceValidatorWalletClient
            .listAppRewardCoupons() should be(empty)
          checkBalance(
            aliceValidatorWalletClient,
            Some(balanceBefore.round + 2),
            (
              balanceBefore.unlockedQty + walletUsdToAmulet(102.5),
              balanceBefore.unlockedQty + walletUsdToAmulet(103.0),
            ),
            (balanceBefore.lockedQty, balanceBefore.lockedQty),
            (0, 1),
          )
        },
      )
    }

    "generate rewards for subscriptions" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue(
        "Advance seven rounds to ensure that rewards from previous test cases were claimed or expired"
      ) {
        Range(1, 8).foreach(_ => advanceRoundsByOneTick)
      }

      val respond = clue("Alice requests a CNS entry") {
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
          Range(1, 7).foreach(_ => advanceRoundsByOneTick),
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
          "Advance time until CNS entry is up for renewal", {
            // We time the advances so that automation doesn't trigger before payments can be made.
            // TODO (#7609): consider replacing with stopping and starting triggers
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(17)))
            advanceTimeToRoundOpen
          },
        )(
          "Wait for another coupon to be generated upon renewal",
          _ => aliceValidatorWalletClient.listAppRewardCoupons() should have length 1,
        )
      }
    }

    "issue app rewards for direct transfers to validator party" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)

      val couponsBefore = aliceValidatorWalletClient.listAppRewardCoupons().length.toLong

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(500.0)
      }

      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
      eventually()({
        bobWalletClient.balance().unlockedQty should not be (BigDecimal(0.0))
        aliceValidatorWalletClient.listAppRewardCoupons() should have length (couponsBefore + 1)
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .lastOption
          .value
          .payload
          .featured shouldBe true
      })
    }
  }

  private def lookupAnsContextCid(
      subscriptionRequestCid: SubscriptionRequest.ContractId
  )(implicit env: CNNodeTestConsoleEnvironment): Option[AnsEntryContext.ContractId] =
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(AnsEntryContext.COMPANION)(dsoParty)
      .find(_.data.reference == subscriptionRequestCid)
      .map(_.id)

  private def lookupAnsAcceptInitialPaymentConfirmation(
      ansContextCid: AnsEntryContext.ContractId
  )(implicit env: CNNodeTestConsoleEnvironment): Option[Confirmation.ContractId] = {
    val svParty = sv1Backend.getDsoInfo().svParty
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(Confirmation.COMPANION)(dsoParty)
      .find(confirmation =>
        confirmation.data.action match {
          case ansContextAction: ARC_AnsEntryContext =>
            ansContextAction.ansEntryContextCid == ansContextCid && confirmation.data.confirmer == svParty.toProtoPrimitive
          case _ => false
        }
      )
      .map(_.id)
  }

  private def createAnsAcceptInitialPaymentConfirmation(
      initialPayment: Contract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment],
      transferContext: AppTransferContext,
  )(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    val svParty = sv1Backend.getDsoInfo().svParty
    val ansRules = sv1ScanBackend.getAnsRules()
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svParty),
      readAs = Seq(dsoParty),
      optTimeout = None,
      commands = sv1Backend
        .getDsoInfo()
        .dsoRules
        .contractId
        .exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          new ARC_AnsEntryContext(
            lookupAnsContextCid(initialPayment.payload.reference).value,
            new ANSRARC_CollectInitialEntryPayment(
              new AnsEntryContext_CollectInitialEntryPayment(
                initialPayment.contractId,
                transferContext,
                ansRules.contractId,
              )
            ),
          ),
        )
        .commands
        .asScala
        .toSeq,
    )
  }
}
