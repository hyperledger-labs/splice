package com.daml.network.integration.tests
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.splitwise.admin.api.client.commands.SplitwiseCommands
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CN.{Splitwise => splitCodegen}

class SplitwiseIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  private val darPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"
  // We reuse the provider’s splitwise as charlie’s splitwise
  private def charlieSplitwise(implicit env: CoinTestConsoleEnvironment) = providerSplitwise

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withSetup(implicit env => {
        import env._
        participants.all.foreach(_.domains.connect_local(da))
        aliceSplitwise.remoteParticipant.dars.upload(darPath)
        bobSplitwise.remoteParticipant.dars.upload(darPath)
        providerSplitwise.remoteParticipant.dars.upload(darPath)
      })

  def test(
      aliceUserParty: PartyId,
      aliceProviderParty: PartyId,
      bobUserParty: PartyId,
      bobProviderParty: PartyId,
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
      .await(bobUserParty, splitCodegen.GroupInvite)
    inside(bobSplitwise.listGroupInvites()) { case Seq(invite) =>
      bobSplitwise.acceptInvite(bobProviderParty, invite.contractId)
    }
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceUserParty, splitCodegen.AcceptedGroupInvite)
    inside(aliceSplitwise.listAcceptedGroupInvites(aliceProviderParty, "group1")) {
      case Seq(accepted) =>
        aliceSplitwise.joinGroup(aliceProviderParty, accepted.contractId)
    }

    val key = SplitwiseCommands.GroupKey(aliceUserParty, aliceProviderParty, "group1")

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
    val acceptedPayment = inside(bobWallet.listAppPaymentRequests()) { case Seq(request) =>
      val coin = bobWallet.tap(20)
      bobWallet.acceptAppPaymentRequest(
        request.contractId,
        coin,
      )
    }
    bobSplitwise.completeTransfer(
      bobProviderParty,
      key,
      acceptedPayment,
    )

    bobSplitwise.listBalanceUpdates(key) should have size 2
    bobSplitwise.listBalances(key) shouldBe Seq(aliceUserParty -> -11).toMap

    aliceSplitwise.listBalanceUpdates(key) should have size 2
    aliceSplitwise.listBalances(key) shouldBe Seq(bobUserParty -> 11).toMap

    inside(charlieSplitwise.listGroupInvites()) { case Seq(invite) =>
      charlieSplitwise.acceptInvite(charlieProviderParty, invite.contractId)
    }
    aliceValidator.remoteParticipant.ledger_api.acs
      .await(aliceUserParty, splitCodegen.AcceptedGroupInvite)
    inside(aliceSplitwise.listAcceptedGroupInvites(aliceProviderParty, "group1")) {
      case Seq(accepted) =>
        aliceSplitwise.joinGroup(aliceProviderParty, accepted.contractId)
    }
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
    aliceSplitwise.listBalances(key) shouldBe Map(bobUserParty -> 0, charlieUserParty -> 0)
    bobSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, charlieUserParty -> -22)
    charlieSplitwise.listBalances(key) shouldBe Map(aliceUserParty -> 0, bobUserParty -> 22)
  }

  "splitwise" should {
    "support self-hosted mode" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)
      aliceSplitwise.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
      bobWallet.initialize(bobValidatorParty)
      bobSplitwise.initialize(bobValidatorParty)

      // Setup install contracts for self-hosted usage
      val aliceProviderParty = aliceUserParty
      val aliceInstallProposal = aliceSplitwise.createInstallProposal(aliceUserParty)
      aliceSplitwise.acceptInstallProposal(aliceInstallProposal)
      val bobProviderParty = bobUserParty
      val bobInstallProposal = bobSplitwise.createInstallProposal(bobUserParty)
      bobSplitwise.acceptInstallProposal(bobInstallProposal)

      // We reuse the provider as charlie here to avoid setting up another splitwise instance.
      val charlieUserParty = splitwiseValidator.initialize()
      charlieSplitwise.initialize(charlieUserParty)
      val charlieProviderParty = charlieUserParty
      val charlieInstallProposal = providerSplitwise.createInstallProposal(charlieUserParty)
      providerSplitwise.acceptInstallProposal(charlieInstallProposal)

      test(
        aliceUserParty,
        aliceProviderParty,
        bobUserParty,
        bobProviderParty,
        charlieUserParty,
        charlieProviderParty,
      )
    }
    "support provider-hosted mode" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)
      aliceSplitwise.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
      bobWallet.initialize(bobValidatorParty)
      bobSplitwise.initialize(bobValidatorParty)

      // Setup install contracts for provider-hosted mode usage
      val providerParty = splitwiseValidator.initialize()
      charlieSplitwise.initialize(providerParty)
      val aliceInstallProposal = aliceSplitwise.createInstallProposal(providerParty)
      providerSplitwise.remoteParticipant.ledger_api.acs
        .await(providerParty, splitCodegen.SplitwiseInstallProposal)
      providerSplitwise.acceptInstallProposal(aliceInstallProposal)
      val bobInstallProposal = bobSplitwise.createInstallProposal(providerParty)
      providerSplitwise.remoteParticipant.ledger_api.acs
        .await(providerParty, splitCodegen.SplitwiseInstallProposal)
      providerSplitwise.acceptInstallProposal(bobInstallProposal)
      // We reuse the provider as charlie to avoid setting up another splitwise instance.
      val charlieUserParty = providerParty
      val charlieInstallProposal = providerSplitwise.createInstallProposal(charlieUserParty)
      providerSplitwise.acceptInstallProposal(charlieInstallProposal)

      test(
        aliceUserParty,
        providerParty,
        bobUserParty,
        providerParty,
        charlieUserParty,
        providerParty,
      )
    }
  }
}
