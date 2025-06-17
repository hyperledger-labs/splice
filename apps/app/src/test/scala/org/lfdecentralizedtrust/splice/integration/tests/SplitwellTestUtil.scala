package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  TestCommon,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.{
  AcceptedAppPayment,
  ReceiverAmuletAmount,
}
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  SplitwellAppClientReference,
  WalletAppClientReference,
}
import com.daml.nonempty.*
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

import scala.concurrent.duration.DurationInt

trait SplitwellTestUtil extends TestCommon with WalletTestUtil with TimeTestUtil {
  protected def splitwellUpgradeAlias = SynchronizerAlias.tryCreate("splitwellUpgrade")
  protected def splitwellAlias = SynchronizerAlias.tryCreate("splitwell")
  protected def connectSplitwellUpgradeDomain(
      participant: ParticipantClientReference,
      ensurePartyIsOnNewDomain: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val upgradeConfig =
      splitwellBackend.participantClient.synchronizers.config(splitwellUpgradeAlias).value

    val url = inside(upgradeConfig.sequencerConnections.connections.forgetNE) {
      case Seq(GrpcSequencerConnection(topEndpoint +-: _, _, _, _, _)) =>
        topEndpoint.toURI(false).toString
    }

    // This can be a bit slow since it first pushes all vetting transactions before pushing
    // the PartyToParticipant transaction.
    actAndCheck(
      timeUntilSuccess = 40.seconds
    )(
      "Connect splitwell upgrade domain",
      participant.synchronizers.connect(splitwellUpgradeAlias, url),
    )(
      s"Wait for splitwell upgrade domain to be connected for party $ensurePartyIsOnNewDomain",
      _ => {
        splitwellBackend
          .getConnectedDomains(ensurePartyIsOnNewDomain)
          .map(_.uid.identifier.str) should contain("splitwellUpgrade")
      },
    )
  }

  protected def disconnectSplitwellUpgradeDomain(participant: ParticipantClientReference) =
    participant.synchronizers.disconnect(splitwellUpgradeAlias)

  protected def createSplitwellInstalls(splitwell: SplitwellAppClientReference, party: PartyId) = {
    actAndCheck(
      "Creating splitwell requests",
      // Eventually, because the install might fail while domains are being reconnected
      eventuallySucceeds() {
        splitwell.createInstallRequests()
      },
    )(
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
      env: SpliceTestConsoleEnvironment
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

    // Wait for the group contract to be visible to Alice's Ledger API
    aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
      .awaitJava(splitwellCodegen.Group.COMPANION)(aliceUserParty)

    val (_, invite) = actAndCheck(
      "create a generic invite for 'group1'",
      aliceSplitwellClient.createGroupInvite(
        group
      ),
    )(
      "alice observes the invite",
      _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
    )

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
  ): AcceptedAppPayment.ContractId =
    splitwellTransfer(
      senderSplitwell,
      senderWallet,
      key,
      Seq(
        new walletCodegen.ReceiverAmuletAmount(
          receiver.toProtoPrimitive,
          amount.bigDecimal,
        )
      ),
    )

  def splitwellTransfer(
      senderSplitwell: SplitwellAppClientReference,
      senderWallet: WalletAppClientReference,
      key: HttpSplitwellAppClient.GroupKey,
      receiverAmounts: Seq[ReceiverAmuletAmount],
  ): AcceptedAppPayment.ContractId = {
    senderSplitwell.initiateTransfer(key, receiverAmounts)
    val request = eventually()(getSingleAppPaymentRequest(senderWallet))
    val (cid, _) = actAndCheck(
      "Sender accepts payment request",
      senderWallet.acceptAppPaymentRequest(request.contractId),
    )(
      "Splitwell completes payment",
      _ => senderWallet.listAcceptedAppPayments() should be(empty),
    )
    cid
  }

  protected def getSingleAppPaymentRequest(
      walletClient: WalletAppClientReference
  ) = {
    walletClient
      .listAppPaymentRequests()
      .loneElement
  }

}
