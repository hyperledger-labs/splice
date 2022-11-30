package com.daml.network.integration.tests

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.scripts.wallet.testsubscriptions as testSubsCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{WalletAppBackendReference, WalletAppClientReference}
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{CoinTestUtil, Proto}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.JavaDecodeUtil as DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import org.slf4j.event.Level

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class WalletIntegrationTest extends CoinIntegrationTest with HasExecutionContext with CoinTestUtil {

  "A wallet" should {

    "restart cleanly" in { implicit env =>
      aliceWalletBackend.stop()
      aliceWalletBackend.startSync()
    }

    "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

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

        checkBalance(aliceWallet, 0, exactly(110), exactly(0), exactly(0))

        nextRound(0)

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
          1,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        nextRound(1)

        checkBalance(
          aliceWallet,
          2,
          (99, 100),
          (9, 10),
          (0.00001, 0.00002),
        )
    }

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

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

        aliceWallet.list().coins.head.round shouldBe 0
        aliceWallet.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe 0
        aliceWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
      }

      nextRound(0)

      clue("Check wallet after advancing to next round") {
        eventually()(aliceWallet.list().coins.head.round shouldBe 1)
        assertInRange(aliceWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
        assertInRange(aliceWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceWallet.list().lockedCoins.head.round shouldBe 1
        assertInRange(
          aliceWallet.list().lockedCoins.head.accruedHoldingFee,
          (0.000004, 0.000005),
        )
        assertInRange(aliceWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
      }
    }

    "allow a user to list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      val (_, reqC) =
        createSelfPaymentRequest(this, aliceWalletBackend.remoteParticipant, aliceUserParty)

      val reqFound = clue("Check that we can see the created payment request") {
        val reqFound = eventually() {
          aliceWallet.listAppPaymentRequests().headOption.value
        }
        reqFound.payload shouldBe reqC
        reqFound
      }

      clue("Reject the payment request") {
        aliceWallet.rejectAppPaymentRequest(reqFound.contractId)
      }

      clue("Check that there are no more payment requests") {
        val requests2 = aliceWallet.listAppPaymentRequests()
        requests2 shouldBe empty
      }
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      val (referenceId, reqC) =
        createSelfPaymentRequest(this, aliceWalletBackend.remoteParticipant, aliceUserParty)

      val cid = eventually() {
        inside(aliceWallet.listAppPaymentRequests()) { case Seq(r) =>
          r.payload shouldBe reqC
          r.contractId
        }
      }

      clue("Tap 50 coins") {
        aliceWallet.tap(50)
        eventually() { aliceWallet.list().coins should not be empty }
      }

      clue("Accept payment request") {
        val acceptedPaymentId = aliceWallet.acceptAppPaymentRequest(cid)
        aliceWallet.listAppPaymentRequests() shouldBe empty
        inside(aliceWallet.listAcceptedAppPayments()) { case Seq(r) =>
          r.contractId shouldBe acceptedPaymentId
          r.payload shouldBe new walletCodegen.AcceptedAppPayment(
            aliceUserParty.toProtoPrimitive,
            Seq(
              new walletCodegen.ReceiverCCQuantity(
                aliceUserParty.toProtoPrimitive,
                BigDecimal(10).bigDecimal.setScale(10),
              )
            ).asJava,
            aliceUserParty.toProtoPrimitive,
            svcParty.toProtoPrimitive,
            r.payload.lockedCoin,
            referenceId.toInterface(walletCodegen.DeliveryOffer.INTERFACE),
          )
        }
      }
    }

    "correctly select coins for payments" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)

      clue("Alice opens payment channel to Bob") {
        val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
        eventually()(bobWallet.listPaymentChannelProposals() should have size 1)
        bobWallet.acceptPaymentChannelProposal(proposalId)
        eventually()(bobWallet.listPaymentChannelProposals() shouldBe empty)
      }

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceWallet.tap(10)
        aliceWallet.tap(40)
        aliceWallet.tap(20)
        checkWallet(aliceUserParty, aliceWallet, Seq((10, 10), (20, 20), (40, 40)))
      }

      clue("Alice transfers 39") {
        aliceWallet.executeDirectTransfer(bobUserParty, 39)
        checkWallet(aliceUserParty, aliceWallet, Seq((30, 31)))
      }
      clue("Alice transfers 19") {
        aliceWallet.executeDirectTransfer(bobUserParty, 19)
        checkWallet(aliceUserParty, aliceWallet, Seq((11, 12)))
      }
    }

    "allow a user to list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      aliceWallet.listSubscriptionRequests() shouldBe empty

      val request = createSelfSubscriptionRequest(aliceUserParty);

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
      }
      clue("Reject the subscription request") {
        aliceWallet.rejectSubscriptionRequest(requestId)
        aliceWallet.listSubscriptionRequests() shouldBe empty
      }
    }

    // We put all of this in one test because assembling valid subscription instances
    // is cumbersome and it's easier to just reuse the results of the "accept" flow.
    "allow a user to list and accept subscription requests, " +
      "to list idle subscriptions, to initiate subscription payments, " +
      "and to cancel a subscription" in { implicit env =>
        val transferContext = scan.getAppTransferContext()
        val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceWallet.listSubscriptionRequests() shouldBe empty
        aliceWallet.listSubscriptions() shouldBe empty

        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(aliceUserParty),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceWallet.listSubscriptionRequests()) { case Seq(r) =>
              r.payload shouldBe request
              r.contractId
            },
        )
        clue("Alice gets some coins") {
          aliceWallet.tap(50)
        }

        val (initialPaymentId, _) = actAndCheck(
          "Accept the subscription request, which initiates the first subscription payment",
          aliceWallet.acceptSubscriptionRequest(requestId),
        )(
          "initial subscription payment is listed correctly",
          initialPaymentId => {
            aliceWallet.listSubscriptionRequests() shouldBe empty
            inside(aliceWallet.listSubscriptionInitialPayments()) { case Seq(r) =>
              r.contractId shouldBe initialPaymentId
              r.payload.subscriptionData should equal(request.subscriptionData)
              r.payload.payData should equal(request.payData)
            }
          },
        )

        val (_, paymentId) = actAndCheck(
          "Collect the initial payment (as the receiver), which creates the subscription", {
            val collectCommand = initialPaymentId
              .exerciseSubscriptionInitialPayment_Collect(transferContext)
              .commands
              .asScala
              .toSeq
            aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceValidatorParty),
              optTimeout = None,
              commands = collectCommand,
            )
          },
        )(
          // note that because the directory sets paymentDuration = paymentInterval,
          // the wallet backend can make the second payment immediately
          "an automated subscription payment is eventually initiated by the wallet",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case GrpcWalletAppClient.SubscriptionPayment(state) =>
                state.payload.subscription shouldBe sub.main.contractId
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        val (_, subscriptionStateId2) = actAndCheck(
          "Collect the second payment (as the receiver), which sets the subscription back to idle", {
            val collectCommand2 = paymentId
              .exerciseSubscriptionPayment_Collect(transferContext)
              .commands
              .asScala
              .toSeq
            aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceValidatorParty),
              optTimeout = None,
              commands = collectCommand2,
            )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceWallet.listSubscriptions()) { case Seq(sub) =>
              sub.main.payload should equal(request.subscriptionData)
              inside(sub.state) { case GrpcWalletAppClient.SubscriptionIdleState(state) =>
                state.payload.subscription should equal(sub.main.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            },
        )

        actAndCheck(
          "Cancel the subscription",
          aliceWallet.cancelSubscription(subscriptionStateId2),
        )("no more subscriptions exist", _ => aliceWallet.listSubscriptions() shouldBe empty)
      }

    "allow two users to make direct transfers between them" in { implicit env =>
      // val aliceUserParty =
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)
      aliceWallet.tap(100.0)

      val expiration = Primitive.Timestamp
        .discardNanos(Instant.now().plus(1, ChronoUnit.MINUTES))
        .getOrElse(fail("Failed to convert timestamp"))

      val offer =
        aliceWallet.createTransferOffer(bobUserParty, 1.0, "direct transfer test", expiration)
      val offer2 =
        aliceWallet.createTransferOffer(bobUserParty, 2.0, "to be rejected", expiration)
      val offer3 =
        aliceWallet.createTransferOffer(bobUserParty, 3.0, "to be withdrawn", expiration)

      eventually() {
        aliceWallet.listTransferOffers().length shouldBe 3
        bobWallet.listTransferOffers().length shouldBe 3
      }

      actAndCheck("Bob accepts one offer", bobWallet.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceWallet.listTransferOffers().length shouldBe 2
          aliceWallet.listAcceptedTransferOffers().length shouldBe 0
          bobWallet.listTransferOffers().length shouldBe 2
          bobWallet.listAcceptedTransferOffers().length shouldBe 0
          checkWallet(aliceUserParty, aliceWallet, Seq((98.8, 99.0)))
          checkWallet(bobUserParty, bobWallet, Seq((1.0, 1.0)))
        },
      )

      actAndCheck(
        "Bob rejects one offer, alice withdraws the other", {
          bobWallet.rejectTransferOffer(offer2)
          aliceWallet.withdrawTransferOffer(offer3)
        },
      )(
        "No more offers listed",
        _ => {
          aliceWallet.listTransferOffers().length shouldBe 0
          aliceWallet.listAcceptedTransferOffers().length shouldBe 0
          bobWallet.listTransferOffers().length shouldBe 0
          bobWallet.listAcceptedTransferOffers().length shouldBe 0
        },
      )

      // TODO(#1731): Once expiration is automated, add a test here that creates an offer with a short expiration period and waits for it to be auto-expired

      val offer5 =
        aliceWallet.createTransferOffer(
          bobUserParty,
          quantity = 150.0,
          description = "not rich enough yet",
          expiration,
        )
      eventually() {
        bobWallet.acceptTransferOffer(offer5)
      }
      clue("Sleeping for a while, to make sure a few retries fail first")(
        // TODO(M3-02): consider making this a time-based test, and advancing time gradually to trigger the failing automation instead
        Threading.sleep(4000)
      )
      clue("Tapping more coin, to afford the accepted transfer offer")(
        aliceWallet.tap(200.0)
      )
      clue("Checking final balances")(
        eventually() {
          checkWallet(aliceUserParty, aliceWallet, Seq((147.5, 148.0)))
          checkWallet(bobUserParty, bobWallet, Seq((1.0, 1.0), (150.0, 150.0)))
        }
      )
    }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)

      // Neither Alice nor Bob see a payment channel proposal
      aliceWallet.listPaymentChannelProposals() shouldBe empty
      bobWallet.listPaymentChannelProposals() shouldBe empty

      // Neither Alice nor Bob see any payment channels
      aliceWallet.listPaymentChannels() shouldBe empty
      bobWallet.listPaymentChannels() shouldBe empty

      // Alice proposes payment channel to Bob
      val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
      val aliceProposal = eventually() {
        inside(aliceWallet.listPaymentChannelProposals()) { case Seq(proposal) =>
          proposal
        }
      }

      // Alice and Bob still don't see the payment channel yet
      aliceWallet.listPaymentChannels() shouldBe empty
      bobWallet.listPaymentChannels() shouldBe empty

      val aliceChannel = aliceProposal.payload.channel
      aliceProposal.contractId shouldBe proposalId
      aliceChannel.sender shouldBe aliceUserParty.toPrim
      aliceChannel.receiver shouldBe bobUserParty.toPrim

      // Bob monitors proposals and accepts the one
      val bobProposal = eventually() {
        inside(bobWallet.listPaymentChannelProposals()) { case Seq(proposal) =>
          proposal
        }
      }
      aliceProposal shouldBe bobProposal
      bobWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      eventually()(aliceWallet.listPaymentChannelProposals() shouldBe empty)

      // Neither Alice nor Bob see a payment channel proposal
      aliceWallet.listPaymentChannelProposals() shouldBe empty
      bobWallet.listPaymentChannelProposals() shouldBe empty

      // But both see the established channel now
      eventually()(aliceWallet.listPaymentChannels() should have size 1)
      aliceWallet.listPaymentChannels() shouldBe bobWallet.listPaymentChannels()

      // Alice taps and does a direct transfer to Bob
      aliceWallet.tap(50)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))
      aliceWallet.executeDirectTransfer(bobUserParty, 10)
      bobWalletBackend.remoteParticipant.ledger_api.acs
        .awaitJava(coinCodegen.Coin.COMPANION)(bobUserParty)
      checkWallet(aliceUserParty, aliceWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10)))

      // Bob asks for more coins, alice accepts
      aliceWallet.listOnChannelPaymentRequests().size shouldBe 0
      val request = bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      eventually()(aliceWallet.listOnChannelPaymentRequests() should have size 1)
      aliceWallet.listOnChannelPaymentRequests().headOption.value.contractId shouldBe request
      bobWallet.listOnChannelPaymentRequests() shouldBe aliceWallet
        .listOnChannelPaymentRequests()
      aliceWallet.acceptOnChannelPaymentRequest(request)
      eventually()(
        bobWalletBackend.remoteParticipant.ledger_api.acs
          .filterJava(coinCodegen.Coin.COMPANION)(bobUserParty)
          should have size 2
      )
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, alice rejects
      val request1 =
        bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please reject")
      eventually()(aliceWallet.listOnChannelPaymentRequests() should have size 1)
      aliceWallet.rejectOnChannelPaymentRequest(request1)
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, then withdraws
      val request2 =
        bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "will withdraw")
      bobWallet.withdrawOnChannelPaymentRequest(request2)
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      aliceWallet.proposePaymentChannel(
        bobUserParty,
        Some(aliceWallet.listPaymentChannels().head.contractId),
        allowDirectTransfers = false,
      )
      eventually()(bobWallet.listPaymentChannelProposals() should have size 1)
      val updatedProposal = bobWallet.acceptPaymentChannelProposal(
        bobWallet.listPaymentChannelProposals().head.contractId
      )
      eventually()(
        aliceWallet.listPaymentChannels().map(_.contractId) should contain(updatedProposal)
      )
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        aliceWallet
          .executeDirectTransfer(bobUserParty, 10),
        _.errorMessage should include regex ("Daml exception.*Direct transfers are allowed"),
      )
    }

    "allow two wallet app users to connect to one wallet backend and tap" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)

      aliceWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))

      val charlieDamlUser = charlieWallet.config.damlUser
      val charlieUserParty = onboardWalletUser(this, charlieWallet, aliceValidator)

      charlieWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieWallet, Seq((50, 50)))

      aliceWalletBackend.setWalletContext(charlieDamlUser)
      checkWallet(charlieUserParty, aliceWalletBackend, Seq((50, 50)))
    }

    "(propose, accept, and) cancel a payment channel by sender" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)

      val (_, aliceProposal) = actAndCheck(
        "Alice proposes payment channel to Bob",
        aliceWallet.proposePaymentChannel(bobUserParty),
      )(
        "payment channel proposal is listed for both Alice and Bob",
        _ => {
          val aliceProposal = inside(aliceWallet.listPaymentChannelProposals()) { case Seq(p) =>
            p
          }
          inside(bobWallet.listPaymentChannelProposals()) { case Seq(p) =>
            p shouldBe aliceProposal
          }
          aliceProposal
        },
      )
      clue("Bob accepts the proposal") {
        bobWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      }
      actAndCheck(
        "Bob requests a payment, and then immediately cancels the channel", {
          bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
          bobWallet.cancelPaymentChannelBySender(aliceUserParty)
        },
      )(
        "neither Alice nor Bob see neither the payment channel nor the payment request anymore",
        _ => {
          bobWallet.listPaymentChannels() shouldBe empty
          bobWallet.listOnChannelPaymentRequests() should not be empty
          eventually()(aliceWallet.listOnChannelPaymentRequests() should not be empty)
          eventually()(aliceWallet.listPaymentChannels() shouldBe empty)
        },
      )
    }

    "(propose, accept, and) cancel a payment channel by receiver" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobWallet, bobValidator)

      // Alice proposes payment channel to Bob
      aliceWallet.proposePaymentChannel(bobUserParty)
      // wallet store sometimes takes a bit to see the proposal
      eventually()(aliceWallet.listPaymentChannelProposals() should have size 1)
      val aliceProposal = aliceWallet.listPaymentChannelProposals()(0)

      // Bob monitors proposals and accepts the one
      eventually()(bobWallet.listPaymentChannelProposals() should have size 1)
      bobWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob requests a payment, and then immediately cancels the channel
      bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      aliceWallet.cancelPaymentChannelByReceiver(bobUserParty)

      // Neither sees the payment channel nor the payment request anymore
      aliceWallet.listPaymentChannels() shouldBe empty
      aliceWallet.listOnChannelPaymentRequests() should not be empty
      eventually()(bobWallet.listOnChannelPaymentRequests() should not be empty)
      eventually()(bobWallet.listPaymentChannels() shouldBe empty)
    }

    "concurrent coin-operations" should {
      "be batched" in { implicit env =>
        val (alice, bob) = setupAliceAndBobAndChannel(this)
        aliceWallet.tap(50)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        (1 to 3).foreach(i => Future(aliceWallet.executeDirectTransfer(bob, i)).discard)
        // Wait until 2 transactions have been received
        val txs = aliceValidator.remoteParticipant.ledger_api.transactions
          .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))

        // create change + transferred coin
        createdCoinsInTx(0) should have size 2
        // (create change + transferred coin) x2
        createdCoinsInTx(1) should have size 2 * 2
      }

      "be batched up to `batchSize` concurrent coin-operations" in { implicit env =>
        val defaultBatchSize = 10
        val (alice, bob) = setupAliceAndBobAndChannel(this)
        aliceWallet.tap(50)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

        val _ = Future(aliceWallet.executeDirectTransfer(bob, 10))
        (1 to defaultBatchSize + 1).foreach(_ =>
          Future(aliceWallet.executeDirectTransfer(bob, 1)).discard
        )
        // 3 txs;
        // tx 1: initial transfer
        // tx 2: 10 subsequent batched transfers
        // tx 3: single transfer that was not picked due to the batch size limit
        val txs = aliceValidator.remoteParticipant.ledger_api.transactions
          .treesJava(Set(alice), completeAfter = 3, beginOffset = offsetBefore)
        val createdCoinsInTx =
          txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))

        // create change + transferred coin
        createdCoinsInTx(0) should have size 2
        // (create change + transferred coin) x10
        createdCoinsInTx(1) should have size 10 * 2
        // create change + transferred coin
        createdCoinsInTx(2) should have size 2
      }

      "fail operations early and independently that don't pass the activeness lookup checks" in {
        implicit env =>
          val alice = onboardWalletUser(this, aliceWallet, aliceValidator)
          val bob = onboardWalletUser(this, bobWallet, bobValidator)

          // tapping some coin & waiting for it to appear as a way to synchronize on the initialization of the apps.
          aliceWallet.tap(10)
          aliceValidator.remoteParticipant.ledger_api.acs
            .awaitJava(coinCodegen.Coin.COMPANION)(alice)
          // ... such that we don't grab the ledger offset when some init txs are still occurring
          val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

          // solo tap will kick off batch with only one coin operation
          Future(aliceWallet.tap(10)).discard

          // following three commands will be in one batch
          Future(aliceWallet.tap(10)).discard
          // fails because we don't have a channel - so removed from batch & error is reported back
          Future(
            loggerFactory.assertThrowsAndLogs[CommandFailure](
              aliceWallet
                .executeDirectTransfer(bob, 10),
              _.errorMessage should include regex ("NOT_FOUND/Payment channel between"),
            )
          ).discard
          Future(aliceWallet.tap(10)).discard

          eventually() {
            val coins = aliceWallet.list().coins
            // all four taps went through
            coins should have size 4
            // but no money was deducted due to the transfer
            checkWallet(alice, aliceWallet, Seq((9, 10), (9, 10), (9, 10), (9, 10)))
          }
          eventually() {
            val txs = aliceValidator.remoteParticipant.ledger_api.transactions
              .treesJava(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
            val createdCoinsInTx =
              txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin.COMPANION)(_))
            createdCoinsInTx(0) should have size 1
            // two taps went through, even though transfer in same batch failed.
            createdCoinsInTx(1) should have size 2
          }
      }

      "retry a batch if it fails due to contention" in { implicit env =>
        val (alice, bob) = setupAliceAndBobAndChannel(this)
        aliceWallet.tap(10)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)

        val cancelF = Future(bobWallet.cancelPaymentChannelBySender(alice))

        val transferF = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          // to simulate contention, submit a transfer on the channel immediately after cancelling the channel such
          // that the activeness lookup on the channel still goes through but the lookup inside the ledger fails.
          // this leads to a retry..
          Future(aliceWallet.executeDirectTransfer(bob, 5))
            // that fails on the second execution
            .recover { case _: CommandFailure =>
              // need to recover as logs are only checked for successful futures
              ()
            },
          entries => {
            forAtLeast(1, entries)( // however, before failing, we see one retry in the logs
              _.message should include(
                "The operation 'execute coin operation batch' has failed with an exception. Retrying after 200 milliseconds."
              )
            )
            forAtLeast(1, entries)( // fails
              _.message should include(
                "GrpcRequestRefusedByServer: NOT_FOUND/PaymentChannel between"
              )
            )
          },
        )

        // eventually, the cancel goes through
        eventually()((cancelF.isCompleted) shouldBe true)
        eventually()(aliceWallet.listPaymentChannels() should have size 1)
        eventually()(bobWallet.listPaymentChannels() should have size 1)

        // such that on the retry, the transfer fails on the activeness check.
        eventually()(transferF.isCompleted shouldBe true)
        checkWallet(alice, aliceWallet, Seq((10, 10)))

      }
    }

    "rejects HS256 JWTs with invalid signatures" in { implicit env =>
      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm
      import com.daml.network.wallet.v0
      import com.daml.network.auth.{JwtCallCredential}
      import io.grpc.ManagedChannelBuilder

      val aliceDamlUser = aliceWallet.config.damlUser
      val token = JWT.create().withSubject(aliceDamlUser).sign(Algorithm.HMAC256("wrong-secret"))

      // using grpc client directly rather than console refs, as token handling isn't threaded through to console command layer (yet)
      val channel =
        ManagedChannelBuilder
          .forAddress("localhost", aliceWalletBackend.config.adminApi.port.unwrap)
          .usePlaintext()
          .build()

      val client =
        v0.WalletServiceGrpc
          .blockingStub(channel)
          .withCallCredentials(new JwtCallCredential(token))

      val error = intercept[io.grpc.StatusRuntimeException] {
        client.tap(new v0.TapRequest("10.0"))
      }

      assert(
        error.getStatus.getCode.value == com.google.rpc.Code.UNAUTHENTICATED.getNumber
      )

      channel.shutdown() // to avoid error about improperly shut down channel
    }

    def checkBalance(
        wallet: WalletAppClientReference,
        expectedRound: Long,
        expectedUQRange: (BigDecimal, BigDecimal),
        expectedLQRange: (BigDecimal, BigDecimal),
        expectedHRange: (BigDecimal, BigDecimal),
    ): Unit = clue(s"Checking balance in round $expectedRound") {
      eventually() {
        val balance = wallet.balance()
        balance.round shouldBe expectedRound
        assertInRange(balance.unlockedQty, expectedUQRange)
        assertInRange(balance.lockedQty, expectedLQRange)
        assertInRange(balance.holdingFees, expectedHRange)
      }
    }

    def lockCoins(
        userWallet: WalletAppBackendReference,
        userParty: PartyId,
        validatorParty: PartyId,
        coins: Seq[GrpcWalletAppClient.CoinPosition],
        quantity: Int,
        transferContext: v1.coin.AppTransferContext,
    ): Unit = clue(s"Locking $quantity coins for $userParty") {
      val coinOpt = coins.find(_.effectiveQuantity >= quantity)
      val expirationOpt = Proto.decode(Proto.Timestamp)(20000000000000000L) // Wed May 18 2033

      (coinOpt, expirationOpt) match {
        case (Some(coin), Right(expiration)) => {
          userWallet.remoteParticipant.ledger_api.commands.submitJava(
            Seq(userParty, validatorParty),
            optTimeout = None,
            commands = transferContext.coinRules
              .exerciseCoinRules_Transfer(
                new v1.coin.Transfer(
                  userParty.toProtoPrimitive,
                  userParty.toProtoPrimitive,
                  Seq[v1.coin.TransferInput](
                    new v1.coin.transferinput.InputCoin(
                      coin.contract.contractId.toInterface(v1.coin.Coin.INTERFACE)
                    )
                  ).asJava,
                  Seq[v1.coin.TransferOutput](
                    new v1.coin.transferoutput.OutputSenderCoin(
                      Some(BigDecimal(quantity).bigDecimal).toJava,
                      Some(
                        new v1.coin.TimeLock(
                          Seq(userParty.toProtoPrimitive).asJava,
                          expiration,
                        )
                      ).toJava,
                    ),
                    new v1.coin.transferoutput.OutputSenderCoin(
                      None.toJava,
                      None.toJava,
                    ),
                  ).asJava,
                  "lock coins",
                ),
                new v1.coin.TransferContext(
                  transferContext.openMiningRound,
                  Map.empty[v1.round.Round, v1.round.IssuingMiningRound.ContractId].asJava,
                  Map.empty[String, v1.coin.ValidatorRight.ContractId].asJava,
                ),
              )
              .commands
              .asScala
              .toSeq,
          )
        }
        case _ => {
          coinOpt shouldBe a[Some[_]]
          expirationOpt shouldBe a[Right[_, _]]
        }
      }
    }

    def nextRound(i: Long)(implicit env: CoinTestConsoleEnvironment): Unit = {
      clue(s"Advancing to round ${i + 1}") {
        svc.startSummarizingRound(i)
        eventually() {
          svc.remoteParticipant.ledger_api.acs
            .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
        }
        svc.closeRound(i)
        svc.openRound(i + 1, 1)
      }
    }

    def createSelfSubscriptionRequest(aliceUserParty: PartyId)(implicit
        env: CoinTestConsoleEnvironment
    ): subsCodegen.SubscriptionRequest = {
      val contextId = clue("Create a subscription context") {
        aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
          Seq(aliceUserParty),
          optTimeout = None,
          commands = new testSubsCodegen.TestSubscriptionContext(
            scan.getSvcPartyId().toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            "description",
          ).create.commands.asScala.toSeq,
        )
        aliceWalletBackend.remoteParticipant.ledger_api.acs
          .awaitJava(testSubsCodegen.TestSubscriptionContext.COMPANION)(aliceUserParty)
          .id
      }
      clue("Create a subscription request to self") {
        val subscriptionData = new subsCodegen.Subscription(
          aliceUserParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          aliceUserParty.toProtoPrimitive,
          svcParty.toProtoPrimitive,
          contextId.toInterface(subsCodegen.SubscriptionContext.INTERFACE),
        )
        val payData = new subsCodegen.SubscriptionPayData(
          BigDecimal(10).bigDecimal.setScale(10),
          new RelTime(60 * 60 * 1000000L),
          new RelTime(60 * 60 * 1000000L),
          new RelTime(60 * 1000000L),
        ) // paymentDuration == paymenInterval, so we can make a second payment immediately,
        // without having to mess with time
        val request = new subsCodegen.SubscriptionRequest(
          subscriptionData,
          payData,
        )
        aliceWalletBackend.remoteParticipant.ledger_api.commands.submitJava(
          actAs = Seq(aliceUserParty),
          optTimeout = None,
          commands = request.create.commands.asScala.toSeq,
        )
        request
      }
    }
  }
}
