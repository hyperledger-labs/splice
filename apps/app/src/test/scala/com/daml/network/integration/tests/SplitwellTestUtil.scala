package com.daml.network.util

import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.splitwell.admin.api.client.commands.HttpSplitwellAppClient
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
      splitwellBackend.participantClient.domains.config(splitwellUpgradeAlias).value

    import com.daml.nonempty.+-:
    val url = inside(upgradeConfig.sequencerConnections.connections.forgetNE) {
      case Seq(GrpcSequencerConnection(topEndpoint +-: _, _, _, _)) =>
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
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
    val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    // The provider's wallet is auto-onboarded, so we just need to wait for it to be ready
    waitForWalletUser(splitwellWalletClient)

    val splitwellProviderParty = splitwellBackend.getProviderPartyId()

    clue("setup install contracts") {
      Seq(
        (aliceSplitwellClient, aliceUserParty),
        (bobSplitwellClient, bobUserParty),
        (charlieSplitwellClient, charlieUserParty),
      ).foreach { case (splitwell, party) =>
        createSplitwellInstalls(splitwell, party)
      }
    }

    actAndCheck("create 'group1'", aliceSplitwellClient.requestGroup(group))(
      "Alice sees 'group1'",
      _ => aliceSplitwellClient.listGroups() should have size 1,
    )

    val invite = clue("create a generic invite for 'group1'") {
      // Wait for the group contract to be visible to Alice's Ledger API
      aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
        .awaitJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)
      aliceSplitwellClient.createGroupInvite(
        group
      )
    }

    actAndCheck("bob asks to join 'group1'", bobSplitwellClient.acceptInvite(invite))(
      "Alice sees the accepted invite",
      _ => aliceSplitwellClient.listAcceptedGroupInvites(group) should not be empty,
    )

    actAndCheck(
      "bob joins 'group1'",
      inside(aliceSplitwellClient.listAcceptedGroupInvites(group)) { case Seq(accepted) =>
        aliceSplitwellClient.joinGroup(accepted.contractId)
      },
    )(
      "bob is in 'group1'",
      _ => {
        bobSplitwellClient.listGroups() should have size 1
        aliceSplitwellClient.listAcceptedGroupInvites(group) should be(empty)
      },
    )

    val key = HttpSplitwellAppClient.GroupKey(
      group,
      aliceUserParty,
    )

    clue("grant featured app right to splitwell provider") {
      grantFeaturedAppRight(splitwellWalletClient)
    }

    (aliceUserParty, bobUserParty, charlieUserParty, splitwellProviderParty, key, invite)
  }

  def splitwellTransfer(
      senderSplitwell: SplitwellAppClientReference,
      senderWallet: WalletAppClientReference,
      receiver: PartyId,
      amount: BigDecimal,
      key: HttpSplitwellAppClient.GroupKey,
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
