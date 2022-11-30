package com.daml.network.util

import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  CoinRemoteParticipantReference,
  ValidatorAppReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.sequencing.SequencerTestUtils.eventually
import com.digitalasset.canton.topology.PartyId
import org.scalatest.matchers.should.Matchers.*

import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*

// TODO(M1-92 - Tech Debt): This could be reused in more places and extended
trait CoinTestUtil { this: CommonCoinAppInstanceReferences =>

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

  /** Setup Alice and Bob's validators, parties, and two payment channels (back-and-forth) between their parties. */
  def setupAliceAndBobAndChannel(test: BaseTest)(implicit
      env: CoinTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val aliceUserParty = onboardWalletUser(test, aliceWallet, aliceValidator)
    val bobUserParty = onboardWalletUser(test, bobWallet, bobValidator)

    test.clue("Setup payment channel between alice and bob") {
      val proposalId =
        aliceWallet.proposePaymentChannel(bobUserParty, senderTransferFeeRatio = 0.5)
      // Bob monitors proposals and accepts the one
      eventually()(bobWallet.listPaymentChannelProposals() should have size 1)
      bobWallet.acceptPaymentChannelProposal(proposalId)
      eventually()(aliceWallet.listPaymentChannels() should have size 1)
    }

    test.clue("Setup payment channel between bob and alice") {
      val bobProposalId =
        bobWallet.proposePaymentChannel(aliceUserParty, senderTransferFeeRatio = 0.5)
      eventually()(aliceWallet.listPaymentChannelProposals() should have size 1)
      aliceWallet.acceptPaymentChannelProposal(bobProposalId)
      eventually()(bobWallet.listPaymentChannels() should have size 2)
    }

    (aliceUserParty, bobUserParty)
  }

  def createSelfPaymentRequest(
      test: BaseTest,
      remoteParticipant: CoinRemoteParticipantReference,
      userParty: PartyId,
  )(implicit
      env: CoinTestConsoleEnvironment
  ): (testWalletCodegen.TestDeliveryOffer.ContractId, walletCodegen.AppPaymentRequest) = {
    val referenceId = test.clue(s"Create test delivery offer for $userParty") {
      remoteParticipant.ledger_api.commands.submitJava(
        Seq(userParty),
        optTimeout = None,
        commands = new testWalletCodegen.TestDeliveryOffer(
          scan.getSvcPartyId().toProtoPrimitive,
          userParty.toProtoPrimitive,
          "description",
        ).create.commands.asScala.toSeq,
      )
      remoteParticipant.ledger_api.acs
        .awaitJava(testWalletCodegen.TestDeliveryOffer.COMPANION)(userParty)
        .id
    }

    val reqC = test.clue(s"Create payment request for $userParty to self") {
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
      remoteParticipant.ledger_api.commands.submitJava(
        actAs = Seq(userParty),
        optTimeout = None,
        commands = reqC.create.commands.asScala.toSeq,
      )
      reqC
    }

    (referenceId, reqC)
  }
}
