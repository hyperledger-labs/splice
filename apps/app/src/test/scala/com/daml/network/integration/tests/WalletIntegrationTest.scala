package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CC.{
  Coin as coinCodegen,
  CoinRules as coinRulesCodegen,
  Round as roundCodegen,
}
import com.daml.network.codegen.CN.Scripts.Wallet.TestSubscriptions as testSubsCodegen
import com.daml.network.codegen.CN.Scripts.TestWallet as testWalletCodegen
import com.daml.network.codegen.CN.Wallet.Subscriptions as subsCodegen
import com.daml.network.codegen.CN.{Wallet as walletCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.codegen.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.daml.network.codegen.java.cn.{directory as dirCodegen}
import com.daml.network.console.{LocalWalletAppReference, WalletAppReference}
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{CoinUtil, PaymentChannelTestUtil, Proto}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient.Balance
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.{DiscardOps, HasExecutionContext}
import org.slf4j.event.Level

import java.time.temporal.ChronoUnit
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Success, Try}

class WalletIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with PaymentChannelTestUtil {

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
          scan.getAppTransferContext(),
        ) // Lock away 10 coins in a payment request to the same party

        checkBalance(
          aliceRemoteWallet.balance(),
          1,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        nextRound(1)

        eventually()(
          checkBalance(
            aliceRemoteWallet.balance(),
            2,
            (99, 100),
            (9, 10),
            (0.00001, 0.00002),
          )
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
        scan.getAppTransferContext(),
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
              svc = scan.getSvcPartyId().toPrim,
              sender = aliceUserParty.toPrim,
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
        receiverQuantities = Seq(walletCodegen.ReceiverQuantity(aliceUserParty.toPrim, 10)),
        svc = svcParty.toPrim,
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
      val reqFound = eventually() {
        aliceRemoteWallet.listAppPaymentRequests().headOption.value
      }
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
              sender = aliceUserParty.toPrim,
              svc = scan.getSvcPartyId().toPrim,
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
        receiverQuantities = Seq(walletCodegen.ReceiverQuantity(aliceUserParty.toPrim, 10)),
        svc = svcParty.toPrim,
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
          receiverQuantities = Seq(
            walletCodegen.ReceiverQuantity(
              receiver = aliceUserParty.toPrim,
              quantity = 10.0,
            )
          ),
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
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
        val aliceValidatorParty = aliceValidator.getValidatorPartyId()

        aliceRemoteWallet.listSubscriptionRequests() shouldBe empty
        aliceRemoteWallet.listSubscriptions() shouldBe empty

        val request = createSelfSubscriptionRequest(aliceUserParty);

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
            r.payload.subscriptionData should equal(request.subscriptionData)
            r.payload.payData should equal(request.payData)
          }
        }
        clue("Collect the initial payment (as the receiver), which creates the subscription") {
          val collectCommand = initialPaymentId
            .exerciseSubscriptionInitialPayment_Collect(transferContext)
            .command
          aliceWallet.remoteParticipant.ledger_api.commands.submit(
            actAs = Seq(aliceUserParty),
            readAs = Seq(aliceValidatorParty),
            optTimeout = None,
            commands = Seq(collectCommand),
          )
        }
        val subscriptionStateId = clue("List subscriptions to find out state ID") {
          inside(aliceRemoteWallet.listSubscriptions()) { case Seq(sub) =>
            sub.main.payload should equal(request.subscriptionData)
            inside(sub.state) {
              case GrpcWalletAppClient.SubscriptionIdleState(state) => {
                state.payload.subscription should equal(sub.main.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            }
          }
        }
        val paymentId = clue("Initiate a subscription payment") {
          aliceRemoteWallet.makeSubscriptionPayment(subscriptionStateId)
        }
        clue("Check that payment contract was created successfully") {
          inside(aliceRemoteWallet.listSubscriptions()) { case Seq(sub) =>
            sub.main.payload should equal(request.subscriptionData)
            inside(sub.state) {
              case GrpcWalletAppClient.SubscriptionPayment(state) => {
                state.contractId shouldBe paymentId
                state.payload.subscription shouldBe sub.main.contractId
                state.payload.payData should equal(request.payData)
              }
            }
          }
          paymentId
        }
        clue(
          "Collect the second payment (as the receiver), which sets the subscription back to idle"
        ) {
          val collectCommand2 = paymentId
            .exerciseSubscriptionPayment_Collect(transferContext)
            .command
          aliceWallet.remoteParticipant.ledger_api.commands.submit(
            actAs = Seq(aliceUserParty),
            readAs = Seq(aliceValidatorParty),
            optTimeout = None,
            commands = Seq(collectCommand2),
          )
        }
        val subscriptionStateId2 = clue("List subscriptions to find out the new state ID") {
          inside(aliceRemoteWallet.listSubscriptions()) { case Seq(sub) =>
            sub.main.payload should equal(request.subscriptionData)
            inside(sub.state) {
              case GrpcWalletAppClient.SubscriptionIdleState(state) => {
                state.payload.subscription should equal(sub.main.contractId)
                state.payload.payData should equal(request.payData)
                state.contractId
              }
            }
          }
        }
        clue("Cancel the subscription") {
          aliceRemoteWallet.cancelSubscription(subscriptionStateId2);
          aliceRemoteWallet.listSubscriptions() shouldBe empty
        }
      }

    "allow a user to list multiple subscriptions in different states" in { implicit env =>
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      aliceValidator.onboardUser(aliceDamlUser)

      clue("Alice gets some coins") {
        aliceRemoteWallet.tap(50)
      }
      clue("Setting up directory as provider for the created subscriptions") {
        val directoryDarPath = "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        aliceDirectory.requestDirectoryInstall()
        aliceValidator.remoteParticipant.ledger_api.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(aliceUserParty)
      }
      aliceRemoteWallet.listSubscriptions() shouldBe empty

      clue("Creating 3 subscriptions") {
        List("alice1", "alice2", "alice3").foreach(name => {
          val (_, requestId) = aliceDirectory.requestDirectoryEntryWithSubscription(name)
          aliceRemoteWallet.acceptSubscriptionRequest(Primitive.ContractId(requestId.contractId))
        })
      }
      clue("Checking that 3 idle subscriptions are listed...") {
        eventually() {
          val subs = aliceRemoteWallet.listSubscriptions()
          subs.length shouldBe 3
          subs.foreach(sub => {
            sub.state should matchPattern { case GrpcWalletAppClient.SubscriptionIdleState(_) => }
          })
        }
      }
      clue("Stopping directory backend so that payments aren't collected.") {
        directory.stop()
      }
      clue("Alice makes a payment on one of the subscriptions") {
        val subscriptionStateId = inside(aliceRemoteWallet.listSubscriptions()) {
          case Seq(sub, _, _) => {
            inside(sub.state) {
              case GrpcWalletAppClient.SubscriptionIdleState(state) => { state.contractId }
            }
          }
        }
        aliceRemoteWallet.makeSubscriptionPayment(subscriptionStateId)
      }
      clue("Checking that 2 idle subscriptions and 1 payment are listed...") {
        eventually() {
          val subs = aliceRemoteWallet.listSubscriptions()
          subs.length shouldBe 3
          subs
            .filter(_.state match {
              case GrpcWalletAppClient.SubscriptionIdleState(_) => true
              case _ => false
            })
            .length shouldBe 2
          subs
            .filter(_.state match {
              case GrpcWalletAppClient.SubscriptionPayment(_) => true
              case _ => false
            })
            .length shouldBe 1
        }
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
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        aliceRemoteWallet
          .executeDirectTransfer(bobUserParty, 10),
        _.errorMessage should include regex ("Daml exception.*Direct transfers are allowed"),
      )
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

    "automatically collect app & validator rewards on coin operations" in { implicit env =>
      val (aliceUserParty, bobUserParty) = setupAliceAndBobAndChannel
      // Set-up payment channel between alice and her validator
      val proposalId =
        aliceValidatorRemoteWallet.proposePaymentChannel(
          aliceUserParty,
          senderTransferFeeRatio = 0.5,
        )
      eventually()(aliceRemoteWallet.listPaymentChannelProposals() should have size 1)
      aliceRemoteWallet.acceptPaymentChannelProposal(proposalId)
      eventually()(aliceValidatorRemoteWallet.listPaymentChannels() should have size 1)

      aliceRemoteWallet.tap(50)
      aliceValidatorRemoteWallet.tap(50)
      eventually()(aliceRemoteWallet.list().coins should have size 1)

      // Execute a transfer in round -> leads to rewards being generated
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 40)
      eventually()(aliceRemoteWallet.listAppRewards() should have size 1)
      eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 1)

      // next round.
      svc.openRound(1, 1)
      svc.startClosingRound(0)
      svc.startIssuingRound(0)
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceValidator.getValidatorPartyId(), roundCodegen.IssuingMiningRound)

      // alice uses her reward
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 1)
      eventually()(aliceRemoteWallet.listAppRewards() should have size 0)

      // 2 validator rewards due to two transfers
      eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 2)
      aliceValidatorRemoteWallet.executeDirectTransfer(aliceUserParty, 1)
      // +1 for the transfer, -1 due to the reward from round 0 being used
      eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 2 + 1 - 1)
      // no rewards are used, since all other rewards are from round 1
      aliceValidatorRemoteWallet.executeDirectTransfer(aliceUserParty, 1)
      eventually()(aliceValidatorRemoteWallet.listValidatorRewards() should have size 3)

    }

    "list and manually collect app & validator rewards" in { implicit env =>
      val (aliceUserParty, bobUserParty) = setupAliceAndBobAndChannel

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
      svc.openRound(1, 1)
      svc.startClosingRound(0)
      svc.startIssuingRound(0)
      eventually() {
        val r = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          Try(bobRemoteWallet.collectRewards(0)),
          entries => forAll(entries)(_.message should include("No issuing mining round found")),
        )
        inside(r) { case Success(_) => }
      }
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
      (1 to 3).foreach(i => Future(aliceRemoteWallet.executeDirectTransfer(bob, i)).discard)
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
        Future(aliceRemoteWallet.executeDirectTransfer(bob, 1)).discard
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
            _.message should include("GrpcRequestRefusedByServer: NOT_FOUND/PaymentChannel between")
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
          val ExpiringQuantity(initialQuantity, _, ratePerRound) =
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
      transferContext: coinRulesCodegen.AppTransferContext,
  ): Unit = {
    val coinOpt = coins.find(_.effectiveQuantity >= quantity)
    val expirationOpt = Proto.decode(Proto.Timestamp)(20000000000000000L) // Wed May 18 2033

    (coinOpt, expirationOpt) match {
      case (Some(coin), Right(expiration)) => {
        userWallet.remoteParticipant.ledger_api.commands.submit(
          Seq(userParty, validatorParty),
          optTimeout = None,
          commands = Seq(
            transferContext.coinRules
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
                ),
                coinRulesCodegen.TransferContext(
                  openMiningRound = transferContext.openMiningRound,
                  issuingMiningRounds = Map.empty[roundCodegen.Round, Primitive.ContractId[
                    roundCodegen.IssuingMiningRound
                  ]],
                  validatorRights =
                    Map.empty[Primitive.Party, Primitive.ContractId[coinCodegen.ValidatorRight]],
                ),
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
    svc.openRound(i + 1, 1)
  }

  def createSelfSubscriptionRequest(aliceUserParty: PartyId)(implicit
      env: CoinTestConsoleEnvironment
  ): subsCodegen.SubscriptionRequest = {
    val contextId = clue("Create a subscription context") {
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          testSubsCodegen
            .TestSubscriptionContext(
              svc = scan.getSvcPartyId().toPrim,
              user = aliceUserParty.toPrim,
              service = aliceUserParty.toPrim,
              description = "description",
            )
            .create
            .command
        ),
      )
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, testSubsCodegen.TestSubscriptionContext)
        .contractId
    }
    clue("Create a subscription request to self") {
      val subscriptionData = subsCodegen.Subscription(
        sender = aliceUserParty.toPrim,
        receiver = aliceUserParty.toPrim,
        provider = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        context = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(contextId)),
      )
      val payData = subsCodegen.SubscriptionPayData(
        paymentQuantity = BigDecimal(10: Int),
        paymentInterval = RelTime(microseconds = 60 * 60 * 1000000L),
        paymentDuration = RelTime(microseconds = 60 * 60 * 1000000L),
        collectionDuration = RelTime(microseconds = 60 * 1000000L),
      ) // paymentDuration == paymenInterval, so we can make a second payment immediately,
      // without having to mess with time
      val request = subsCodegen.SubscriptionRequest(
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
  }
}
