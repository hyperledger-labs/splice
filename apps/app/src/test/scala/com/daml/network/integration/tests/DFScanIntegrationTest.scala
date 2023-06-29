package com.daml.network.integration.tests

import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*

// TODO(tech-debt): Add tests that cover all possible CoinEvents
class DFScanIntegrationTest
    extends CNNodeIntegrationTest
    with WalletTestUtil
    with CNNodeTestCommon {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformToFront(
        CNNodeConfigTransforms.onlySv1
      )
      .withTrafficTopupsEnabled

  "restart cleanly" in { implicit env =>
    sv1ScanBackend.stop()
    sv1ScanBackend.startSync()
  }

  "list total coin balances" in { implicit env =>
    val (aliceUserParty, _) = onboardAliceAndBob()
    aliceWalletClient.tap(100.0)
    bobWalletClient.tap(150.0)
    eventually() {
      val balances = sv1ScanBackend.getTotalCoinBalance()
      balances.totalUnlocked should be(250.0)
      balances.totalLocked should be(0.0)
    }

    val (referenceId, _, reqC) =
      createSelfPaymentRequest(
        aliceValidatorBackend.participantClientWithAdminToken,
        aliceWalletClient.config.ledgerApiUser,
        aliceUserParty,
      )

    val cid = eventually() {
      inside(aliceWalletClient.listAppPaymentRequests()) { case Seq(r) =>
        r.appPaymentRequest.payload shouldBe reqC
        r.appPaymentRequest.contractId
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
      val balances = sv1ScanBackend.getTotalCoinBalance()
      assertInRange(balances.totalUnlocked, (239.5, 240.0))
      assertInRange(balances.totalLocked, (10.0, 10.5))
    }
  }
}
