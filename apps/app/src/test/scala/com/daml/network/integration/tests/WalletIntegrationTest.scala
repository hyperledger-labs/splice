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
import com.daml.network.console.{LocalWalletAppReference, RemoteWalletAppReference}
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
      aliceWallet.stop()
      aliceWallet.startSync()
    }

    "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        val exactly = (x: BigDecimal) => (x, x)

        clue("Alice taps 50 coins") {
          val ranges1 = Seq(exactly(50))
          aliceRemoteWallet.tap(50)
          checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)
          checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)
        }

        clue("Alice taps 60 coins") {
          val ranges2 = Seq(exactly(50), exactly(60))
          aliceRemoteWallet.tap(60)
          checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)
          checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)
        }

        checkBalance(aliceRemoteWallet, 0, exactly(110), exactly(0), exactly(0))

        nextRound(0)

        lockCoins(
          aliceWallet,
          aliceUserParty,
          aliceValidatorParty,
          aliceRemoteWallet.list().coins,
          10,
          scan.getAppTransferContext(),
        )

        checkBalance(
          aliceRemoteWallet,
          1,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        nextRound(1)

        checkBalance(
          aliceRemoteWallet,
          2,
          (99, 100),
          (9, 10),
          (0.00001, 0.00002),
        )
    }

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      clue("Alice taps 50 coins") {
        aliceRemoteWallet.tap(50)
        eventually() {
          aliceRemoteWallet.list().coins.length shouldBe 1
          aliceRemoteWallet.list().lockedCoins.length shouldBe 0
        }
      }

      lockCoins(
        aliceWallet,
        aliceUserParty,
        aliceValidatorParty,
        aliceRemoteWallet.list().coins,
        25,
        scan.getAppTransferContext(),
      )

      clue("Check wallet after locking coins") {
        aliceRemoteWallet.list().coins.length shouldBe 1
        eventually()(aliceRemoteWallet.list().lockedCoins should have length 1)

        aliceRemoteWallet.list().coins.head.round shouldBe 0
        aliceRemoteWallet.list().coins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceRemoteWallet.list().lockedCoins.head.round shouldBe 0
        aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
        assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
      }

      nextRound(0)

      clue("Check wallet after advancing to next round") {
        eventually()(aliceRemoteWallet.list().coins.head.round shouldBe 1)
        assertInRange(aliceRemoteWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
        assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

        aliceRemoteWallet.list().lockedCoins.head.round shouldBe 1
        assertInRange(
          aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee,
          (0.000004, 0.000005),
        )
        assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
      }
    }

    "allow a user to list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      clue("Check that no payment requests exist") {
        aliceRemoteWallet.listAppPaymentRequests() shouldBe empty
      }

      val (_, reqC) = createSelfPaymentRequest(this, aliceWallet.remoteParticipant, aliceUserParty)

      val reqFound = clue("Check that we can see the created payment request") {
        val reqFound = eventually() {
          aliceRemoteWallet.listAppPaymentRequests().headOption.value
        }
        reqFound.payload shouldBe reqC
        reqFound
      }

      clue("Reject the payment request") {
        aliceRemoteWallet.rejectAppPaymentRequest(reqFound.contractId)
      }

      clue("Check that there are no more payment requests") {
        val requests2 = aliceRemoteWallet.listAppPaymentRequests()
        requests2 shouldBe empty
      }
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      val (referenceId, reqC) =
        createSelfPaymentRequest(this, aliceWallet.remoteParticipant, aliceUserParty)

      val cid = eventually() {
        inside(aliceRemoteWallet.listAppPaymentRequests()) { case Seq(r) =>
          r.payload shouldBe reqC
          r.contractId
        }
      }

      clue("Tap 50 coins") {
        aliceRemoteWallet.tap(50)
        eventually() { aliceRemoteWallet.list().coins should not be empty }
      }

      clue("Accept payment request") {
        val acceptedPaymentId = aliceRemoteWallet.acceptAppPaymentRequest(cid)
        aliceRemoteWallet.listAppPaymentRequests() shouldBe empty
        inside(aliceRemoteWallet.listAcceptedAppPayments()) { case Seq(r) =>
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
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      val bobUserParty = onboardWalletUser(this, bobRemoteWallet, bobValidator)

      clue("Alice opens payment channel to Bob") {
        val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
        eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
        bobRemoteWallet.acceptPaymentChannelProposal(proposalId)
        eventually()(bobRemoteWallet.listPaymentChannelProposals() shouldBe empty)
      }

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceRemoteWallet.tap(10)
        aliceRemoteWallet.tap(40)
        aliceRemoteWallet.tap(20)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((10, 10), (20, 20), (40, 40)))
      }

      clue("Alice transfers 39") {
        aliceRemoteWallet.executeDirectTransfer(bobUserParty, 39)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((30, 31)))
      }
      clue("Alice transfers 19") {
        aliceRemoteWallet.executeDirectTransfer(bobUserParty, 19)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((11, 12)))
      }
    }

    "allow a user to list and reject subscription requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      aliceRemoteWallet.listSubscriptionRequests() shouldBe empty

      val request = createSelfSubscriptionRequest(aliceUserParty);

      val requestId = clue("List subscription requests to find out request ID") {
        eventually() {
          inside(aliceRemoteWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
      }
      clue("Reject the subscription request") {
        aliceRemoteWallet.rejectSubscriptionRequest(requestId)
        aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
      }
    }

    // We put all of this in one test because assembling valid subscription instances
    // is cumbersome and it's easier to just reuse the results of the "accept" flow.
    "allow a user to list and accept subscription requests, " +
      "to list idle subscriptions, to initiate subscription payments, " +
      "and to cancel a subscription" in { implicit env =>
        val transferContext = scan.getAppTransferContext()
        val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
        aliceRemoteWallet.listSubscriptions() shouldBe empty

        val (request, requestId) = actAndCheck(
          "Create self-subscription request",
          createSelfSubscriptionRequest(aliceUserParty),
        )(
          "the created subscription request is listed correctly",
          request =>
            inside(aliceRemoteWallet.listSubscriptionRequests()) { case Seq(r) =>
              r.payload shouldBe request
              r.contractId
            },
        )
        clue("Alice gets some coins") {
          aliceRemoteWallet.tap(50)
        }

        val (initialPaymentId, _) = actAndCheck(
          "Accept the subscription request, which initiates the first subscription payment",
          aliceRemoteWallet.acceptSubscriptionRequest(requestId),
        )(
          "initial subscription payment is listed correctly",
          initialPaymentId => {
            aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
            inside(aliceRemoteWallet.listSubscriptionInitialPayments()) { case Seq(r) =>
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
            aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
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
            inside(aliceRemoteWallet.listSubscriptions()) { case Seq(sub) =>
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
            aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceValidatorParty),
              optTimeout = None,
              commands = collectCommand2,
            )
          },
        )(
          "the subscription is back in idle state",
          _ =>
            inside(aliceRemoteWallet.listSubscriptions()) { case Seq(sub) =>
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
          aliceRemoteWallet.cancelSubscription(subscriptionStateId2),
        )("no more subscriptions exist", _ => aliceRemoteWallet.listSubscriptions() shouldBe empty)
      }

    "allow two users to make direct transfers between them" in { implicit env =>
      // val aliceUserParty =
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobRemoteWallet, bobValidator)
      aliceRemoteWallet.tap(100.0)

      val expiration = Primitive.Timestamp
        .discardNanos(Instant.now().plus(1, ChronoUnit.MINUTES))
        .getOrElse(fail("Failed to convert timestamp"))

      val shortExpiration = Primitive.Timestamp
        .discardNanos(Instant.now().plus(5, ChronoUnit.SECONDS))
        .getOrElse(fail("Failed to convert timestamp"))

      val offer =
        aliceRemoteWallet.createTransferOffer(bobUserParty, 1.0, "direct transfer test", expiration)
      val offer2 =
        aliceRemoteWallet.createTransferOffer(bobUserParty, 2.0, "to be rejected", expiration)
      val offer3 =
        aliceRemoteWallet.createTransferOffer(bobUserParty, 3.0, "to be withdrawn", expiration)

      eventually() {
        aliceRemoteWallet.listTransferOffers().length shouldBe 3
        bobRemoteWallet.listTransferOffers().length shouldBe 3
      }

      actAndCheck("Bob accepts one offer", bobRemoteWallet.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceRemoteWallet.listTransferOffers().length shouldBe 2
          aliceRemoteWallet.listAcceptedTransferOffers().length shouldBe 0
          bobRemoteWallet.listTransferOffers().length shouldBe 2
          bobRemoteWallet.listAcceptedTransferOffers().length shouldBe 0
          checkWallet(aliceUserParty, aliceRemoteWallet, Seq((98.8, 99.0)))
          checkWallet(bobUserParty, bobRemoteWallet, Seq((1.0, 1.0)))
        },
      )

      actAndCheck(
        "Bob rejects one offer, alice withdraws the other", {
          bobRemoteWallet.rejectTransferOffer(offer2)
          aliceRemoteWallet.withdrawTransferOffer(offer3)
        },
      )(
        "No more offers listed",
        _ => {
          aliceRemoteWallet.listTransferOffers().length shouldBe 0
          aliceRemoteWallet.listAcceptedTransferOffers().length shouldBe 0
          bobRemoteWallet.listTransferOffers().length shouldBe 0
          bobRemoteWallet.listAcceptedTransferOffers().length shouldBe 0
        },
      )

      val (offer4, _) = actAndCheck(
        "Alice offers another payment",
        aliceRemoteWallet.createTransferOffer(bobUserParty, 4.0, "to expire", shortExpiration),
      )("New offer is listed", _ => { aliceRemoteWallet.listTransferOffers().length shouldBe 1 })

      Threading.sleep(10000)
      // TODO(#1731): Once expiration is automated, replace this with waiting for the offer to disappear
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        bobRemoteWallet.acceptTransferOffer(offer4),
        a => a.errorMessage should include("The requirement 'Offer has not expired' was not met."),
      )

      val offer5 =
        aliceRemoteWallet.createTransferOffer(
          bobUserParty,
          quantity = 150.0,
          description = "not rich enough yet",
          expiration,
        )
      eventually() {
        bobRemoteWallet.acceptTransferOffer(offer5)
      }
      clue("Sleeping for a while, to make sure a few retries fail first")(
        // TODO(M3-02): consider making this a time-based test, and advancing time gradually to trigger the failing automation instead
        Threading.sleep(4000)
      )
      clue("Tapping more coin, to afford the accepted transfer offer")(
        aliceRemoteWallet.tap(200.0)
      )
      clue("Checking final balances")(
        eventually() {
          checkWallet(aliceUserParty, aliceRemoteWallet, Seq((147.5, 148.0)))
          checkWallet(bobUserParty, bobRemoteWallet, Seq((1.0, 1.0), (150.0, 150.0)))
        }
      )
    }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      val bobUserParty = onboardWalletUser(this, bobRemoteWallet, bobValidator)

      // Neither Alice nor Bob see a payment channel proposal
      aliceRemoteWallet.listPaymentChannelProposals() shouldBe empty
      bobRemoteWallet.listPaymentChannelProposals() shouldBe empty

      // Neither Alice nor Bob see any payment channels
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listPaymentChannels() shouldBe empty

      // Alice proposes payment channel to Bob
      val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      val aliceProposal = eventually() {
        inside(aliceRemoteWallet.listPaymentChannelProposals()) { case Seq(proposal) =>
          proposal
        }
      }

      // Alice and Bob still don't see the payment channel yet
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listPaymentChannels() shouldBe empty

      val aliceChannel = aliceProposal.payload.channel
      aliceProposal.contractId shouldBe proposalId
      aliceChannel.sender shouldBe aliceUserParty.toPrim
      aliceChannel.receiver shouldBe bobUserParty.toPrim

      // Bob monitors proposals and accepts the one
      val bobProposal = eventually() {
        inside(bobRemoteWallet.listPaymentChannelProposals()) { case Seq(proposal) =>
          proposal
        }
      }
      aliceProposal shouldBe bobProposal
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      eventually()(aliceRemoteWallet.listPaymentChannelProposals() shouldBe empty)

      // Neither Alice nor Bob see a payment channel proposal
      aliceRemoteWallet.listPaymentChannelProposals() shouldBe empty
      bobRemoteWallet.listPaymentChannelProposals() shouldBe empty

      // But both see the established channel now
      eventually()(aliceRemoteWallet.listPaymentChannels() should have size 1)
      aliceRemoteWallet.listPaymentChannels() shouldBe bobRemoteWallet.listPaymentChannels()

      // Alice taps and does a direct transfer to Bob
      aliceRemoteWallet.tap(50)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((50, 50)))
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 10)
      bobWallet.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(bobUserParty)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10)))

      // Bob asks for more coins, alice accepts
      aliceRemoteWallet.listOnChannelPaymentRequests().size shouldBe 0
      val request = bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      eventually()(aliceRemoteWallet.listOnChannelPaymentRequests() should have size 1)
      aliceRemoteWallet.listOnChannelPaymentRequests().headOption.value.contractId shouldBe request
      bobRemoteWallet.listOnChannelPaymentRequests() shouldBe aliceRemoteWallet
        .listOnChannelPaymentRequests()
      aliceRemoteWallet.acceptOnChannelPaymentRequest(request)
      eventually()(
        bobWallet.remoteParticipant.ledger_api.acs
          .filterJava(coinCodegen.Coin.COMPANION)(bobUserParty)
          should have size 2
      )
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, alice rejects
      val request1 =
        bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please reject")
      eventually()(aliceRemoteWallet.listOnChannelPaymentRequests() should have size 1)
      aliceRemoteWallet.rejectOnChannelPaymentRequest(request1)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, then withdraws
      val request2 =
        bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "will withdraw")
      bobRemoteWallet.withdrawOnChannelPaymentRequest(request2)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      aliceRemoteWallet.proposePaymentChannel(
        bobUserParty,
        Some(aliceRemoteWallet.listPaymentChannels().head.contractId),
        allowDirectTransfers = false,
      )
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      val updatedProposal = bobRemoteWallet.acceptPaymentChannelProposal(
        bobRemoteWallet.listPaymentChannelProposals().head.contractId
      )
      eventually()(
        aliceRemoteWallet.listPaymentChannels().map(_.contractId) should contain(updatedProposal)
      )
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        aliceRemoteWallet
          .executeDirectTransfer(bobUserParty, 10),
        _.errorMessage should include regex ("Daml exception.*Direct transfers are allowed"),
      )
    }

    "allow two remote wallets to connect to one local wallet and tap" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)

      aliceRemoteWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((50, 50)))

      val charlieDamlUser = charlieRemoteWallet.config.damlUser
      val charlieUserParty = onboardWalletUser(this, charlieRemoteWallet, aliceValidator)

      charlieRemoteWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieRemoteWallet, Seq((50, 50)))

      aliceWallet.setWalletContext(charlieDamlUser)
      checkWallet(charlieUserParty, aliceWallet, Seq((50, 50)))
    }

    "(propose, accept, and) cancel a payment channel by sender" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobRemoteWallet, bobValidator)

      val (_, aliceProposal) = actAndCheck(
        "Alice proposes payment channel to Bob",
        aliceRemoteWallet.proposePaymentChannel(bobUserParty),
      )(
        "payment channel proposal is listed for both Alice and Bob",
        _ => {
          val aliceProposal = inside(aliceRemoteWallet.listPaymentChannelProposals()) {
            case Seq(p) => p
          }
          inside(bobRemoteWallet.listPaymentChannelProposals()) { case Seq(p) =>
            p shouldBe aliceProposal
          }
          aliceProposal
        },
      )
      clue("Bob accepts the proposal") {
        bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      }
      actAndCheck(
        "Bob requests a payment, and then immediately cancels the channel", {
          bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
          bobRemoteWallet.cancelPaymentChannelBySender(aliceUserParty)
        },
      )(
        "neither Alice nor Bob see neither the payment channel nor the payment request anymore",
        _ => {
          bobRemoteWallet.listPaymentChannels() shouldBe empty
          bobRemoteWallet.listOnChannelPaymentRequests() should not be empty
          eventually()(aliceRemoteWallet.listOnChannelPaymentRequests() should not be empty)
          eventually()(aliceRemoteWallet.listPaymentChannels() shouldBe empty)
        },
      )
    }

    "(propose, accept, and) cancel a payment channel by receiver" in { implicit env =>
      val aliceUserParty = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(this, bobRemoteWallet, bobValidator)

      // Alice proposes payment channel to Bob
      aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      // wallet store sometimes takes a bit to see the proposal
      eventually()(aliceRemoteWallet.listPaymentChannelProposals() should have size 1)
      val aliceProposal = aliceRemoteWallet.listPaymentChannelProposals()(0)

      // Bob monitors proposals and accepts the one
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob requests a payment, and then immediately cancels the channel
      bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      aliceRemoteWallet.cancelPaymentChannelByReceiver(bobUserParty)

      // Neither sees the payment channel nor the payment request anymore
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      aliceRemoteWallet.listOnChannelPaymentRequests() should not be empty
      eventually()(bobRemoteWallet.listOnChannelPaymentRequests() should not be empty)
      eventually()(bobRemoteWallet.listPaymentChannels() shouldBe empty)
    }

    "concurrent coin-operations" should {
      "be batched" in { implicit env =>
        val (alice, bob) = setupAliceAndBobAndChannel(this)
        aliceRemoteWallet.tap(50)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()
        // sending three commands in short succession to the idle wallet should lead to two transactions being executed
        // tx 1: first command that arrived is immediately executed
        // tx 2: other commands that arrived after the first command was started are executed in one batch
        (1 to 3).foreach(i => Future(aliceRemoteWallet.executeDirectTransfer(bob, i)).discard)
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
        aliceRemoteWallet.tap(50)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)
        val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

        val _ = Future(aliceRemoteWallet.executeDirectTransfer(bob, 10))
        (1 to defaultBatchSize + 1).foreach(_ =>
          Future(aliceRemoteWallet.executeDirectTransfer(bob, 1)).discard
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
          val alice = onboardWalletUser(this, aliceRemoteWallet, aliceValidator)
          val bob = onboardWalletUser(this, bobRemoteWallet, bobValidator)

          // tapping some coin & waiting for it to appear as a way to synchronize on the initialization of the apps.
          aliceRemoteWallet.tap(10)
          aliceValidator.remoteParticipant.ledger_api.acs
            .awaitJava(coinCodegen.Coin.COMPANION)(alice)
          // ... such that we don't grab the ledger offset when some init txs are still occurring
          val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

          // solo tap will kick off batch with only one coin operation
          Future(aliceRemoteWallet.tap(10)).discard

          // following three commands will be in one batch
          Future(aliceRemoteWallet.tap(10)).discard
          // fails because we don't have a channel - so removed from batch & error is reported back
          Future(
            loggerFactory.assertThrowsAndLogs[CommandFailure](
              aliceRemoteWallet
                .executeDirectTransfer(bob, 10),
              _.errorMessage should include regex ("NOT_FOUND/Payment channel between"),
            )
          ).discard
          Future(aliceRemoteWallet.tap(10)).discard

          eventually() {
            val coins = aliceRemoteWallet.list().coins
            // all four taps went through
            coins should have size 4
            // but no money was deducted due to the transfer
            checkWallet(alice, aliceRemoteWallet, Seq((9, 10), (9, 10), (9, 10), (9, 10)))
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
        aliceRemoteWallet.tap(10)
        aliceValidator.remoteParticipant.ledger_api.acs.awaitJava(coinCodegen.Coin.COMPANION)(alice)

        val cancelF = Future(bobRemoteWallet.cancelPaymentChannelBySender(alice))

        val transferF = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          // to simulate contention, submit a transfer on the channel immediately after cancelling the channel such
          // that the activeness lookup on the channel still goes through but the lookup inside the ledger fails.
          // this leads to a retry..
          Future(aliceRemoteWallet.executeDirectTransfer(bob, 5))
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
        eventually()(aliceRemoteWallet.listPaymentChannels() should have size 1)
        eventually()(bobRemoteWallet.listPaymentChannels() should have size 1)

        // such that on the retry, the transfer fails on the activeness check.
        eventually()(transferF.isCompleted shouldBe true)
        checkWallet(alice, aliceRemoteWallet, Seq((10, 10)))

      }
    }

    "rejects HS256 JWTs with invalid signatures" in { implicit env =>
      import com.auth0.jwt.JWT
      import com.auth0.jwt.algorithms.Algorithm
      import com.daml.network.wallet.v0
      import com.daml.network.auth.{JwtCallCredential}
      import io.grpc.ManagedChannelBuilder

      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val token = JWT.create().withSubject(aliceDamlUser).sign(Algorithm.HMAC256("wrong-secret"))

      // using grpc client directly rather than console refs, as token handling isn't threaded through to console command layer (yet)
      val channel =
        ManagedChannelBuilder
          .forAddress("localhost", aliceWallet.config.adminApi.port.unwrap)
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
        wallet: RemoteWalletAppReference,
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
        userWallet: LocalWalletAppReference,
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
        aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
          Seq(aliceUserParty),
          optTimeout = None,
          commands = new testSubsCodegen.TestSubscriptionContext(
            scan.getSvcPartyId().toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            aliceUserParty.toProtoPrimitive,
            "description",
          ).create.commands.asScala.toSeq,
        )
        aliceWallet.remoteParticipant.ledger_api.acs
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
        aliceWallet.remoteParticipant.ledger_api.commands.submitJava(
          actAs = Seq(aliceUserParty),
          optTimeout = None,
          commands = request.create.commands.asScala.toSeq,
        )
        request
      }
    }
  }
}
