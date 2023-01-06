package com.daml.network.integration.tests

import com.daml.network.codegen.java.cn.wallet.{payment as walletCodegen}
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.data.CantonTimestamp

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletPaymentIntegrationTest extends CoinIntegrationTest with WalletTestUtil {

  "A wallet" should {
    "allow a user to list, and reject app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      clue("Check that no payment requests exist") {
        aliceWallet.listAppPaymentRequests() shouldBe empty
      }

      val (_, _, reqC) =
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.damlUser,
          aliceUserParty,
        )

      val reqFound = clue("Check that we can see the created payment request") {
        val reqFound = eventually() {
          aliceWallet.listAppPaymentRequests().headOption.value
        }
        reqFound.payload shouldBe reqC
        reqFound
      }

      actAndCheck(
        "Reject the payment request",
        aliceWallet.rejectAppPaymentRequest(reqFound.contractId),
      )(
        "Rejected request disappears from list",
        _ => aliceWallet.listAppPaymentRequests() shouldBe empty,
      )
    }

    "allow a user to list and accept app payment requests" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)

      val (referenceId, _, reqC) =
        createSelfPaymentRequest(
          aliceWalletBackend.remoteParticipantWithAdminToken,
          aliceWallet.config.damlUser,
          aliceUserParty,
        )

      val cid = eventually() {
        inside(aliceWallet.listAppPaymentRequests()) { case Seq(r) =>
          r.payload shouldBe reqC
          r.contractId
        }
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
      val (alice, bob) = onboardAliceAndBob()

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceWallet.tap(10)
        aliceWallet.tap(40)
        aliceWallet.tap(20)
        checkWallet(alice, aliceWallet, Seq((10, 10), (20, 20), (40, 40)))
      }

      clue("Alice transfers 39") {
        p2pTransfer(aliceWallet, bobWallet, bob, 39)
        checkWallet(alice, aliceWallet, Seq((30, 31)))
      }
      clue("Alice transfers 19") {
        p2pTransfer(aliceWallet, bobWallet, bob, 19)
        checkWallet(alice, aliceWallet, Seq((11, 12)))
      }
    }

    "allow two users to make direct transfers between them" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      aliceWallet.tap(100.0)

      val expiration = CantonTimestamp.now().plus(Duration.ofMinutes(1))

      val offer =
        aliceWallet.createTransferOffer(bobUserParty, 1.0, "direct transfer test", expiration)
      val offer2 =
        aliceWallet.createTransferOffer(bobUserParty, 2.0, "to be rejected", expiration)
      val offer3 =
        aliceWallet.createTransferOffer(bobUserParty, 3.0, "to be withdrawn", expiration)

      eventually() {
        aliceWallet.listTransferOffers() should have length 3
        bobWallet.listTransferOffers() should have length 3
      }

      actAndCheck("Bob accepts one offer", bobWallet.acceptTransferOffer(offer))(
        "Accepted offer gets paid",
        _ => {
          aliceWallet.listTransferOffers() should have length 2
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWallet.listTransferOffers() should have length 2
          bobWallet.listAcceptedTransferOffers() should have length 0
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
          aliceWallet.listTransferOffers() should have length 0
          aliceWallet.listAcceptedTransferOffers() should have length 0
          bobWallet.listTransferOffers() should have length 0
          bobWallet.listAcceptedTransferOffers() should have length 0
        },
      )
    }
  }
}
