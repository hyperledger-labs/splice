package com.daml.network.integration.tests

import com.daml.network.integration.tests.CNNodeTests.{CNNodeIntegrationTest, CNNodeTestCommon}
import com.daml.network.util.WalletTestUtil
import com.daml.network.codegen.java.cn.wallet.{payment as walletCodegen}

import scala.jdk.CollectionConverters.*

// TODO(tech-debt): Add tests that cover all possible CoinEvents
class ScanIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil with CNNodeTestCommon {

  "restart cleanly" in { implicit env =>
    scan.stop()
    scan.startSync()
  }

  "list total coin balances" in { implicit env =>
    val (aliceUserParty, _) = onboardAliceAndBob()
    aliceWallet.tap(100.0)
    bobWallet.tap(150.0)
    eventually() {
      val balances = scan.getTotalCoinBalance()
      balances.totalUnlocked should be(250.0)
      balances.totalLocked should be(0.0)
    }

    val (referenceId, _, reqC) =
      createSelfPaymentRequest(
        aliceValidator.remoteParticipantWithAdminToken,
        aliceWallet.config.ledgerApiUser,
        aliceUserParty,
      )

    val cid = eventually() {
      inside(aliceWallet.listAppPaymentRequests()) { case Seq(r) =>
        r.appPaymentRequest.payload shouldBe reqC
        r.appPaymentRequest.contractId
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
    eventually() {
      val balances = scan.getTotalCoinBalance()
      assertInRange(balances.totalUnlocked, (239.5, 240.0))
      assertInRange(balances.totalLocked, (10.0, 10.5))
    }
  }
}
