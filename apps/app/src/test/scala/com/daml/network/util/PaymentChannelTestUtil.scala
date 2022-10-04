package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.sequencing.SequencerTestUtils.eventually
import com.digitalasset.canton.topology.PartyId
import org.scalatest.matchers.should.Matchers._

// TODO(M1-92 - Tech Debt): This could be reused in more places and extended
trait PaymentChannelTestUtil { this: CommonCoinAppInstanceReferences =>

  /** Setup Alice and Bob's validators, parties, and a payment channel between their parties. */
  def setupAliceAndBobAndChannel(implicit
      env: CoinTestConsoleEnvironment
  ): (PartyId, PartyId) = {
    // Onboard alice on her self-hosted validator
    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

    // Onboard bob on his self-hosted validator
    val bobDamlUser = bobRemoteWallet.config.damlUser
    val bobUserParty = bobValidator.onboardUser(bobDamlUser)

    // ensure the participants see the CoinRules
    val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
    // Bob monitors proposals and accepts the one
    eventually()(bobRemoteWallet.listPaymentChannelProposals() should have size 1)
    bobRemoteWallet.acceptPaymentChannelProposal(proposalId)

    eventually()(aliceRemoteWallet.listPaymentChannels() should have size 1)
    (aliceUserParty, bobUserParty)
  }
}
