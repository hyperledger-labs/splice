package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{Coin as coinCodegen, CoinRules as coinRulesCodegen}
import com.daml.network.codegen.CN.Scripts.Wallet.TestSubscriptions as testSubscriptionsCodegen
import com.daml.network.codegen.CN.Scripts.TestWallet as testWalletCodegen
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.codegen.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.daml.network.console.{LocalWalletAppReference, WalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{
  CoinUtil,
  CommonCoinAppInstanceReferences,
  PaymentChannelTestUtil,
  Proto,
}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient.{Balance, ListResponse}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}

import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.duration.*

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with HasExecutionContext
    with CommonCoinAppInstanceReferences
    with PaymentChannelTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "A wallet" should {

    "restart cleanly" in { implicit env =>
      aliceWallet.stop()
      aliceWallet.startSync()
    }

    "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
      implicit env =>
        val aliceDamlUser = aliceRemoteWallet.config.damlUser

        val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceRemoteWallet.list() shouldBe ListResponse(Seq(), Seq())

        val exactly = (x: BigDecimal) => (x, x)
        val ranges1 = Seq(exactly(50))
        aliceRemoteWallet.tap(50)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)

        val ranges2 = Seq(exactly(50), exactly(60))
        aliceRemoteWallet.tap(60)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)

        checkBalance(aliceRemoteWallet.balance(), 0, exactly(110), exactly(0), exactly(0))

        nextRound(0)
        lockCoins(
          aliceWallet,
          aliceUserParty,
          aliceValidatorParty,
          aliceRemoteWallet.list().coins,
          10,
        ) // Lock away 10 coins in a payment request to the same party

        checkBalance(
          aliceRemoteWallet.balance(),
          1,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        nextRound(1)

        checkBalance(
          aliceRemoteWallet.balance(),
          2,
          (99, 100),
          (9, 10),
          (0.00001, 0.00002),
        )
    }

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser

      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()

      aliceRemoteWallet.tap(50)

      aliceRemoteWallet.list().coins.length shouldBe 1
      aliceRemoteWallet.list().lockedCoins.length shouldBe 0

      lockCoins(
        aliceWallet,
        aliceUserParty,
        aliceValidatorParty,
        aliceRemoteWallet.list().coins,
        25,
      )

      aliceRemoteWallet.list().coins.length shouldBe 1
      eventually()(aliceRemoteWallet.list().lockedCoins should have length 1)

      aliceRemoteWallet.list().coins.head.round shouldBe 0
      aliceRemoteWallet.list().coins.head.accruedHoldingFee shouldBe 0
      assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

      aliceRemoteWallet.list().lockedCoins.head.round shouldBe 0
      aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
      assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))

      nextRound(0)

      aliceRemoteWallet.list().coins.head.round shouldBe 1
      assertInRange(aliceRemoteWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
      assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

      aliceRemoteWallet.list().lockedCoins.head.round shouldBe 1
      assertInRange(
        aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee,
        (0.000004, 0.000005),
      )
      assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
    }

    "allow a user to list, and reject app payment requests" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Check that no payment requests exist
      aliceRemoteWallet.listAppPaymentRequests() shouldBe empty

      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          testWalletCodegen
            .TestDeliveryOffer(
              p = aliceUserParty.toPrim,
              description = "description",
            )
            .create
            .command
        ),
      )
      val referenceId =
        aliceWallet.remoteParticipant.ledger_api.acs
          .await(aliceUserParty, testWalletCodegen.TestDeliveryOffer)
          .contractId

      // Create a payment request to self.
      val reqC = walletCodegen.AppPaymentRequest(
        sender = aliceUserParty.toPrim,
        provider = aliceUserParty.toPrim,
        receiver = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        quantity = BigDecimal(10: Int),
        expiresAt = binding.Primitive.Timestamp
          .discardNanos(java.time.Instant.now().plus(1, ChronoUnit.MINUTES))
          .getOrElse(sys.error("Invalid instant")),
        collectionDuration = RelTime(microseconds = 60 * 1000000),
        deliveryOffer = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(reqC.create.command),
      )

      // Check that we can see the created payment request
      val reqFound = aliceRemoteWallet.listAppPaymentRequests().headOption.value
      reqFound.payload shouldBe reqC

      // Reject the payment request
      aliceRemoteWallet.rejectAppPaymentRequest(reqFound.contractId)

      // Check that there are no more payment requests
      val requests2 = aliceRemoteWallet.listAppPaymentRequests()
      requests2 shouldBe empty
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          testWalletCodegen
            .TestDeliveryOffer(
              p = aliceUserParty.toPrim,
              description = "description",
            )
            .create
            .command
        ),
      )
      val referenceId =
        aliceWallet.remoteParticipant.ledger_api.acs
          .await(aliceUserParty, testWalletCodegen.TestDeliveryOffer)
          .contractId

      // Create a payment request to self.
      val reqC = walletCodegen.AppPaymentRequest(
        sender = aliceUserParty.toPrim,
        provider = aliceUserParty.toPrim,
        receiver = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        quantity = BigDecimal(10: Int),
        expiresAt = binding.Primitive.Timestamp
          .discardNanos(java.time.Instant.now().plus(1, ChronoUnit.MINUTES))
          .getOrElse(sys.error("Invalid instant")),
        collectionDuration = RelTime(microseconds = 60 * 1000000),
        deliveryOffer = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(reqC.create.command),
      )

      val cid = inside(aliceRemoteWallet.listAppPaymentRequests()) { case Seq(r) =>
        r.payload shouldBe reqC
        r.contractId
      }

      aliceRemoteWallet.tap(50)
      val acceptedPaymentId = aliceRemoteWallet.acceptAppPaymentRequest(cid)
      aliceRemoteWallet.listAppPaymentRequests() shouldBe empty
      inside(aliceRemoteWallet.listAcceptedAppPayments()) { case Seq(r) =>
        r.contractId shouldBe acceptedPaymentId
        r.payload shouldBe walletCodegen.AcceptedAppPayment(
          sender = aliceUserParty.toPrim,
          receiver = aliceUserParty.toPrim,
          provider = aliceUserParty.toPrim,
          svc = svcParty.toPrim,
          lockedCoin = r.payload.lockedCoin,
          deliveryOffer = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
        )
      }
    }

    "correctly select coins for payments" in { implicit env =>
      val aliceUserParty = clue("Onboard alice on her self-hosted validator") {
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        aliceValidator.onboardUser(aliceDamlUser)
      }

      val bobUserParty = clue("Onboard bob on his self-hosted validator") {
        val bobDamlUser = bobRemoteWallet.config.damlUser
        bobValidator.onboardUser(bobDamlUser)
      }

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
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      val (subscriptionData, payData) = subscriptionTestData(aliceUserParty);

      aliceRemoteWallet.listSubscriptionRequests() shouldBe empty

      val request = clue("Create a subscription request to self") {
        val request = walletCodegen.Subscriptions.SubscriptionRequest(
          subscriptionData,
          payData,
        )
        aliceWallet.remoteParticipant.ledger_api.commands.submit(
          actAs = Seq(aliceUserParty),
          optTimeout = None,
          commands = Seq(request.create.command),
        )
        request
      }
      val requestId = clue("List subscription requests to find out request ID") {
        inside(aliceRemoteWallet.listSubscriptionRequests()) { case Seq(r) =>
          r.payload shouldBe request
          r.contractId
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
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()
        val (subscriptionData, payData) = subscriptionTestData(aliceUserParty);

        aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
        aliceRemoteWallet.listSubscriptionIdleStates() shouldBe empty

        val request = clue("Create a subscription request to self") {
          val request = walletCodegen.Subscriptions.SubscriptionRequest(
            subscriptionData,
            payData,
          )
          aliceWallet.remoteParticipant.ledger_api.commands.submit(
            actAs = Seq(aliceUserParty),
            optTimeout = None,
            commands = Seq(request.create.command),
          )
          request
        }
        val requestId = clue("List subscription requests to find out request ID") {
          inside(aliceRemoteWallet.listSubscriptionRequests()) { case Seq(r) =>
            r.payload shouldBe request
            r.contractId
          }
        }
        clue("Alice gets some coins") {
          aliceRemoteWallet.tap(50)
        }
        val initialPaymentId =
          clue("Accept the subscription request, initiating the first subscription payment") {
            aliceRemoteWallet.acceptSubscriptionRequest(requestId)
          }
        clue("Check that initial payment contract was created successfully") {
          aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
          inside(aliceRemoteWallet.listSubscriptionInitialPayments()) { case Seq(r) =>
            r.contractId shouldBe initialPaymentId
            r.payload.subscriptionData should equal(subscriptionData)
            r.payload.payData should equal(payData)
          }
        }
        clue("Collect the initial payment (as the receiver), which creates the subscription") {
          val collectCommand = initialPaymentId
            .exerciseSubscriptionInitialPayment_Collect(submitter = aliceUserParty.toPrim)
            .command
          aliceWallet.remoteParticipant.ledger_api.commands.submit(
            actAs = Seq(aliceUserParty),
            readAs = Seq(aliceValidatorParty),
            optTimeout = None,
            commands = Seq(collectCommand),
          )
        }
        val subscriptionStateId = clue("List idle subscriptions to find out state ID") {
          inside(aliceRemoteWallet.listSubscriptionIdleStates()) { case Seq(r) =>
            r.payload.subscriptionData should equal(subscriptionData)
            r.payload.payData should equal(payData)
            r.contractId
          }
        }
        val paymentId = clue("Initiate a subscription payment") {
          aliceRemoteWallet.makeSubscriptionPayment(subscriptionStateId)
        }
        clue("Check that payment contract was created successfully") {
          aliceRemoteWallet.listSubscriptionIdleStates() shouldBe empty
          inside(aliceRemoteWallet.listSubscriptionPayments()) { case Seq(r) =>
            r.contractId shouldBe paymentId
            r.payload.subscriptionData should equal(subscriptionData)
            r.payload.payData should equal(payData)
          }
          paymentId
        }
        clue(
          "Collect the second payment (as the receiver), which sets the subscription back to idle"
        ) {
          val collectCommand2 = paymentId
            .exerciseSubscriptionPayment_Collect(submitter = aliceUserParty.toPrim)
            .command
          aliceWallet.remoteParticipant.ledger_api.commands.submit(
            actAs = Seq(aliceUserParty),
            readAs = Seq(aliceValidatorParty),
            optTimeout = None,
            commands = Seq(collectCommand2),
          )
        }
        val subscriptionStateId2 = clue("List idle subscriptions to find out the new state ID") {
          inside(aliceRemoteWallet.listSubscriptionIdleStates()) { case Seq(r) =>
            r.payload.subscriptionData should equal(subscriptionData)
            r.payload.payData should equal(payData)
            r.contractId
          }
        }
        clue("Cancel the subscription") {
          aliceRemoteWallet.cancelSubscription(subscriptionStateId2);
          aliceRemoteWallet.listSubscriptionIdleStates() shouldBe empty
          aliceRemoteWallet.listSubscriptionPayments() shouldBe empty
        }
      }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobDamlUser = bobRemoteWallet.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

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
      bobWallet.remoteParticipant.ledger_api.acs.await(bobUserParty, coinCodegen.Coin)
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
          .of_party(bobUserParty, filterTemplates = Seq(coinCodegen.Coin.id))
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
      // TODO(#756): reenable once error handling has been improved.
//      loggerFactory.assertThrowsAndLogs[CommandFailure](
//        aliceRemoteWallet
//          .executeDirectTransfer(bobUserParty, 10),
//        _.errorMessage should include regex ("Unhandled Daml exception.*Direct transfers are allowed"),
//      )
    }

    "allow two remote wallets to connect to one local wallet and tap" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      aliceRemoteWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((50, 50)))

      // Onboard charlie onto alice's validator
      val charlieDamlUser = charlieRemoteWallet.config.damlUser
      val charlieUserParty = aliceValidator.onboardUser(charlieDamlUser)

      charlieRemoteWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieRemoteWallet, Seq((50, 50)))

      aliceWallet.setWalletContext(charlieDamlUser)
      checkWallet(charlieUserParty, aliceWallet, Seq((50, 50)))
    }

    "(propose, accept, and) cancel a payment channel by sender" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobDamlUser = bobRemoteWallet.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

      // Alice proposes payment channel to Bob
      aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceRemoteWallet.listPaymentChannelProposals()
      val aliceProposal = aliceProposals(0)

      // Bob monitors proposals and accepts the one
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob requests a payment, and then immediately cancels the channel
      bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      bobRemoteWallet.cancelPaymentChannelBySender(aliceUserParty)

      // Neither sees the payment channel nor the payment request anymore
      bobRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listOnChannelPaymentRequests() should not be empty
      eventually()(aliceRemoteWallet.listOnChannelPaymentRequests() should not be empty)
      eventually()(aliceRemoteWallet.listPaymentChannels() shouldBe empty)
    }

    "(propose, accept, and) cancel a payment channel by receiver" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobDamlUser = bobRemoteWallet.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

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

    "list and collect app & validator rewards" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobDamlUser = bobRemoteWallet.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

      // Setup payment channel between alice and bob
      val aliceProposalId =
        aliceRemoteWallet.proposePaymentChannel(bobUserParty, senderTransferFeeRatio = 0.5)
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposalId)
      eventually()(aliceRemoteWallet.listPaymentChannels() should have size 1)

      // Setup payment channel between bob and alice
      val bobProposalId =
        bobRemoteWallet.proposePaymentChannel(aliceUserParty, senderTransferFeeRatio = 0.5)
      eventually()(aliceRemoteWallet.listPaymentChannelProposals() should have size 1)
      aliceRemoteWallet.acceptPaymentChannelProposal(bobProposalId)
      eventually()(bobRemoteWallet.listPaymentChannels() should have size 2)

      // Tap coin and do a transfer from alice to bob
      aliceRemoteWallet.tap(50)
      eventually()(aliceRemoteWallet.list().coins should have size 1)
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 40)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice; bob will receive some app rewards
      eventually()(bobRemoteWallet.list().coins should have size 1)
      bobRemoteWallet.executeDirectTransfer(aliceUserParty, 30)

      // Wait for app rewards to become visible in bob's wallet, and check structure
      bobWallet.remoteParticipant.ledger_api.acs
        .await(bobUserParty, coinCodegen.AppReward)
        .contractId
      val appRewards = bobRemoteWallet.listAppRewards()
      appRewards should have size 1
      bobRemoteWallet.listValidatorRewards() shouldBe empty

      // Wait for validator rewards to become visible in alice's wallet, check structure
      val validatorRewards = aliceValidatorRemoteWallet.listValidatorRewards()
      validatorRewards should have size 1
      aliceRemoteWallet.tap(200)
      eventually()(aliceRemoteWallet.list().coins should have size 3)

      // Bob collects/realizes rewards
      val prevCoins = bobRemoteWallet.list().coins
      svc.openRound(1)
      svc.startClosingRound(0)
      svc.startIssuingRound(0)
      bobRemoteWallet.collectRewards(0)
      bobRemoteWallet.listValidatorRewards() shouldBe empty
      // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
      // for rewards.
      checkWallet(
        bobUserParty,
        bobRemoteWallet,
        prevCoins
          .map(c =>
            (
              c.contract.payload.quantity.initialQuantity,
              c.contract.payload.quantity.initialQuantity + 2,
            )
          )
          .sortBy(_._1),
      )
    }
  }

  "support coin redistribution" in { implicit env =>
    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

    aliceRemoteWallet.tap(50)
    val coin2 = aliceRemoteWallet.tap(10)
    val coin3 = aliceRemoteWallet.tap(15)
    aliceRemoteWallet.redistribute(Seq(coin2, coin3), Seq(Some(3.0), Some(5.0), None))
    val exactly = (x: BigDecimal) => (x, x)
    checkWallet(
      aliceUserParty,
      aliceRemoteWallet,
      Seq(exactly(3.0), exactly(5.0), (16.0, 17.0), exactly(50.0)),
    )

    val all = aliceRemoteWallet.list().coins.map(c => c.contract.contractId)
    aliceRemoteWallet.redistribute(all, Seq(None))
    checkWallet(aliceUserParty, aliceRemoteWallet, Seq((74.0, 75.0)))
  }

  "concurrent coin-operations" should {
    "be batched" in { implicit env =>
      val (alice, bob) = setupAliceAndBobAndChannel
      aliceRemoteWallet.tap(50)
      aliceValidator.remoteParticipant.ledger_api.acs.await(alice, coinCodegen.Coin)
      val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()
      // sending three commands in short succession to the idle wallet should lead to two transactions being executed
      // tx 1: first command that arrived is immediately executed
      // tx 2: other commands that arrived after the first command was started are executed in one batch
      (1 to 3).foreach(i => Future(aliceRemoteWallet.executeDirectTransfer(bob, i)))
      // Wait until 2 transactions have been received
      val txs = aliceValidator.remoteParticipant.ledger_api.transactions
        .trees(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
      val createdCoinsInTx = txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin)(_))

      // create change + transferred coin
      createdCoinsInTx(0) should have size 2
      // (create change + transferred coin) x2
      createdCoinsInTx(1) should have size 2 * 2
    }

    "be batched up to `batchSize` concurrent coin-operations" in { implicit env =>
      val defaultBatchSize = 10
      val (alice, bob) = setupAliceAndBobAndChannel
      aliceRemoteWallet.tap(50)
      aliceValidator.remoteParticipant.ledger_api.acs.await(alice, coinCodegen.Coin)
      val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

      val _ = Future(aliceRemoteWallet.executeDirectTransfer(bob, 10))
      (1 to defaultBatchSize + 1).foreach(_ =>
        Future(aliceRemoteWallet.executeDirectTransfer(bob, 1))
      )
      // 3 txs;
      // tx 1: initial transfer
      // tx 2: 10 subsequent batched transfers
      // tx 3: single transfer that was not picked due to the batch size limit
      val txs = aliceValidator.remoteParticipant.ledger_api.transactions
        .trees(Set(alice), completeAfter = 3, beginOffset = offsetBefore)
      val createdCoinsInTx = txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin)(_))

      // create change + transferred coin
      createdCoinsInTx(0) should have size 2
      // (create change + transferred coin) x10
      createdCoinsInTx(1) should have size 10 * 2
      // create change + transferred coin
      createdCoinsInTx(2) should have size 2
    }

    "fail operations early and independently that don't pass the activeness lookup checks" in {
      implicit env =>
        val alice = aliceValidator.onboardUser(aliceRemoteWallet.config.damlUser)
        val bob = bobValidator.onboardUser(bobRemoteWallet.config.damlUser)

        // tapping some coin & waiting for it to appear as a way to synchronize on the initialization of the apps.
        aliceRemoteWallet.tap(10)
        aliceValidator.remoteParticipant.ledger_api.acs.await(alice, coinCodegen.Coin)
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

        val txs = aliceValidator.remoteParticipant.ledger_api.transactions
          .trees(Set(alice), completeAfter = 2, beginOffset = offsetBefore)
        val createdCoinsInTx = txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin)(_))
        createdCoinsInTx(0) should have size 1
        // two taps went through, even though transfer in same batch failed.
        createdCoinsInTx(1) should have size 2
    }

    "retry a batch if it fails due to contention" in { implicit env =>
      val (alice, bob) = setupAliceAndBobAndChannel
      aliceRemoteWallet.tap(10)
      aliceValidator.remoteParticipant.ledger_api.acs.await(alice, coinCodegen.Coin)
      val offsetBefore = aliceValidator.remoteParticipant.ledger_api.transactions.end()

      val cancelF = Future(bobRemoteWallet.cancelPaymentChannelBySender(alice))
      // to simulate contention, submit a transfer on the channel immediately after cancelling the channel such
      // that the activeness lookup on the channel still goes through but the lookup inside the ledger fails
      // this leads to a retry
      val transferF = Future(aliceRemoteWallet.executeDirectTransfer(bob, 5))

      // eventually, the cancel goes through
      eventually()(cancelF.isCompleted shouldBe true)
      eventually()(aliceRemoteWallet.listPaymentChannels() should have size 0)
      eventually()(bobRemoteWallet.listPaymentChannels() should have size 0)
      // now reestablish the payment channel
      val proposalId = aliceRemoteWallet.proposePaymentChannel(bob)
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      bobRemoteWallet.acceptPaymentChannelProposal(proposalId)

      // such that on the retry, the transfer goes through.
      eventually()(transferF.isCompleted shouldBe true)
      checkWallet(alice, aliceRemoteWallet, Seq((4, 5)))
      checkWallet(bob, bobRemoteWallet, Seq((4, 5)))
      val txs = aliceValidator.remoteParticipant.ledger_api.transactions
        .trees(Set(alice), completeAfter = 4, beginOffset = offsetBefore)
      val createdCoinsInTx = txs.map(DecodeUtil.decodeAllCreatedTree(coinCodegen.Coin)(_))
      // after the cancel, propose & accept of the payment channel, the fourth transaction should lead to the creation of
      // two coins (change, transferred coin)
      createdCoinsInTx(3) should have size 2

    }
  }

  "accepts an optional JWT token with user in subject" in { implicit env =>
    import cats.syntax.either.*
    import com.auth0.jwt.JWT
    import com.auth0.jwt.algorithms.Algorithm
    import com.daml.network.auth.JwtCallCredential
    import com.daml.network.util.Contract
    import com.daml.network.wallet.v0
    import io.grpc.ManagedChannelBuilder

    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

    val token = JWT.create().withSubject(aliceDamlUser).sign(Algorithm.HMAC256("secret"))

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

    client.tap(new v0.TapRequest("10.0"))
    val res = client.list(new v0.ListRequest())

    res.coins.head.effectiveQuantity shouldBe "10.0000000000"

    for {
      contractOpt <- res.coins.head.contract.toRight("Could not find contract payload")
      contract <- Contract.fromProto(coinCodegen.Coin)(contractOpt).leftMap(_.toString)
    } yield {
      contract.payload.owner.toString shouldBe aliceUserParty.toPrim.toString
    }
  }

  /** @param expectedQuantityRanges: lower and upper bounds for coins sorted by their initial quantity in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppReference,
      expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
  ): Unit = clue(s"checking wallet with $expectedQuantityRanges") {
    eventually(10.seconds, 500.millis) {
      val coins = wallet.list().coins.sortBy(coin => coin.contract.payload.quantity.initialQuantity)
      coins should have size (expectedQuantityRanges.size.toLong)
      coins
        .zip(expectedQuantityRanges)
        .foreach { case (coin, quantityBounds) =>
          coin.contract.payload.owner shouldBe walletParty.toPrim
          val ExpiringQuantity(initialQuantity, createdAt, ratePerRound) =
            coin.contract.payload.quantity
          assertInRange(initialQuantity, quantityBounds)
          ratePerRound shouldBe RatePerRound(
            CoinUtil.defaultHoldingFee.rate.doubleValue
          )
        }
    }
  }

  def checkBalance(
      balance: Balance,
      expectedRound: Long,
      expectedUQRange: (BigDecimal, BigDecimal),
      expectedLQRange: (BigDecimal, BigDecimal),
      expectedHRange: (BigDecimal, BigDecimal),
  ): Unit = {
    balance.round shouldBe expectedRound
    assertInRange(balance.unlockedQty, expectedUQRange)
    assertInRange(balance.lockedQty, expectedLQRange)
    assertInRange(balance.holdingFees, expectedHRange)
  }

  def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
    value should (be >= range._1 and be <= range._2)
  }

  def lockCoins(
      userWallet: LocalWalletAppReference,
      userParty: PartyId,
      validatorParty: PartyId,
      coins: Seq[GrpcWalletAppClient.CoinPosition],
      quantity: Int,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    val coinOpt = coins.find(_.effectiveQuantity >= quantity)
    val expirationOpt = Proto.decode(Proto.Timestamp)(20000000000000000L) // Wed May 18 2033

    (coinOpt, expirationOpt) match {
      case (Some(coin), Right(expiration)) => {
        userWallet.remoteParticipant.ledger_api.commands.submit(
          Seq(userParty, validatorParty),
          optTimeout = None,
          commands = Seq(
            coinRulesCodegen.CoinRules
              .key(svcParty.toPrim)
              .exerciseCoinRules_Transfer(
                coinRulesCodegen.Transfer(
                  sender = userParty.toPrim,
                  provider = userParty.toPrim,
                  inputs = Primitive.List(
                    coinRulesCodegen.TransferInput.InputCoin(coin.contract.contractId)
                  ),
                  outputs = Primitive.List(
                    coinRulesCodegen.TransferOutput.OutputSenderCoin(
                      exactQuantity = Some(quantity),
                      lock = Primitive.Optional[coinCodegen.TimeLock](
                        coinCodegen.TimeLock(
                          userParty.toPrim,
                          expiresAt = expiration,
                        )
                      ),
                    ),
                    coinRulesCodegen.TransferOutput.OutputSenderCoin(
                      exactQuantity = None,
                      lock = None,
                    ),
                  ),
                  payload = "lock coins",
                )
              )
              .command
          ),
        )
      }
      case _ => {
        coinOpt shouldBe a[Some[_]]
        expirationOpt shouldBe a[Right[_, _]]
      }
    }
  }

  def nextRound(i: Long)(implicit env: CoinTestConsoleEnvironment): Unit = {
    svc.startClosingRound(i)
    svc.startIssuingRound(i)
    svc.closeRound(i)
    svc.openRound(i + 1)
  }

  def subscriptionTestData(aliceUserParty: PartyId)(implicit
      env: CoinTestConsoleEnvironment
  ): (walletCodegen.Subscriptions.Subscription, walletCodegen.Subscriptions.SubscriptionPayData) = {
    // Create a subscription context
    aliceWallet.remoteParticipant.ledger_api.commands.submit(
      Seq(aliceUserParty),
      optTimeout = None,
      commands = Seq(
        testSubscriptionsCodegen
          .TestSubscriptionContext(
            user = aliceUserParty.toPrim,
            service = aliceUserParty.toPrim,
            description = "description",
          )
          .create
          .command
      ),
    )
    val referenceId =
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, testSubscriptionsCodegen.TestSubscriptionContext)
        .contractId
    // Assemble actual test data
    val subscriptionData = walletCodegen.Subscriptions.Subscription(
      sender = aliceUserParty.toPrim,
      receiver = aliceUserParty.toPrim,
      provider = aliceUserParty.toPrim,
      svc = svcParty.toPrim,
      context = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
    )
    val payData = walletCodegen.Subscriptions.SubscriptionPayData(
      paymentQuantity = BigDecimal(10: Int),
      paymentInterval = RelTime(microseconds = 60 * 60 * 1000000L),
      paymentDuration = RelTime(microseconds = 60 * 60 * 1000000L),
      collectionDuration = RelTime(microseconds = 60 * 1000000L),
    ) // paymentDuration == paymenInterval, so we can make a second payment immediately
    (subscriptionData, payData)
  }
}
