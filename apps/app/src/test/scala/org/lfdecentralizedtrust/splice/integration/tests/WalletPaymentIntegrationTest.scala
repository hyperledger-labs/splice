package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.AppPaymentRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import java.util.UUID
import scala.jdk.CollectionConverters.*

class WalletPaymentIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "A wallet" should {
    "fail to get a non-existent payment request" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val nonExistentName = "does not exist"
      val errorString =
        s"HTTP 404 Not Found GET at '/api/validator/v0/wallet/app-payment-requests/does%20not%20exist' on 127.0.0.1:5503. Command failed, message: Contract id not found: ContractId(id = $nonExistentName"

      assertThrowsAndLogsCommandFailures(
        aliceWalletClient.getAppPaymentRequest(new AppPaymentRequest.ContractId(nonExistentName)),
        _.errorMessage should include(errorString),
      )
    }

    "allow a user to get, list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Check that no payment requests exist") {
        aliceWalletClient.listAppPaymentRequests() shouldBe empty
      }

      val description = "a payment for cool stuff"

      val (cid, reqC) =
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          description = description,
        )

      val reqFound = clue("Check that we can get the created payment request") {
        val reqFound = eventuallySucceeds() {
          aliceWalletClient.getAppPaymentRequest(cid)
        }
        reqFound.payload shouldBe reqC
        reqFound
      }

      clue("Check that the created payment request is listed") {
        val reqList = eventually() {
          aliceWalletClient.listAppPaymentRequests().headOption.value
        }
        reqList.payload shouldBe reqC
        reqList
      }

      actAndCheck(
        "Reject the payment request",
        aliceWalletClient.rejectAppPaymentRequest(reqFound.contractId),
      )(
        "Rejected request disappears from list",
        _ => aliceWalletClient.listAppPaymentRequests() shouldBe empty,
      )
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      val (cid, reqC) =
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        )

      eventuallySucceeds() {
        aliceWalletClient.getAppPaymentRequest(cid).payload shouldBe reqC
      }

      clue("Tap 50 amulets") {
        aliceWalletClient.tap(50)
        eventually() {
          aliceWalletClient.list().amulets should not be empty
        }
      }

      clue("Accept payment request") {
        val (acceptedPaymentId, _) =
          actAndCheck(
            "Alice accepts payment request",
            aliceWalletClient.acceptAppPaymentRequest(cid),
          )(
            "Payment request disappears from list",
            _ => aliceWalletClient.listAppPaymentRequests() shouldBe empty,
          )
        inside(aliceWalletClient.listAcceptedAppPayments()) { case Seq(r) =>
          r.contractId shouldBe acceptedPaymentId
          r.payload shouldBe new walletCodegen.AcceptedAppPayment(
            aliceUserParty.toProtoPrimitive,
            Seq(
              new walletCodegen.ReceiverAmuletAmount(
                aliceUserParty.toProtoPrimitive,
                BigDecimal(10).bigDecimal.setScale(10),
              )
            ).asJava,
            aliceUserParty.toProtoPrimitive,
            dsoParty.toProtoPrimitive,
            r.payload.lockedAmulet,
            r.payload.round,
            cid,
          )
        }
      }
    }

    "correctly select amulets for payments" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()

      val baseWalletFloor = walletUsdToAmulet(69)
      val baseWalletCeiling = walletUsdToAmulet(70)

      clue("Alice gets some amulets") {
        // Note: it would be great if we could add amulets with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceWalletClient.tap(10)
        aliceWalletClient.tap(40)
        aliceWalletClient.tap(20)
        // not using checkWallet as amulets may already be merged by automation
        checkBalance(
          aliceWalletClient,
          None,
          (baseWalletFloor, baseWalletCeiling),
          exactly(0),
          exactly(0),
        )
      }

      clue("Alice transfers 39") {
        p2pTransfer(aliceWalletClient, bobWalletClient, bob, 39)
        checkWallet(alice, aliceWalletClient, Seq((baseWalletFloor - 39, baseWalletCeiling - 39)))
      }
      clue("Alice transfers 19") {
        p2pTransfer(aliceWalletClient, bobWalletClient, bob, 19)
        checkWallet(
          alice,
          aliceWalletClient,
          Seq((baseWalletFloor - 39 - 19, baseWalletCeiling - 39 - 19)),
        )
      }
    }

    "allow two users to make direct transfers between them" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)

      val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))

      val offer =
        aliceWalletClient.createTransferOffer(
          bobUserParty,
          1.0,
          "direct transfer test",
          expiration,
          UUID.randomUUID.toString,
        )
      val offer2 =
        aliceWalletClient.createTransferOffer(
          bobUserParty,
          2.0,
          "to be rejected",
          expiration,
          UUID.randomUUID.toString,
        )
      val offer3 =
        aliceWalletClient.createTransferOffer(
          bobUserParty,
          3.0,
          "to be withdrawn",
          expiration,
          UUID.randomUUID.toString,
        )

      eventually() {
        aliceWalletClient.listTransferOffers() should have length 3
        bobWalletClient.listTransferOffers() should have length 3
      }

      actAndCheck("Bob accepts one offer", bobWalletClient.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceWalletClient.listTransferOffers() should have length 2
          aliceWalletClient.listAcceptedTransferOffers() should have length 0
          bobWalletClient.listTransferOffers() should have length 2
          bobWalletClient.listAcceptedTransferOffers() should have length 0
          checkWallet(
            aliceUserParty,
            aliceWalletClient,
            Seq((walletUsdToAmulet(99.8) - 1, walletUsdToAmulet(100) - 1)),
          )
          checkWallet(bobUserParty, bobWalletClient, Seq((1, 1)))
        },
      )

      actAndCheck(
        "Bob rejects one offer, alice withdraws the other", {
          bobWalletClient.rejectTransferOffer(offer2)
          aliceWalletClient.withdrawTransferOffer(offer3)
        },
      )(
        "No more offers listed",
        _ => {
          aliceWalletClient.listTransferOffers() should have length 0
          aliceWalletClient.listAcceptedTransferOffers() should have length 0
          bobWalletClient.listTransferOffers() should have length 0
          bobWalletClient.listAcceptedTransferOffers() should have length 0
        },
      )
    }

    "deduplicate transfer offers" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)

      val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(5))

      val trackingId = "dummy-key"

      val offerId = aliceWalletClient.createTransferOffer(
        bobUserParty,
        1.0,
        "direct transfer test",
        expiration,
        trackingId,
      )

      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          aliceWalletClient.createTransferOffer(
            bobUserParty,
            1.0,
            "direct transfer test - resubmitting",
            expiration,
            trackingId,
          ),
          _.errorMessage should include("Command submission already exists").or(
            include(s"Transfer offer with trackingId ${trackingId} already exists.")
          ),
        )
      )

      eventually() {
        inside(aliceWalletClient.listTransferOffers()) { case Seq(t) =>
          t.contractId should be(offerId)
        }
        inside(aliceWalletClient.listTransferOffers()) { case Seq(t) =>
          t.contractId should be(offerId)
        }
      }
    }

  }
}
