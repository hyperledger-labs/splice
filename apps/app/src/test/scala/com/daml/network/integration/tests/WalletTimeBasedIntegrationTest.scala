package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.*
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SplitwellTestUtil, TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*

import java.time.Duration
import java.util.UUID

class WalletTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .addConfigTransform((_, config) => {
        // TODO(M3-63) Currently, auto-expiration of unclaimed rewards is disabled by default, and enabled only where needed.
        // In the cluster it currently cannot be enabled due to lack of resiliency to unavailable validators
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.focus(_.enableUnclaimedRewardExpiration).replace(true)
        )(config)
      })
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(splitwellDarPath)
        aliceValidator.participantClient.upload_dar_unless_exists(directoryDarPath)
        bobValidator.participantClient.upload_dar_unless_exists(directoryDarPath)
      })

  "A wallet" should {

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      clue("Alice taps 50 coins") {
        aliceWallet.list().coins should have length 0
        aliceWallet.tap(50)
        eventually() {
          aliceWallet.list().coins should have length 1
          aliceWallet.list().lockedCoins should have length 0
        }
      }
      val startRound = aliceWallet.list().coins.head.round

      lockCoins(
        aliceValidator,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        25,
        sv1Scan,
        Duration.ofDays(10),
      )

      clue("Check wallet after locking coins") {
        aliceWallet.list().coins should have length 1
        eventually()(aliceWallet.list().lockedCoins should have length 1)

        aliceWallet.list().coins.head.round shouldBe startRound
        // we have 0 holding fees because the coins were created in the same round we are currently in
        aliceWallet.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().coins.head.effectiveAmount, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe startRound
        aliceWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveAmount, (24.0, 25.0))
      }

      // advance to next round.
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next round") {
        eventually()(aliceWallet.list().coins.head.round shouldBe startRound + 1)
        assertInRange(aliceWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
        assertInRange(aliceWallet.list().coins.head.effectiveAmount, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe startRound + 1
        assertInRange(
          aliceWallet.list().lockedCoins.head.accruedHoldingFee,
          (0.000004, 0.000005),
        )
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveAmount, (24.0, 25.0))
      }
    }

    "ensure balances are updated accordingly after a round" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()
      aliceWallet.tap(50)
      val startingBalance = eventually() {
        val startingBalance = aliceWallet.balance()
        startingBalance.unlockedQty shouldBe 50.0
        startingBalance
      }
      val lockedQty = 25

      advanceRoundsByOneTick

      lockCoins(
        aliceValidator,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        lockedQty,
        sv1Scan,
        Duration.ofDays(10),
      )

      clue("Check balance after advancing round and locking coins") {
        eventually() {
          val expectedUnlockedCoins = startingBalance.unlockedQty - lockedQty
          checkBalance(
            aliceWallet,
            startingBalance.round + 1,
            (expectedUnlockedCoins - 1, expectedUnlockedCoins),
            (lockedQty - 1, lockedQty),
            (0, 1),
          )
        }
      }
    }

    "allow a user to list multiple subscriptions in different states" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      clue("Alice gets some coins") {
        aliceWallet.tap(50)
      }
      clue("Setting up directory as provider for the created subscriptions") {
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }
      aliceWallet.listSubscriptions() shouldBe empty

      bracket(
        clue("Creating 3 subscriptions, 10 days apart") {
          for ((name, i) <- List("alice1", "alice2", "alice3").map(perTestCaseName).zipWithIndex) {

            val (_, requestId) = actAndCheck(
              "Request directory entry", {
                aliceDirectory.requestDirectoryEntry(name)._1
              },
            )(
              "the corresponding subscription request is created",
              { _ =>
                inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
                  r.subscriptionRequest.contractId
                }
              },
            )
            actAndCheck(
              "Accept subscription request", {
                aliceWallet.acceptSubscriptionRequest(requestId)
              },
            )(
              "subscription is created",
              _ => {
                val subs = aliceWallet.listSubscriptions()
                subs should have length (i + 1L)
              },
            )
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(10))
            advanceTimeToRoundOpen
          }
        },
        cancelAllSubscriptions(aliceWallet, aliceValidator),
      ) {
        bracket(
          clue("Stopping directory backend so that payments aren't collected.") {
            directory.stop()
          },
          clue("Starting directory backend again so other tests can use it") {
            directory.startSync()
          },
        ) {
          actAndCheck(
            "Wait for a payment on the first subscription to become possible", {
              // We time the advances so that automation doesn't trigger before
              // payments can be made.
              advanceTimeAndWaitForRoundAutomation(Duration.ofDays(59).minus(Duration.ofMinutes(9)))
              advanceTimeToRoundOpen
            },
          )(
            "2 idle subscriptions and 1 payment are listed",
            _ => {
              val subs = aliceWallet.listSubscriptions()
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

    "auto-expire payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck(
        "Create a payment request, which expires after 1 minute",
        createSelfPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          expirationTime = Duration.ofMinutes(1),
        ),
      )(
        "Check that we can see the created payment request",
        _ => aliceWallet.listAppPaymentRequests() should have length 1,
      )

      actAndCheck(
        "Advance time beyond the request's expiration",
        advanceTime(Duration.ofMinutes(3)),
      )(
        "Check that there are no more payment requests",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )
    }

    "support transfer offers" in { implicit env =>
      val (_, bob) = onboardAliceAndBob()
      aliceWallet.tap(100.0)

      val now = aliceValidator.participantClient.ledger_api.time.get()
      val expiration = now.plus(Duration.ofMinutes(1))

      val (_, _) = actAndCheck(
        "Alice creates a transfer offer", {
          aliceWallet.createTransferOffer(
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
          aliceWallet.listTransferOffers() should have length 1
          bobWallet.listTransferOffers() should have length 1
        },
      )

      advanceTime(Duration.ofMinutes(3))

      clue("Wait for the offer to expire")(eventually() {
        aliceWallet.listTransferOffers() should have length 0
        aliceWallet.listAcceptedTransferOffers() should have length 0
      })
    }

    "auto-expire coin" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      clue("Alice taps 0.000005 coins") {
        aliceWallet.tap(0.000005)
        eventually() {
          aliceWallet.list().coins should have length 1
          aliceWallet.list().lockedCoins should have length 0
          // we have 0 holding fees because the coins were created in the same round we are currently in
          aliceWallet.list().coins.head.accruedHoldingFee shouldBe 0
          assertInRange(aliceWallet.list().coins.head.effectiveAmount, (0, 1))
        }
      }

      val startRound = aliceWallet.list().coins.head.round

      // advance 2 rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWallet.list().coins.head.round shouldBe startRound + 2)
        aliceWallet.list().coins should have length 1

        // The coin is expired but not yet archived.
        // They will be archived when no coins can be used as transfer input.
        // ie, in 2 round
        assertInRange(aliceWallet.list().coins.head.accruedHoldingFee, (0.000009, 0.00001))
        assertInRange(aliceWallet.list().coins.head.effectiveAmount, (-0.000005, -0.000004))
      }

      // advance 2 more rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 rounds") {
        eventually()(aliceWallet.list().coins shouldBe empty)
      }
    }

    "auto-expire locked coin" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      clue("Alice taps 0.11001 coins") {
        aliceWallet.tap(0.11001)
        eventually() {
          aliceWallet.list().coins should have length 1
          aliceWallet.list().lockedCoins should have length 0
        }
      }
      val startRound = aliceWallet.list().coins.head.round

      lockCoins(
        aliceValidator,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        0.000005,
        sv1Scan,
        Duration.ofMinutes(1),
      )

      clue("Check wallet after locking coins") {
        aliceWallet.list().coins should have length 1
        eventually()(aliceWallet.list().lockedCoins should have length 1)

        aliceWallet.list().coins.head.round shouldBe startRound
        // we have 0 holding fees because the coins were created in the same round we are currently in
        aliceWallet.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().coins.head.effectiveAmount, (0, 1))

        aliceWallet.list().lockedCoins.head.round shouldBe startRound
        aliceWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveAmount, (0, 1))
      }

      // advance 2 rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWallet.list().lockedCoins.head.round shouldBe startRound + 2)
        aliceWallet.list().lockedCoins should have length 1

        // The locked coin is expired but not yet archived.
        // It will be archived when no coins can be used as transfer input.
        // ie, in 2 rounds
        aliceWallet.list().lockedCoins.head.round shouldBe startRound + 2
        assertInRange(aliceWallet.list().lockedCoins.head.accruedHoldingFee, (0.000009, 0.00001))
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveAmount, (-0.000005, -0.000004))
      }

      // advance 2 more rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 rounds") {
        eventually()(aliceWallet.list().lockedCoins shouldBe empty)
      }
    }

    "generate app rewards correctly" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      grantFeaturedAppRight(aliceValidatorWallet)
      aliceWallet.tap(20.0)

      eventually() {
        aliceValidatorWallet.listAppRewardCoupons() should be(empty)
      }

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      actAndCheck(
        "Alice creates a self-payment request",
        createSelfPaymentRequest(
          aliceValidator.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Wait for request to be ingested",
        _ => aliceWallet.listAppPaymentRequests() should have length 1,
      )

      actAndCheck(
        "Alice accepts the payment request",
        aliceWallet
          .listAppPaymentRequests()
          .map(req => aliceWallet.acceptAppPaymentRequest(req.appPaymentRequest.contractId)),
      )("Request no longer exists", _ => aliceWallet.listAppPaymentRequests() should have length 0)

      actAndCheck(
        "Advance rounds until reward coupons are issued",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for reward coupons",
        _ => {
          aliceValidatorWallet
            .listAppRewardCoupons() should have length 1 // Award for the first (locking) leg goes to the sender's validator
        },
      )

      inside(aliceValidatorWallet.listAppRewardCoupons()) { case Seq(c) =>
        c.payload.featured shouldBe true
      }

      val balanceBefore = aliceValidatorWallet.balance()

      actAndCheck(
        "Advance rounds until reward coupons can be collected",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for reward to be collected",
        _ => {
          aliceValidatorWallet
            .listAppRewardCoupons() should be(empty)
          checkBalance(
            aliceValidatorWallet,
            balanceBefore.round + 2,
            (balanceBefore.unlockedQty + 2.5, balanceBefore.unlockedQty + 3.0),
            (balanceBefore.lockedQty, balanceBefore.lockedQty),
            (0, 1),
          )
        },
      )
    }

    "handles rewards correctly in the context of 3rd party apps" in { implicit env =>
      val (_, bobUserParty, _, splitwellProviderParty, key, _) =
        initSplitwellTest()

      aliceWallet.tap(350.0)

      def transferAndCheckRewards(expectedAppRewardsRange: (BigDecimal, BigDecimal)) = {
        clue("Transfer some cc through splitwell") {
          splitwellTransfer(aliceSplitwell, aliceWallet, bobUserParty, BigDecimal(100.0), key)
        }

        val aliceValidatorStartBalance = aliceValidatorWallet.balance()
        val providerStartBalance = splitwellProviderWallet.balance()

        actAndCheck(
          "Advance rounds until reward coupons are issued",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Wait for all reward coupons",
          _ => {
            // App reward coupon to alice's validator for the first (locking) leg
            aliceValidatorWallet.listAppRewardCoupons() should have length 1
            // App reward to splitwell provider for the second leg
            splitwellProviderWallet.listAppRewardCoupons() should have length 1
            // One validator reward coupon per leg to alice's validator
            aliceValidatorWallet.listValidatorRewardCoupons() should have length 2
          },
        )

        actAndCheck(
          "Advance rounds again to get rewards",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Earn rewards",
          _ => {
            aliceValidatorWallet.listAppRewardCoupons() should be(empty)
            splitwellProviderWallet.listAppRewardCoupons() should be(empty)
            aliceValidatorWallet.listValidatorRewardCoupons() should be(empty)
            checkBalance(
              aliceValidatorWallet,
              aliceValidatorStartBalance.round + 4,
              (
                aliceValidatorStartBalance.unlockedQty,
                aliceValidatorStartBalance.unlockedQty + 5.0,
              ),
              (0, 0),
              (0, 1),
            )
            checkBalance(
              splitwellProviderWallet,
              providerStartBalance.round + 4,
              (
                providerStartBalance.unlockedQty + expectedAppRewardsRange._1,
                providerStartBalance.unlockedQty + expectedAppRewardsRange._2,
              ),
              (0, 0),
              (0, 1),
            )
          },
        )
      }

      transferAndCheckRewards((102.9, 103))

      actAndCheck(
        "Splitwell cancels its own featured app right",
        splitwellProviderWallet.cancelFeaturedAppRight(),
      )(
        "Splitwell is no longer featured",
        _ => sv1Scan.lookupFeaturedAppRight(splitwellProviderParty) should be(None),
      )

      transferAndCheckRewards((0.5, 0.6))
    }

    "generate rewards for subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      val dirParty = clue(
        "Getting directory party ID (will fail if another test stopped the directory and failed before starting it again)"
      ) {
        directory.getProviderPartyId()
      }

      clue(
        "Advance seven rounds to ensure that rewards from previous test cases were claimed or expired"
      ) {
        Range(1, 8).foreach(_ => advanceRoundsByOneTick)
      }

      clue("Request install and wait for provider to auto-accept") {
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectory.requestDirectoryEntry(testEntryName)
      }
      bracket(
        clue("Alice obtains some coins and accepts the subscription") {
          aliceWallet.tap(50.0)
          aliceWallet.acceptSubscriptionRequest(subReqId)
        },
        cancelAllSubscriptions(aliceWallet, aliceValidator),
      ) {
        clue("Getting Alice's new entry") {
          eventuallySucceeds() {
            directory.lookupEntryByName(testEntryName)
          }
        }

        clue("Wait for reward coupons to be issued") {
          eventually()({
            aliceValidatorWallet.listAppRewardCoupons() should have length 1
            aliceValidatorWallet.listValidatorRewardCoupons() should have length 2
            directory.participantClient.ledger_api_extensions.acs
              .filterJava(coinCodegen.AppRewardCoupon.COMPANION)(
                dirParty,
                _.data.provider == dirParty.toProtoPrimitive,
              ) should have length 1
          })
        }

        actAndCheck(
          "Advance six rounds - all rewards should be claimed or expired",
          Range(1, 7).foreach(_ => advanceRoundsByOneTick),
        )(
          "",
          _ => {
            aliceValidatorWallet.listAppRewardCoupons() should be(empty)
            aliceValidatorWallet.listValidatorRewardCoupons() should be(empty)
            directory.participantClient.ledger_api_extensions.acs
              .filterJava(coinCodegen.AppRewardCoupon.COMPANION)(
                dirParty,
                _.data.provider == dirParty.toProtoPrimitive,
              ) should be(empty)
          },
        )

        actAndCheck(
          "Advance time until directory entry is up for renewal", {
            // We time the advances so that automation doesn't trigger before payments can be made.
            advanceTimeAndWaitForRoundAutomation(Duration.ofDays(89).minus(Duration.ofMinutes(17)))
            advanceTimeToRoundOpen
          },
        )(
          "Wait for another coupon to be generated upon renewal",
          _ => aliceValidatorWallet.listAppRewardCoupons() should have length 1,
        )
      }
    }

    "issue app rewards for direct transfers to validator party" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWallet)

      val couponsBefore = aliceValidatorWallet.listAppRewardCoupons().length.toLong

      clue("Tap to get some coins") {
        aliceWallet.tap(500.0)
      }

      grantFeaturedAppRight(aliceValidatorWallet)
      p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobUserParty, 40.0)
      eventually()({
        bobWallet.balance().unlockedQty should not be (BigDecimal(0.0))
        aliceValidatorWallet.listAppRewardCoupons() should have length (couponsBefore + 1)
        aliceValidatorWallet.listAppRewardCoupons().lastOption.value.payload.featured shouldBe true
      })
    }

  }
}
