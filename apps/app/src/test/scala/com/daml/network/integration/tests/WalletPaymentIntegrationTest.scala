package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.wallet.payment.AppPaymentRequest
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*

class WalletPaymentIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "A wallet" should {
    "fail to get a non-existent payment request" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidatorBackend)

      val nonExistentName = "does not exist"
      val errorString =
        s"HTTP 404 Not Found GET at '/wallet/app-payment-requests/does%20not%20exist'. Command failed, message: contract id not found: ContractId(id = $nonExistentName"

      assertThrowsAndLogsCommandFailures(
        aliceWallet.getAppPaymentRequest(new AppPaymentRequest.ContractId(nonExistentName)),
        _.errorMessage should include(errorString),
      )
    }

    "allow a user to get, list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidatorBackend)

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      val description = "a payment for cool stuff"

      val (_, cid, reqC) =
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
          description = description,
        )

      val reqFound = clue("Check that we can get the created payment request") {
        val reqFound = eventuallySucceeds() {
          aliceWallet.getAppPaymentRequest(cid)
        }
        reqFound.appPaymentRequest.payload shouldBe reqC
        reqFound.deliveryOffer.payload.description shouldBe description
        reqFound
      }

      clue("Check that the created payment request is listed") {
        val reqList = eventually() {
          aliceWallet.listAppPaymentRequests().headOption.value
        }
        reqList.appPaymentRequest.payload shouldBe reqC
        reqList.deliveryOffer.payload.description shouldBe description
        reqList
      }

      actAndCheck(
        "Reject the payment request",
        aliceWallet.rejectAppPaymentRequest(reqFound.appPaymentRequest.contractId),
      )(
        "Rejected request disappears from list",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidatorBackend)

      val (referenceId, cid, reqC) =
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWallet.config.ledgerApiUser,
          aliceUserParty,
        )

      eventuallySucceeds() {
        aliceWallet.getAppPaymentRequest(cid).appPaymentRequest.payload shouldBe reqC
      }

      clue("Tap 50 coins") {
        aliceWallet.tap(50)
        eventually() {
          aliceWallet.list().coins should not be empty
        }
      }

      clue("Accept payment request") {
        val (acceptedPaymentId, _) =
          actAndCheck("Alice accepts payment request", aliceWallet.acceptAppPaymentRequest(cid))(
            "Payment request disappears from list",
            _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
          )
        inside(aliceWallet.listAcceptedAppPayments()) { case Seq(r) =>
          r.contractId shouldBe acceptedPaymentId
          r.payload shouldBe new walletCodegen.AcceptedAppPayment(
            aliceUserParty.toProtoPrimitive,
            Seq(
              new walletCodegen.ReceiverCCAmount(
                aliceUserParty.toProtoPrimitive,
                BigDecimal(10).bigDecimal.setScale(10),
              )
            ).asJava,
            aliceUserParty.toProtoPrimitive,
            svcParty.toProtoPrimitive,
            r.payload.lockedCoin,
            referenceId.toInterface(walletCodegen.DeliveryOffer.INTERFACE),
            r.payload.round,
          )
        }
      }
    }

    "correctly select coins for payments" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceWallet.tap(10)
        aliceWallet.tap(40)
        aliceWallet.tap(20)
        // not using checkWallet as coins may already be merged by automation
        checkBalance(aliceWallet, None, (69, 70), exactly(0), exactly(0))
      }

      clue("Alice transfers 39") {
        p2pTransfer(aliceValidatorBackend, aliceWallet, bobWalletClient, bob, 39)
        checkWallet(alice, aliceWallet, Seq((30, 31)))
      }
      clue("Alice transfers 19") {
        p2pTransfer(aliceValidatorBackend, aliceWallet, bobWalletClient, bob, 19)
        checkWallet(alice, aliceWallet, Seq((11, 12)))
      }
    }

    "allow two users to make direct transfers between them" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWallet.tap(100.0)

      val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))

      val offer =
        aliceWallet.createTransferOffer(
          bobUserParty,
          1.0,
          "direct transfer test",
          expiration,
          UUID.randomUUID.toString,
        )
      val offer2 =
        aliceWallet.createTransferOffer(
          bobUserParty,
          2.0,
          "to be rejected",
          expiration,
          UUID.randomUUID.toString,
        )
      val offer3 =
        aliceWallet.createTransferOffer(
          bobUserParty,
          3.0,
          "to be withdrawn",
          expiration,
          UUID.randomUUID.toString,
        )

      eventually() {
        aliceWallet.listTransferOffers() should have length 3
        bobWalletClient.listTransferOffers() should have length 3
      }

      actAndCheck("Bob accepts one offer", bobWalletClient.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceWallet.listTransferOffers() should have length 2
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWalletClient.listTransferOffers() should have length 2
          bobWalletClient.listAcceptedTransferOffers() should have length 0
          checkWallet(aliceUserParty, aliceWallet, Seq((98.8, 99.0)))
          checkWallet(bobUserParty, bobWalletClient, Seq((1.0, 1.0)))
        },
      )

      actAndCheck(
        "Bob rejects one offer, alice withdraws the other", {
          bobWalletClient.rejectTransferOffer(offer2)
          aliceWallet.withdrawTransferOffer(offer3)
        },
      )(
        "No more offers listed",
        _ => {
          aliceWallet.listTransferOffers() should have length 0
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWalletClient.listTransferOffers() should have length 0
          bobWalletClient.listAcceptedTransferOffers() should have length 0
        },
      )
    }

    "deduplicate transfer offers" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWallet.tap(100.0)

      val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(5))

      val idempotencyKey = "dummy-key"

      val offerId = aliceWallet.createTransferOffer(
        bobUserParty,
        1.0,
        "direct transfer test",
        expiration,
        idempotencyKey,
      )

      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          aliceWallet.createTransferOffer(
            bobUserParty,
            1.0,
            "direct transfer test - resubmitting",
            expiration,
            idempotencyKey,
          ),
          _.errorMessage should include("Command submission already exists"),
        )
      )

      eventually() {
        inside(aliceWallet.listTransferOffers()) { case Seq(t) =>
          t.contractId should be(offerId)
        }
        inside(aliceWallet.listTransferOffers()) { case Seq(t) =>
          t.contractId should be(offerId)
        }
      }
    }

  }
}
