package com.daml.network.integration.tests
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.splitwise.admin.api.client.commands.GrpcSplitwiseAppClient
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}

class SplitwiseIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  private val darPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withConnectedDomains()
      .withAllocatedValidatorUsers()
      .withSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwise" should {
    "support provider-hosted mode" in { implicit env =>
      // Onboard users
      val aliceUserParty = aliceValidator.onboardUser(aliceSplitwise.config.damlUser)
      val charlieUserParty = aliceValidator.onboardUser(charlieSplitwise.config.damlUser)
      val bobUserParty = bobValidator.onboardUser(bobSplitwise.config.damlUser)

      // Setup install contracts
      val providerParty = providerSplitwiseBackend.getProviderPartyId()
      Seq(aliceSplitwise, bobSplitwise, charlieSplitwise).foreach { splitwise =>
        val proposal = splitwise.createInstallProposal()
        providerSplitwiseBackend.remoteParticipant.ledger_api.acs
          .await(providerParty, splitwiseCodegen.SplitwiseInstallProposal)
        providerSplitwiseBackend.acceptInstallProposal(proposal)
      }

      aliceSplitwise.createGroup("group1")
      aliceSplitwise.createGroupInvite(
        "group1",
        Seq(bobUserParty, charlieUserParty),
      )

      bobValidator.remoteParticipant.ledger_api.acs
        .await(bobUserParty, splitwiseCodegen.GroupInvite)
      inside(bobSplitwise.listGroupInvites()) { case Seq(invite) =>
        bobSplitwise.acceptInvite(invite.contractId)
      }
      aliceValidator.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, splitwiseCodegen.AcceptedGroupInvite)

      inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwise.joinGroup(accepted.contractId)
      }

      val key = GrpcSplitwiseAppClient.GroupKey(
        aliceUserParty,
        aliceSplitwise.getProviderPartyId(),
        "group1",
      )

      aliceSplitwise.enterPayment(
        key,
        42.0,
        "payment",
      )
      bobSplitwise.initiateMultiTransfer(
        key,
        Seq(walletCodegen.ReceiverQuantity(aliceUserParty.toPrim, 10.0)),
      )
      eventually()(bobRemoteWallet.listAppMultiPaymentRequests() should not be empty)
      val acceptedPayment = inside(bobRemoteWallet.listAppMultiPaymentRequests()) {
        case Seq(request) =>
          bobRemoteWallet.tap(20)
          bobRemoteWallet.acceptAppMultiPaymentRequest(request.contractId)
      }
      bobSplitwise.completeMultiTransfer(
        key,
        acceptedPayment,
      )
      eventually() {
        bobSplitwise.listBalanceUpdates(key) should have size 2
      }
      bobSplitwise.listBalances(key) shouldBe Seq(aliceUserParty -> -11).toMap

      aliceSplitwise.listBalanceUpdates(key) should have size 2
      aliceSplitwise.listBalances(key) shouldBe Seq(bobUserParty -> 11).toMap

      inside(charlieSplitwise.listGroupInvites()) { case Seq(invite) =>
        charlieSplitwise.acceptInvite(invite.contractId)
      }
      eventually {
        aliceSplitwise.listAcceptedGroupInvites("group1") should have size 1
      }
      inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
        aliceSplitwise.joinGroup(accepted.contractId)
      }

      splitwiseValidator.remoteParticipant.ledger_api.acs
        .await(providerSplitwiseBackend.getProviderPartyId(), splitwiseCodegen.Group)

      charlieSplitwise.listBalances(key) shouldBe Map.empty
      charlieSplitwise.enterPayment(key, 33.0, "payment")
      eventually() {
        charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 11, bobUserParty -> 11)
      }

      eventually()(aliceSplitwise.listBalanceUpdates(key) should have size 3)
      aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 11, charlieUserParty -> -11)

      aliceSplitwise.net(
        key,
        Map(
          aliceUserParty -> Map(bobUserParty -> -11, charlieUserParty -> 11),
          bobUserParty -> Map(aliceUserParty -> 11, charlieUserParty -> -11),
          charlieUserParty -> Map(aliceUserParty -> -11, bobUserParty -> 11),
        ),
      )
      eventually() {
        aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
      }
      eventually() {
        bobSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -22)
      }
      eventually() {
        charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 22)
      }
    }

    "return the primary party of the user" in { implicit env =>
      val users = providerSplitwiseBackend.remoteParticipant.ledger_api.users
        .list(filterUser = providerSplitwiseBackend.config.providerUser)
      inside(users.users) { case Seq(user) =>
        Some(providerSplitwiseBackend.getProviderPartyId().toLf) shouldBe user.primaryParty
      }
    }
  }
}
