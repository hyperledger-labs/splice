package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.directory as dirCodegen
import com.daml.network.console.WalletAppClientReference
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import java.util.UUID

class WalletTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  private def p2pTransferAndTriggerAutomation(
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      senderTransferFeeRatio: BigDecimal = 1.0,
  )(implicit env: CoinTestConsoleEnvironment) = {
    p2pTransfer(senderWallet, receiverWallet, receiver, amount, senderTransferFeeRatio)
    eventually() {
      // wait until we observe the accepted transfer offer
      receiverWallet.listAcceptedTransferOffers() should have size 1
    }
    // ... before we advance time to trigger the automation.
    advanceTime(Duration.ofSeconds(1))
  }

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "A wallet" should {

    "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        val exactly = (x: BigDecimal) => (x, x)

        clue("Alice taps 50 coins") {
          val ranges1 = Seq(exactly(50))
          aliceWallet.tap(50)
          checkWallet(aliceUserParty, aliceWallet, ranges1)
          checkWallet(aliceUserParty, aliceWallet, ranges1)
        }

        clue("Alice taps 60 coins") {
          val ranges2 = Seq(exactly(50), exactly(60))
          aliceWallet.tap(60)
          checkWallet(aliceUserParty, aliceWallet, ranges2)
          checkWallet(aliceUserParty, aliceWallet, ranges2)
        }

        checkBalance(aliceWallet, 1, exactly(110), exactly(0), exactly(0))
        // leads to archival of open round 0
        advanceRoundsByOneTick

        lockCoins(
          aliceWalletBackend,
          aliceUserParty,
          aliceValidatorParty,
          aliceWallet.list().coins,
          10,
          scan.getAppTransferContext(),
        )

        checkBalance(
          aliceWallet,
          2,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        // leads to latest round being round 3
        advanceRoundsByOneTick

        checkBalance(
          aliceWallet,
          3,
          (99, 100),
          (9, 10),
          (0.00001, 0.00002),
        )
    }

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      clue("Alice taps 50 coins") {
        aliceWallet.tap(50)
        eventually() {
          aliceWallet.list().coins.length shouldBe 1
          aliceWallet.list().lockedCoins.length shouldBe 0
        }
      }

      lockCoins(
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        25,
        scan.getAppTransferContext(),
      )

      clue("Check wallet after locking coins") {
        aliceWallet.list().coins.length shouldBe 1
        eventually()(aliceWallet.list().lockedCoins should have length 1)

        aliceWallet.list().coins.head.round shouldBe 1
        // even though we are in round 1, we have 0 holding fees because the coins were also created in round 1
        aliceWallet.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe 1
        aliceWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
      }

      // advance to next round.
      advanceRoundsByOneTick

      clue("Check wallet after advancing to next round") {
        eventually()(aliceWallet.list().coins.head.round shouldBe 2)
        assertInRange(aliceWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
        assertInRange(aliceWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe 2
        assertInRange(
          aliceWallet.list().lockedCoins.head.accruedHoldingFee,
          (0.000004, 0.000005),
        )
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
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
        for ((name, i) <- List("alice1", "alice2", "alice3").zipWithIndex) {

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
          eventually() {
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
          }
        },
      )
    }

    "auto-expire payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      actAndCheck(
        "Create a payment request, which expires after 1 minute",
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.damlUser,
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
  }

  "automatically collect app & validator rewards on coin operations" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()

    aliceWallet.tap(50)
    aliceValidatorWallet.tap(50)
    eventually()(aliceWallet.list().coins should have size 1)

    // Execute a transfer in round 1 -> leads to rewards being generated
    p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bob, 40.0, 0.0)
    eventually()(aliceWallet.listAppRewards() should have size 1)
    eventually()(aliceValidatorWallet.listValidatorRewards() should have size 1)

    // advance by two ticks, so the issuing round of round 1 is created
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    // advance time such that issuing round 1 is open to rewards collection.
    advanceRoundsByOneTick

    // alice uses her reward
    p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bob, 1.0, 0.0)
    eventually()(
      aliceWallet.listAppRewards().filter(_.payload.round.number == 1) should have size 0
    )
    aliceValidatorWallet
      .listValidatorRewards()
      .filter(_.payload.round.number == 1) should have size 1
    // 2 validator rewards due to two transfers
    eventually()(
      aliceValidatorWallet
        .listValidatorRewards()
        .filter(_.payload.round.number == 1) should have size 1
    )

    // validator uses reward from round 1 too.
    p2pTransferAndTriggerAutomation(aliceValidatorWallet, aliceWallet, alice, 1.0)
    eventually()(
      aliceValidatorWallet
        .listValidatorRewards()
        .filter(_.payload.round.number == 1) should have size 0
    )
    // but still gains a reward for the new transfer in a different round
    eventually()(
      aliceValidatorWallet
        .listValidatorRewards()
        .filter(_.payload.round.number != 1) should have size 2
    )
    // doing additional transfers in this round, only leads to more rewards since only round 1 is open for rewards collections
    p2pTransferAndTriggerAutomation(aliceValidatorWallet, aliceWallet, alice, 1.0)
    eventually()(aliceValidatorWallet.listValidatorRewards() should have size 3)

  }

  "list and manually collect app & validator rewards" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()

    // Tap coin and do a transfer from alice to bob
    aliceWallet.tap(50)

    p2pTransferAndTriggerAutomation(aliceWallet, bobWallet, bob, 40.0, 0.0)

    // Retrieve transferred coin in bob's wallet and transfer part of it back to alice; bob will receive some app rewards
    eventually()(bobWallet.list().coins should have size 1)
    p2pTransferAndTriggerAutomation(bobWallet, aliceWallet, alice, 30.0, 0.0)

    eventually() {
      bobWallet.listAppRewards() should have size 1
    }
    bobWallet.listValidatorRewards() shouldBe empty

    // Wait for validator rewards to become visible in alice's wallet, check structure
    aliceValidatorWallet.listValidatorRewards() should have size 1

    // Bob collects/realizes rewards
    // it takes 3 ticks for IR 1 to be created and open.
    advanceRoundsByOneTick
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    val prevCoins = bobWallet.list().coins
    bobWallet.collectRewards(1)
    bobWallet.listAppRewards() should have size 0
    bobWallet.listValidatorRewards() should have size 0
    // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
    // for rewards.
    checkWallet(
      bob,
      bobWallet,
      prevCoins
        .map(c =>
          (
            BigDecimal(c.contract.payload.quantity.initialQuantity),
            BigDecimal(c.contract.payload.quantity.initialQuantity) + 2,
          )
        )
        .sortBy(_._1),
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

}
