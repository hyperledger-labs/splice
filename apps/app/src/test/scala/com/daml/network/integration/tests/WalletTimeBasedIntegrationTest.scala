package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import java.util.UUID

class WalletTimeBasedIntegrationTest
    extends CoinIntegrationTest // TODO(#2100): investigate whether we can make this a shared environment again
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

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
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        25,
        scan.getUnfeaturedAppTransferContext(),
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
      val startingBalance = aliceWallet.balance()
      val lockedQty = 25

      advanceRoundsByOneTick

      lockCoins(
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        lockedQty,
        scan.getUnfeaturedAppTransferContext(),
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
        val directoryDarPath = "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }
      aliceWallet.listSubscriptions() shouldBe empty

      clue("Creating 3 subscriptions, 10 days apart") {
        for ((name, i) <- List("alice1", "alice2", "alice3").map(perTestCaseName).zipWithIndex) {

          val (_, requestId) = actAndCheck(
            "Request directory entry", {
              aliceDirectory.requestDirectoryEntry(name)._1
            },
          )(
            "the corresponding subscription request is created",
            { _ =>
              inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) => r.contractId }
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
          advanceTime(Duration.ofDays(10))
        }
      }
      clue("Stopping directory backend so that payments aren't collected.") {
        directory.stop()
      }
      actAndCheck(
        "Wait for the time for a payment on the first subscription to arrive",
        advanceTime(Duration.ofDays(60)),
      )(
        "2 idle subscriptions and 1 payment are listed",
        _ => {
          val subs = aliceWallet.listSubscriptions()
          subs should have length 3
          subs
            .collect(_.state match {
              case s: GrpcWalletAppClient.SubscriptionIdleState => s
            }) should have length 2
          subs
            .collect(_.state match {
              case s: GrpcWalletAppClient.SubscriptionPayment => s
            }) should have length 1
        },
      )
    }

    "auto-expire payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck(
        "Create a payment request, which expires after 1 minute",
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
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

      val now = aliceValidator.remoteParticipant.ledger_api.time.get()
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
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        0.000005,
        scan.getUnfeaturedAppTransferContext(),
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
      aliceValidatorWallet.selfGrantFeaturedAppRight()
      aliceWallet.tap(20.0)

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      actAndCheck(
        "Alice creates a self-payment request",
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
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
          .map(req => aliceWallet.acceptAppPaymentRequest(req.contractId)),
      )("Request no longer exists", _ => aliceWallet.listAppPaymentRequests() should have length 0)

      actAndCheck(
        "Advance rounds until reward coupons are issued",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for reward coupons",
        _ => {
          aliceValidatorWallet
            .listAppRewardCoupons() should have length 1 // Award for the first (locking) leg goes to the sender's validator
          // TODO(#2100) In this test, we don't yet collect the app payment, so the provider (alice's wallet) doesn't currently get a reward
        },
      )

      inside(aliceValidatorWallet.listAppRewardCoupons()) { case Seq(c) =>
        c.payload.featured shouldBe true
      }
    }

  }
}
