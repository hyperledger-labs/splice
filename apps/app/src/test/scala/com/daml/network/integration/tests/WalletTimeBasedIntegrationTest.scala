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

import java.time.Duration
import java.util.UUID

class WalletTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
  private val testEntryName = "mycoolentry.unverified.cns"
  private val testEntryUrl = "https://cns-dir-url.com"
  private val testEntryDescription = "Sample CNS Directory Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })

  "A wallet" should {

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      clue("Alice taps 50 coins") {
        aliceWalletClient.list().coins should have length 0
        aliceWalletClient.tap(50)
        eventually() {
          aliceWalletClient.list().coins should have length 1
          aliceWalletClient.list().lockedCoins should have length 0
        }
      }
      val startRound = aliceWalletClient.list().coins.head.round

      lockCoins(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().coins,
        25,
        sv1ScanBackend,
        Duration.ofDays(10),
      )

      clue("Check wallet after locking coins") {
        aliceWalletClient.list().coins should have length 1
        eventually()(aliceWalletClient.list().lockedCoins should have length 1)

        aliceWalletClient.list().coins.head.round shouldBe startRound
        // we have 0 holding fees because the coins were created in the same round we are currently in
        aliceWalletClient.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWalletClient.list().coins.head.effectiveAmount, (24.0, 25.0))

        aliceWalletClient.list().lockedCoins.head.round shouldBe startRound
        aliceWalletClient.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWalletClient.list().lockedCoins.head.effectiveAmount, (24.0, 25.0))
      }

      // advance to next round.
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next round") {
        eventually()(aliceWalletClient.list().coins.head.round shouldBe startRound + 1)
        assertInRange(aliceWalletClient.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
        assertInRange(aliceWalletClient.list().coins.head.effectiveAmount, (24.0, 25.0))

        aliceWalletClient.list().lockedCoins.head.round shouldBe startRound + 1
        assertInRange(
          aliceWalletClient.list().lockedCoins.head.accruedHoldingFee,
          (0.000004, 0.000005),
        )
        assertInRange(aliceWalletClient.list().lockedCoins.head.effectiveAmount, (24.0, 25.0))
      }
    }

    "ensure balances are updated accordingly after a round" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(50)
      val startingBalance = eventually() {
        val startingBalance = aliceWalletClient.balance()
        startingBalance.unlockedQty shouldBe 50.0
        startingBalance
      }
      val lockedQty = 25

      advanceRoundsByOneTick

      lockCoins(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().coins,
        lockedQty,
        sv1ScanBackend,
        Duration.ofDays(10),
      )

      clue("Check balance after advancing round and locking coins") {
        eventually() {
          val expectedUnlockedCoins = startingBalance.unlockedQty - lockedQty
          checkBalance(
            aliceWalletClient,
            Some(startingBalance.round + 1),
            (expectedUnlockedCoins - 1, expectedUnlockedCoins),
            (lockedQty - 1, lockedQty),
            (0, 1),
          )
        }
      }
    }

    "allow a user to list multiple subscriptions in different states" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Alice gets some coins") {
        aliceWalletClient.tap(50)
      }
      clue("Setting up directory as provider for the created subscriptions") {
        aliceDirectoryClient.requestDirectoryInstall()
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }
      aliceWalletClient.listSubscriptions() shouldBe empty

      bracket(
        clue("Creating 3 subscriptions, 10 days apart") {
          for ((name, i) <- List("alice1", "alice2", "alice3").map(perTestCaseName).zipWithIndex) {

            val (_, requestId) = actAndCheck(
              "Request directory entry", {
                aliceDirectoryClient
                  .requestDirectoryEntry(name, testEntryUrl, testEntryDescription)
                  ._1
              },
            )(
              "the corresponding subscription request is created",
              { _ =>
                inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
                  r.subscriptionRequest.contractId
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
        cancelAllSubscriptions(aliceWalletClient, aliceValidatorBackend),
      ) {
        // TODO (#7609): replace with stopping and starting triggers
        bracket(
          clue("Stopping directory backend so that payments aren't collected.") {
            directoryBackend.stop()
          },
          clue("Starting directory backend again so other tests can use it") {
            directoryBackend.startSync()
          },
        ) {
          actAndCheck(
            "Wait for a payment on the first subscription to become possible", {
              // We time the advances so that automation doesn't trigger before
              // payments can be made.
              // TODO (#7609): consider replacing with stopping and starting triggers
              advanceTimeAndWaitForRoundAutomation(Duration.ofDays(59).minus(Duration.ofMinutes(9)))
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

    "auto-expire coin" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Alice taps 0.000005 coins") {
        aliceWalletClient.tap(0.000005)
        eventually() {
          aliceWalletClient.list().coins should have length 1
          aliceWalletClient.list().lockedCoins should have length 0
          // we have 0 holding fees because the coins were created in the same round we are currently in
          aliceWalletClient.list().coins.head.accruedHoldingFee shouldBe 0
          assertInRange(aliceWalletClient.list().coins.head.effectiveAmount, (0, 1))
        }
      }

      val startRound = aliceWalletClient.list().coins.head.round

      // advance 2 rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWalletClient.list().coins.head.round shouldBe startRound + 2)
        aliceWalletClient.list().coins should have length 1

        // The coin is expired but not yet archived.
        // They will be archived when no coins can be used as transfer input.
        // ie, in 2 round
        assertInRange(aliceWalletClient.list().coins.head.accruedHoldingFee, (0.000009, 0.00001))
        assertInRange(aliceWalletClient.list().coins.head.effectiveAmount, (-0.000005, -0.000004))
      }

      // advance 2 more rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 rounds") {
        eventually()(aliceWalletClient.list().coins shouldBe empty)
      }
    }

    "auto-expire locked coin" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      clue("Alice taps 0.11001 coins") {
        aliceWalletClient.tap(0.11001)
        eventually() {
          aliceWalletClient.list().coins should have length 1
          aliceWalletClient.list().lockedCoins should have length 0
        }
      }
      val startRound = aliceWalletClient.list().coins.head.round

      lockCoins(
        aliceValidatorBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWalletClient.list().coins,
        0.000005,
        sv1ScanBackend,
        Duration.ofMinutes(1),
      )

      clue("Check wallet after locking coins") {
        aliceWalletClient.list().coins should have length 1
        eventually()(aliceWalletClient.list().lockedCoins should have length 1)

        aliceWalletClient.list().coins.head.round shouldBe startRound
        // we have 0 holding fees because the coins were created in the same round we are currently in
        aliceWalletClient.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWalletClient.list().coins.head.effectiveAmount, (0, 1))

        aliceWalletClient.list().lockedCoins.head.round shouldBe startRound
        aliceWalletClient.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWalletClient.list().lockedCoins.head.effectiveAmount, (0, 1))
      }

      // advance 2 rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 round") {
        eventually()(aliceWalletClient.list().lockedCoins.head.round shouldBe startRound + 2)
        aliceWalletClient.list().lockedCoins should have length 1

        // The locked coin is expired but not yet archived.
        // It will be archived when no coins can be used as transfer input.
        // ie, in 2 rounds
        aliceWalletClient.list().lockedCoins.head.round shouldBe startRound + 2
        assertInRange(
          aliceWalletClient.list().lockedCoins.head.accruedHoldingFee,
          (0.000009, 0.00001),
        )
        assertInRange(
          aliceWalletClient.list().lockedCoins.head.effectiveAmount,
          (-0.000005, -0.000004),
        )
      }

      // advance 2 more rounds.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next 2 rounds") {
        eventually()(aliceWalletClient.list().lockedCoins shouldBe empty)
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
          .map(req => aliceWalletClient.acceptAppPaymentRequest(req.appPaymentRequest.contractId)),
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

      aliceWalletClient.tap(350.0)

      def transferAndCheckRewards(expectedAppRewardsRange: (BigDecimal, BigDecimal)) = {
        clue("Transfer some cc through splitwell") {
          splitwellTransfer(
            aliceSplitwellClient,
            aliceWalletClient,
            bobUserParty,
            BigDecimal(100.0),
            key,
          )
        }

        val aliceValidatorStartBalance = aliceValidatorWalletClient.balance()
        val providerStartBalance = splitwellWalletClient.balance()

        actAndCheck(
          "Advance rounds until reward coupons are issued",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Wait for all reward coupons",
          _ => {
            // App reward coupon to alice's validator for the first (locking) leg
            aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
            // App reward to splitwell provider for the second leg
            splitwellWalletClient.listAppRewardCoupons() should have length 1
            // One validator reward coupon per leg to alice's validator
            aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 2
          },
        )

        actAndCheck(
          "Advance rounds again to get rewards",
          Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
        )(
          "Earn rewards",
          _ => {
            aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
            splitwellWalletClient.listAppRewardCoupons() should be(empty)
            aliceValidatorWalletClient.listValidatorRewardCoupons() should be(empty)
            checkBalance(
              aliceValidatorWalletClient,
              Some(aliceValidatorStartBalance.round + 4),
              (
                aliceValidatorStartBalance.unlockedQty,
                aliceValidatorStartBalance.unlockedQty + 5.0,
              ),
              (0, 0),
              (0, 1),
            )
            checkBalance(
              splitwellWalletClient,
              Some(providerStartBalance.round + 4),
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
        splitwellWalletClient.cancelFeaturedAppRight(),
      )(
        "Splitwell is no longer featured",
        _ => sv1ScanBackend.lookupFeaturedAppRight(splitwellProviderParty) should be(None),
      )

      transferAndCheckRewards((0.5, 0.6))
    }

    "generate rewards for subscriptions" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val dirParty = clue(
        "Getting directory party ID (will fail if another test stopped the directory and failed before starting it again)"
      ) {
        directoryBackend.getProviderPartyId()
      }

      clue(
        "Advance seven rounds to ensure that rewards from previous test cases were claimed or expired"
      ) {
        Range(1, 8).foreach(_ => advanceRoundsByOneTick)
      }

      clue("Request install and wait for provider to auto-accept") {
        aliceDirectoryClient.requestDirectoryInstall()
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }

      val (_, subReqId) = clue("Alice requests a directory entry") {
        aliceDirectoryClient.requestDirectoryEntry(
          testEntryName,
          testEntryUrl,
          testEntryDescription,
        )
      }
      bracket(
        clue("Alice obtains some coins and accepts the subscription") {
          aliceWalletClient.tap(50.0)
          aliceWalletClient.acceptSubscriptionRequest(subReqId)
        },
        cancelAllSubscriptions(aliceWalletClient, aliceValidatorBackend),
      ) {
        clue("Getting Alice's new entry") {
          eventuallySucceeds() {
            directoryBackend.lookupEntryByName(testEntryName)
          }
        }

        clue("Wait for reward coupons to be issued") {
          eventually()({
            aliceValidatorWalletClient.listAppRewardCoupons() should have length 1
            aliceValidatorWalletClient.listValidatorRewardCoupons() should have length 2
            directoryBackend.participantClient.ledger_api_extensions.acs
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
            aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
            aliceValidatorWalletClient.listValidatorRewardCoupons() should be(empty)
            directoryBackend.participantClient.ledger_api_extensions.acs
              .filterJava(coinCodegen.AppRewardCoupon.COMPANION)(
                dirParty,
                _.data.provider == dirParty.toProtoPrimitive,
              ) should be(empty)
          },
        )

        actAndCheck(
          "Advance time until directory entry is up for renewal", {
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

      clue("Tap to get some coins") {
        aliceWalletClient.tap(500.0)
      }

      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceValidatorBackend, aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
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
}
