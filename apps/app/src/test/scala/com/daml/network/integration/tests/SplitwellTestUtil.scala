package com.daml.network.util

import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.console.{
  CNParticipantClientReference,
  SplitwellAppClientReference,
  WalletAppClientReference,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

trait SplitwellTestUtil extends CNNodeTestCommon with WalletTestUtil with TimeTestUtil {
  protected def splitwellUpgradeAlias = DomainAlias.tryCreate("splitwellUpgrade")
  protected def splitwellAlias = DomainAlias.tryCreate("splitwell")
  protected def connectSplitwellUpgradeDomain(
      participant: CNParticipantClientReference
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val upgradeConfig =
      providerSplitwellBackend.participantClient.domains.config(splitwellUpgradeAlias).value

    import com.daml.nonempty.+-:
    val url = inside(upgradeConfig.sequencerConnection) {
      case GrpcSequencerConnection(topEndpoint +-: _, _, _) =>
        topEndpoint.toURI(false).toString
    }

    participant.domains.connect(splitwellUpgradeAlias, url)
  }

  protected def disconnectSplitwellUpgradeDomain(participant: CNParticipantClientReference) =
    participant.domains.disconnect(splitwellUpgradeAlias)

  protected def createSplitwellInstalls(splitwell: SplitwellAppClientReference, party: PartyId) = {
    actAndCheck("Creating splitwell requests", splitwell.createInstallRequests())(
      "Wait for splitwell installs",
      requests => {
        splitwell.listSplitwellInstalls().keys shouldBe requests.keys
        splitwell.ledgerApi.ledger_api_extensions.acs
          .filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(
            party
          ) should have size requests.size.toLong
      },
    )
  }

  def initSplitwellTest()(implicit
      env: CNNodeTestConsoleEnvironment
  ) = clue("setup splitwell users and contracts") {

    val group = "group1"
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
        createSplitwellInstalls(splitwell, party)
      }
    }

    actAndCheck("create 'group1'", aliceSplitwell.requestGroup(group))(
      "Alice sees 'group1'",
      _ => aliceSplitwell.listGroups() should have size 1,
    )

    val invite = clue("create a generic invite for 'group1'") {
      // Wait for the group contract to be visible to Alice's Ledger API
      aliceSplitwell.ledgerApi.ledger_api_extensions.acs
        .awaitJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)
      aliceSplitwell.createGroupInvite(
        group
      )
    }

    actAndCheck("bob asks to join 'group1'", bobSplitwell.acceptInvite(invite))(
      "Alice sees the accepted invite",
      _ => aliceSplitwell.listAcceptedGroupInvites(group) should not be empty,
    )

    actAndCheck(
      "bob joins 'group1'",
      inside(aliceSplitwell.listAcceptedGroupInvites(group)) { case Seq(accepted) =>
        aliceSplitwell.joinGroup(accepted.contractId)
      },
    )(
      "bob is in 'group1'",
      _ => {
        bobSplitwell.listGroups() should have size 1
        aliceSplitwell.listAcceptedGroupInvites(group) should be(empty)
      },
    )

    val key = GrpcSplitwellAppClient.GroupKey(
      aliceUserParty,
      aliceSplitwell.getProviderPartyId(),
      group,
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
