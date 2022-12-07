package com.daml.network.util

import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  LedgerApiUtils,
  ValidatorAppReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.sequencing.SequencerTestUtils.eventually
import com.digitalasset.canton.topology.PartyId
import org.scalatest.matchers.should.Matchers.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

// TODO(M1-92 - Tech Debt): This could be reused in more places and extended
trait CoinTestUtil {
  this: CommonCoinAppInstanceReferences =>

  /** Onboards the daml user associated with the given wallet app user reference
    * onto the given validator, and waits until the wallet is usable for that user
    */
  def onboardWalletUser(
      test: BaseTest,
      walletAppClient: WalletAppClientReference,
      validator: ValidatorAppReference,
  ): PartyId = {
    val damlUser = walletAppClient.config.damlUser

    test.clue(s"Onboard $damlUser on ${validator.name}") {
      val party = validator.onboardUser(damlUser)
      // The wallet is not immediately usable by the onboarded user -
      // the wallet app backend has to ingest the wallet install contract first.
      eventually() {
        walletAppClient.userStatus().userOnboarded shouldBe true
      }
      party
    }
  }

  def onboardAliceAndBob(test: BaseTest)(implicit
      env: CoinTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val alice = onboardWalletUser(test, aliceWallet, aliceValidator)
    val bob = onboardWalletUser(test, bobWallet, bobValidator)
    (alice, bob)
  }

  def p2pTransfer(
      senderWallet: WalletAppClientReference,
      receiverWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      senderTransferFeeRatio: BigDecimal = 1.0,
  ) = {
    val expiration = Primitive.Timestamp
      .discardNanos(Instant.now().plus(1, ChronoUnit.MINUTES))
      .getOrElse(fail("Failed to convert timestamp"))
    val transferOfferId =
      senderWallet.createTransferOffer(
        receiver,
        amount,
        "test transfer",
        expiration,
        senderTransferFeeRatio,
      )
    eventually() {
      receiverWallet.listTransferOffers() should have size 1
    }
    receiverWallet.acceptTransferOffer(transferOfferId)
  }

  def createSelfPaymentRequest(
      test: BaseTest,
      remoteParticipant: CoinRemoteParticipantReference,
      userParty: PartyId,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): (
      testWalletCodegen.TestDeliveryOffer.ContractId,
      walletCodegen.AppPaymentRequest.ContractId,
      walletCodegen.AppPaymentRequest,
  ) = {
    val referenceId = test.clue(s"Create test delivery offer for $userParty") {
      val result = LedgerApiUtils.submitWithResult(
        remoteParticipant,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = new testWalletCodegen.TestDeliveryOffer(
          scan.getSvcPartyId().toProtoPrimitive,
          userParty.toProtoPrimitive,
          "description",
        ).create,
      )
      testWalletCodegen.TestDeliveryOffer.COMPANION.toContractId(result.contractId)
    }

    val (reqCid, reqC) = test.clue(s"Create payment request for $userParty to self") {
      val reqC = new walletCodegen.AppPaymentRequest(
        userParty.toProtoPrimitive,
        Seq(
          new walletCodegen.ReceiverQuantity(
            userParty.toProtoPrimitive,
            new walletCodegen.PaymentQuantity(
              BigDecimal(10).bigDecimal.setScale(10),
              walletCodegen.Currency.CC,
            ),
          )
        ).asJava,
        userParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        java.time.Instant.now().plus(1, ChronoUnit.MINUTES),
        new RelTime(60 * 1000000),
        referenceId.toInterface(walletCodegen.DeliveryOffer.INTERFACE),
      )
      val result = LedgerApiUtils.submitWithResult(
        remoteParticipant,
        actAs = Seq(userParty),
        readAs = Seq.empty,
        update = reqC.create,
      )
      val cid = walletCodegen.AppPaymentRequest.COMPANION.toContractId(result.contractId)
      (cid, reqC)
    }

    (referenceId, reqCid, reqC)
  }

}
