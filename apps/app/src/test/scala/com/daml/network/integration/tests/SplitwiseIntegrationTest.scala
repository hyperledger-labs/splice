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
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.console.RemoteSplitwiseAppReference
import org.scalatest.concurrent.Eventually

class SplitwiseIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences
    with Eventually {

  private val darPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"
  // We reuse the provider’s splitwise as charlie’s splitwise
  private def charlieSplitwiseBackend(implicit env: CoinTestConsoleEnvironment) =
    providerSplitwiseBackend
  private def charlieSplitwise(implicit env: CoinTestConsoleEnvironment) = providerSplitwise
  private def charlieSplitwiseSelfHosted(implicit env: CoinTestConsoleEnvironment) =
    providerSplitwiseSelfHosted

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withSetup(implicit env => {
        import env._
        participants.all.foreach(_.domains.connect_local(da))
        aliceSplitwiseBackend.remoteParticipant.dars.upload(darPath)
        bobSplitwiseBackend.remoteParticipant.dars.upload(darPath)
        providerSplitwiseBackend.remoteParticipant.dars.upload(darPath)
      })

  def test(
      aliceSplitwise: RemoteSplitwiseAppReference,
      aliceUserParty: PartyId,
      aliceProviderParty: PartyId,
      bobSplitwise: RemoteSplitwiseAppReference,
      bobUserParty: PartyId,
      bobProviderParty: PartyId,
      charlieSplitwise: RemoteSplitwiseAppReference,
      charlieUserParty: PartyId,
      charlieProviderParty: PartyId,
  )(implicit env: CoinTestConsoleEnvironment) = {
    import env._

    aliceSplitwise.createGroup(aliceProviderParty, "group1")
    aliceSplitwise.createGroupInvite(
      aliceProviderParty,
      "group1",
      Seq(bobUserParty, charlieUserParty),
    )

    bobValidator.remoteParticipant.ledger_api.acs
      .await(bobUserParty, splitwiseCodegen.GroupInvite)
    inside(bobSplitwise.listGroupInvites()) { case Seq(invite) =>
      bobSplitwise.acceptInvite(bobProviderParty, invite.contractId)
    }
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceUserParty, splitwiseCodegen.AcceptedGroupInvite)

    inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
      aliceSplitwise.joinGroup(aliceProviderParty, accepted.contractId)
    }

    val key = GrpcSplitwiseAppClient.GroupKey(aliceUserParty, aliceProviderParty, "group1")

    aliceSplitwise.enterPayment(
      aliceProviderParty,
      key,
      42.0,
      "payment",
    )
    bobSplitwise.initiateTransfer(
      bobProviderParty,
      key,
      aliceUserParty,
      10.0,
    )
    val acceptedPayment = inside(bobRemoteWallet.listAppPaymentRequests()) { case Seq(request) =>
      bobRemoteWallet.tap(20)
      bobRemoteWallet.acceptAppPaymentRequest(request.contractId)
    }
    bobSplitwise.completeTransfer(
      bobProviderParty,
      key,
      acceptedPayment,
    )
    eventually {
      bobSplitwise.listBalanceUpdates(key) should have size 2
    }
    bobSplitwise.listBalances(key) shouldBe Seq(aliceUserParty -> -11).toMap

    aliceSplitwise.listBalanceUpdates(key) should have size 2
    aliceSplitwise.listBalances(key) shouldBe Seq(bobUserParty -> 11).toMap

    inside(charlieSplitwise.listGroupInvites()) { case Seq(invite) =>
      charlieSplitwise.acceptInvite(charlieProviderParty, invite.contractId)
    }
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceUserParty, splitwiseCodegen.AcceptedGroupInvite)
    inside(aliceSplitwise.listAcceptedGroupInvites("group1")) { case Seq(accepted) =>
      aliceSplitwise.joinGroup(aliceProviderParty, accepted.contractId)
    }

    splitwiseValidator.remoteParticipant.ledger_api.acs
      .await(charlieProviderParty, splitwiseCodegen.Group)

    charlieSplitwise.listBalances(key) shouldBe Map.empty
    charlieSplitwise.enterPayment(charlieProviderParty, key, 33.0, "payment")
    charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 11, bobUserParty -> 11)

    utils.retry_until_true(aliceSplitwise.listBalanceUpdates(key).size == 3)
    aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 11, charlieUserParty -> -11)

    aliceSplitwise.net(
      aliceProviderParty,
      key,
      Map(
        aliceUserParty -> Map(bobUserParty -> -11, charlieUserParty -> 11),
        bobUserParty -> Map(aliceUserParty -> 11, charlieUserParty -> -11),
        charlieUserParty -> Map(aliceUserParty -> -11, bobUserParty -> 11),
      ),
    )
    // The participants we need to synchronize with are different in self-hosted & provider-hosted mode so
    // we just eventually everything instead of trying to be clever.
    eventually {
      aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
    }
    eventually {
      bobSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -22)
    }
    eventually {
      charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 22)
    }
  }

  "splitwise" should {
    "support self-hosted mode" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobDamlUser = bobRemoteWallet.config.damlUser
      bobWallet.initialize(bobValidatorParty)
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

      // Setup install contracts for self-hosted usage
      val aliceProviderParty = aliceUserParty
      val aliceInstallProposal = aliceSplitwise.createInstallProposal(aliceUserParty)
      aliceSplitwiseBackend.acceptInstallProposal(aliceInstallProposal)
      val bobProviderParty = bobUserParty
      val bobInstallProposal = bobSplitwise.createInstallProposal(bobUserParty)
      bobSplitwiseBackend.acceptInstallProposal(bobInstallProposal)

      // We reuse the provider as charlie here to avoid setting up another splitwise instance.
      val charlieUserParty = splitwiseValidator.initialize()
      val charlieProviderParty = charlieUserParty
      val charlieInstallProposal = providerSplitwise.createInstallProposal(charlieUserParty)
      providerSplitwiseBackend.acceptInstallProposal(charlieInstallProposal)

      test(
        aliceSplitwiseSelfHosted,
        aliceUserParty,
        aliceProviderParty,
        bobSplitwiseSelfHosted,
        bobUserParty,
        bobProviderParty,
        charlieSplitwiseSelfHosted,
        charlieUserParty,
        charlieProviderParty,
      )
    }

    "support provider-hosted mode" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobDamlUser = bobRemoteWallet.config.damlUser
      bobWallet.initialize(bobValidatorParty)
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

      // Setup install contracts for provider-hosted mode usage
      val providerParty = splitwiseValidator.initialize()
      val aliceInstallProposal = aliceSplitwise.createInstallProposal(providerParty)
      providerSplitwiseBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, splitwiseCodegen.SplitwiseInstallProposal)
      providerSplitwiseBackend.acceptInstallProposal(aliceInstallProposal)
      val bobInstallProposal = bobSplitwise.createInstallProposal(providerParty)
      providerSplitwiseBackend.remoteParticipant.ledger_api.acs
        .await(providerParty, splitwiseCodegen.SplitwiseInstallProposal)
      providerSplitwiseBackend.acceptInstallProposal(bobInstallProposal)
      // We reuse the provider as charlie to avoid setting up another splitwise instance.
      val charlieUserParty = providerParty
      val charlieInstallProposal = providerSplitwise.createInstallProposal(charlieUserParty)
      providerSplitwiseBackend.acceptInstallProposal(charlieInstallProposal)

      test(
        aliceSplitwise,
        aliceUserParty,
        providerParty,
        bobSplitwise,
        bobUserParty,
        providerParty,
        charlieSplitwise,
        charlieUserParty,
        providerParty,
      )
    }

    "return the primary party of the user" in { implicit env =>
      val providerParty = splitwiseValidator.initialize()
      providerSplitwiseBackend.getProviderPartyId() shouldBe providerParty
    }
  }
}
