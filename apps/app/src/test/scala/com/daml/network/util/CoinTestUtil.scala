package com.daml.network.util

import com.daml.network.console.{RemoteWalletAppReference, ValidatorAppReference}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.sequencing.SequencerTestUtils.eventually
import com.digitalasset.canton.topology.PartyId
import org.scalatest.matchers.should.Matchers.*

// TODO(M1-92 - Tech Debt): This could be reused in more places and extended
trait CoinTestUtil { this: CommonCoinAppInstanceReferences =>

  /** Onboards the daml user associated with the given remote wallet app reference
    * onto the given validator, and waits until the wallet is usable for that user
    */
  def onboardWalletUser(
      test: BaseTest,
      remoteWallet: RemoteWalletAppReference,
      validator: ValidatorAppReference,
  ): PartyId = {
    val damlUser = remoteWallet.config.damlUser

    test.clue(s"Onboard $damlUser on ${validator.name}") {
      val party = validator.onboardUser(damlUser)
      // The wallet is not immediately usable by the onboarded user -
      // the wallet app backend has to ingest the wallet install contract first.
      eventually() {
        remoteWallet.userStatus().userOnboarded shouldBe true
      }
      party
    }
  }

  /** Setup Alice and Bob's validators, parties, and two payment channels (back-and-forth) between their parties. */
  def setupAliceAndBobAndChannel(test: BaseTest)(implicit
      env: CoinTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    val aliceUserParty = onboardWalletUser(test, aliceRemoteWallet, aliceValidator)
    val bobUserParty = onboardWalletUser(test, bobRemoteWallet, bobValidator)

    test.clue("Setup payment channel between alice and bob") {
      val proposalId =
        aliceRemoteWallet.proposePaymentChannel(bobUserParty, senderTransferFeeRatio = 0.5)
      // Bob monitors proposals and accepts the one
      eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
      bobRemoteWallet.acceptPaymentChannelProposal(proposalId)
      eventually()(aliceRemoteWallet.listPaymentChannels() should have size 1)
    }

    test.clue("Setup payment channel between bob and alice") {
      val bobProposalId =
        bobRemoteWallet.proposePaymentChannel(aliceUserParty, senderTransferFeeRatio = 0.5)
      eventually()(aliceRemoteWallet.listPaymentChannelProposals() should have size 1)
      aliceRemoteWallet.acceptPaymentChannelProposal(bobProposalId)
      eventually()(bobRemoteWallet.listPaymentChannels() should have size 2)
    }

    (aliceUserParty, bobUserParty)
  }
}
