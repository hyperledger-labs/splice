package com.daml.network.util

import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.console.SplitwellAppClientReference
import com.daml.network.console.WalletAppClientReference
import com.digitalasset.canton.topology.PartyId

trait SplitwellTestUtil extends CNNodeTestCommon with WalletTestUtil with TimeTestUtil {
  def initSplitwellTest()(implicit
      env: CNNodeTestConsoleEnvironment
  ) = clue("setup splitwell users and contracts") {

    val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
    val charlieUserParty = onboardWalletUser(charlieWallet, aliceValidator)
    val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
    // The provider's wallet is auto-onboarded, so we just need to wait for it to be ready
    waitForWalletUser(splitwellProviderWallet)

    val splitwellProviderParty = providerSplitwellBackend.getProviderPartyId()

    clue("setup install contracts") {
      Seq(
        (aliceSplitwell, aliceUserParty),
        (bobSplitwell, bobUserParty),
        (charlieSplitwell, charlieUserParty),
      ).foreach { case (splitwell, party) =>
        splitwell.createInstallRequests()
        splitwell.ledgerApi.ledger_api_extensions.acs
          .awaitJava(splitwellCodegen.SplitwellInstall.COMPANION)(party)
      }
    }

    clue("create 'group1'") {
      aliceSplitwell.requestGroup("group1")
      eventually() {
        aliceSplitwell.listGroups() should have size 1
      }
    }
    val invite = clue("create a generic invite for 'group1'") {
      // Wait for the group contract to be visible to Alice's Ledger API
      aliceSplitwell.ledgerApi.ledger_api_extensions.acs
        .awaitJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)
      aliceSplitwell.createGroupInvite(
        "group1"
      )
    }

    clue("invite bob to 'group1'") {
      bobSplitwell.acceptInvite(invite)

      eventually() {
        aliceSplitwell.listAcceptedGroupInvites("group1") should not be empty
      }
      inside(aliceSplitwell.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwell.joinGroup(accepted.contractId)
      }
    }

    val key = GrpcSplitwellAppClient.GroupKey(
      aliceUserParty,
      aliceSplitwell.getProviderPartyId(),
      "group1",
    )

    clue("grant featured app right to splitwell provider") {
      grantFeaturedAppRight(splitwellProviderWallet)
    }

    (aliceUserParty, bobUserParty, charlieUserParty, splitwellProviderParty, key, invite)
  }

  def splitwellTransfer(
      senderSplitwell: SplitwellAppClientReference,
      senderWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      key: GrpcSplitwellAppClient.GroupKey,
  ) = {
    senderSplitwell.initiateTransfer(
      key,
      Seq(
        new walletCodegen.ReceiverCCAmount(
          receiver.toProtoPrimitive,
          amount.bigDecimal,
        )
      ),
    )
    eventually()(senderWallet.listAppPaymentRequests() should not be empty)
    inside(senderWallet.listAppPaymentRequests()) { case Seq(request) =>
      senderWallet.acceptAppPaymentRequest(request.appPaymentRequest.contractId)
    }

  }

}
