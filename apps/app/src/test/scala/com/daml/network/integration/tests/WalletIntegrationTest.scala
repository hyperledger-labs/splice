package com.daml.network.integration.tests

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding
import com.daml.network.console.WalletAppReference
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.codegen.CC.{Coin => coinCodegen}
import com.daml.network.codegen.CC.CoinRules.CoinRules
import com.daml.network.codegen.CN.Scripts.{TestWallet => testWalletCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.daml.network.codegen.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient.{Balance, ListResponse}

import java.time.temporal.ChronoUnit
import scala.concurrent.duration._

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withSetup(env => {
        import env._
        participants.all.foreach(_.domains.connect_local(da))
      })

  "A wallet" should {
    "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
      implicit env =>
        val aliceValidatorParty = aliceValidator.initialize()
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        aliceWallet.initialize(aliceValidatorParty)
        val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

        // ensure wallet's participant sees the CoinRules
        aliceWallet.remoteParticipant.ledger_api.acs.await(aliceValidatorParty, CoinRules)
        aliceRemoteWallet.list() shouldBe ListResponse(Seq(), Seq())

        val exactly = (x: BigDecimal) => (x, x)
        val ranges1 = Seq(exactly(50))
        aliceRemoteWallet.tap(50)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)

        val ranges2 = Seq(exactly(50), exactly(60))
        aliceRemoteWallet.tap(60)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)
        checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)

        checkBalance(aliceRemoteWallet.balance(), 0, exactly(110), exactly(0), exactly(0))

        nextRound()
        lockCoins(aliceUserParty, 10) // Lock away 10 coins in a payment request to the same party

        checkBalance(
          aliceRemoteWallet.balance(),
          1,
          (99, 100),
          exactly(10),
          (0.000004, 0.000005),
        )

        nextRound()

        checkBalance(
          aliceRemoteWallet.balance(),
          2,
          (99, 100),
          (9, 10),
          (0.00001, 0.00002),
        )
    }

    "list all coins, including locked coins, with additional position details" in { implicit env =>
      import env._

      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      aliceWallet.remoteParticipant.ledger_api.acs.await(aliceValidatorParty, CoinRules)

      aliceRemoteWallet.tap(50)

      aliceRemoteWallet.list().coins.length shouldBe 1
      aliceRemoteWallet.list().lockedCoins.length shouldBe 0

      lockCoins(aliceUserParty, 25)

      aliceRemoteWallet.list().coins.length shouldBe 1
      utils.retry_until_true(aliceRemoteWallet.list().lockedCoins.length == 1)

      aliceRemoteWallet.list().coins.head.round shouldBe 0
      aliceRemoteWallet.list().coins.head.accruedHoldingFee shouldBe 0
      assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

      aliceRemoteWallet.list().lockedCoins.head.round shouldBe 0
      aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee shouldBe 0
      assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))

      nextRound()

      aliceRemoteWallet.list().coins.head.round shouldBe 1
      assertInRange(aliceRemoteWallet.list().coins.head.accruedHoldingFee, (0.000004, 0.000005))
      assertInRange(aliceRemoteWallet.list().coins.head.effectiveQuantity, (24.0, 25.0))

      aliceRemoteWallet.list().lockedCoins.head.round shouldBe 1
      assertInRange(
        aliceRemoteWallet.list().lockedCoins.head.accruedHoldingFee,
        (0.000004, 0.000005),
      )
      assertInRange(aliceRemoteWallet.list().lockedCoins.head.effectiveQuantity, (24.0, 25.0))
    }

    "allow a user to create, list, and reject app payment requests" in { implicit env =>
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      // TODO(M1-90 Backlog): consider adding synchronization 'wait-for-participant-x' to this command
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Check that no payment requests exist
      aliceRemoteWallet.listAppPaymentRequests() shouldBe empty

      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          testWalletCodegen
            .TestReference(
              p = aliceUserParty.toPrim,
              description = "description",
            )
            .create
            .command
        ),
      )
      val referenceId =
        aliceWallet.remoteParticipant.ledger_api.acs
          .await(aliceUserParty, testWalletCodegen.TestReference)
          .contractId

      // Create a payment request to self.
      val reqC = walletCodegen.AppPaymentRequest(
        sender = aliceUserParty.toPrim,
        provider = aliceUserParty.toPrim,
        receiver = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        quantity = BigDecimal(10: Int),
        expiresAt = binding.Primitive.Timestamp
          .discardNanos(java.time.Instant.now().plus(1, ChronoUnit.MINUTES))
          .getOrElse(sys.error("Invalid instant")),
        collectionDuration = RelTime(microseconds = 60 * 1000000),
        reference = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(reqC.create.command),
      )

      // Check that we can see the created payment request
      val reqFound = aliceRemoteWallet.listAppPaymentRequests().headOption.value
      reqFound.payload shouldBe reqC

      // Reject the payment request
      aliceRemoteWallet.rejectAppPaymentRequest(reqFound.contractId)

      // Check that there are no more payment requests
      val requests2 = aliceRemoteWallet.listAppPaymentRequests()
      requests2 shouldBe empty
    }

    "allow a user to create, list, and accept app payment requests" in { implicit env =>
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      // TODO(M1-90 Backlog): consider adding synchronization 'wait-for-participant-x' to this command
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          testWalletCodegen
            .TestReference(
              p = aliceUserParty.toPrim,
              description = "description",
            )
            .create
            .command
        ),
      )
      val referenceId =
        aliceWallet.remoteParticipant.ledger_api.acs
          .await(aliceUserParty, testWalletCodegen.TestReference)
          .contractId

      // Create a payment request to self.
      val reqC = walletCodegen.AppPaymentRequest(
        sender = aliceUserParty.toPrim,
        provider = aliceUserParty.toPrim,
        receiver = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        quantity = BigDecimal(10: Int),
        expiresAt = binding.Primitive.Timestamp
          .discardNanos(java.time.Instant.now().plus(1, ChronoUnit.MINUTES))
          .getOrElse(sys.error("Invalid instant")),
        collectionDuration = RelTime(microseconds = 60 * 1000000),
        reference = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
      )
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(reqC.create.command),
      )

      val cid = inside(aliceRemoteWallet.listAppPaymentRequests()) { case Seq(r) =>
        r.payload shouldBe reqC
        r.contractId
      }

      aliceRemoteWallet.tap(50)
      val acceptedPaymentId = aliceRemoteWallet.acceptAppPaymentRequest(cid)
      aliceRemoteWallet.listAppPaymentRequests() shouldBe empty
      inside(aliceRemoteWallet.listAcceptedAppPayments()) { case Seq(r) =>
        r.contractId shouldBe acceptedPaymentId
        r.payload shouldBe walletCodegen.AcceptedAppPayment(
          sender = aliceUserParty.toPrim,
          receiver = aliceUserParty.toPrim,
          provider = aliceUserParty.toPrim,
          svc = svcParty.toPrim,
          lockedCoin = r.payload.lockedCoin,
          reference = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
        )
      }
    }

    "correctly select coins for payments" in { implicit env =>
      import env._

      val aliceUserParty = clue("Onboard alice on her self-hosted validator") {
        val aliceValidatorParty = aliceValidator.initialize()
        val aliceDamlUser = aliceRemoteWallet.config.damlUser
        aliceWallet.initialize(aliceValidatorParty)
        aliceValidator.onboardUser(aliceDamlUser)
      }

      val bobUserParty = clue("Onboard bob on his self-hosted validator") {
        val bobValidatorParty = bobValidator.initialize()
        val bobDamlUser = bobRemoteWallet.config.damlUser
        bobWallet.initialize(bobValidatorParty)
        bobValidator.onboardUser(bobDamlUser)
      }

      clue("Alice opens payment channel to Bob") {
        val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
        utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
        bobRemoteWallet.acceptPaymentChannelProposal(proposalId)
        utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().isEmpty)
      }

      clue("Alice gets some coins") {
        // Note: it would be great if we could add coins with different holding fees,
        // to test whether the wallet selects the most expensive ones for the transfer.
        aliceRemoteWallet.tap(10)
        aliceRemoteWallet.tap(40)
        aliceRemoteWallet.tap(20)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((10, 10), (20, 20), (40, 40)))
      }

      clue("Alice transfers 39") {
        aliceRemoteWallet.executeDirectTransfer(bobUserParty, 39)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((0, 1), (10, 10), (20, 20)))
      }
      clue("Alice transfers 19") {
        aliceRemoteWallet.executeDirectTransfer(bobUserParty, 19)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((0, 1), (0, 1), (10, 10)))
      }
    }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      import env._

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

      // Neither Alice nor Bob see a payment channel proposal
      aliceRemoteWallet.listPaymentChannelProposals() shouldBe empty
      bobRemoteWallet.listPaymentChannelProposals() shouldBe empty

      // Neither Alice nor Bob see any payment channels
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listPaymentChannels() shouldBe empty

      // Alice proposes payment channel to Bob
      val proposalId = aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceRemoteWallet.listPaymentChannelProposals()

      // Alice and Bob still don't see the payment channel yet
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listPaymentChannels() shouldBe empty

      aliceProposals should have size (1)
      val aliceProposal = aliceProposals(0)
      val aliceChannel = aliceProposal.payload.channel
      aliceProposal.contractId shouldBe proposalId
      aliceChannel.sender shouldBe aliceUserParty.toPrim
      aliceChannel.receiver shouldBe bobUserParty.toPrim

      // Bob monitors proposals and accepts the one
      utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
      val bobProposals = bobRemoteWallet.listPaymentChannelProposals()
      aliceProposals shouldBe bobProposals
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannelProposals().isEmpty)

      // Neither Alice nor Bob see a payment channel proposal
      aliceRemoteWallet.listPaymentChannelProposals() shouldBe empty
      bobRemoteWallet.listPaymentChannelProposals() shouldBe empty

      // But both see the established channel now
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannels().size == 1)
      aliceRemoteWallet.listPaymentChannels() shouldBe bobRemoteWallet.listPaymentChannels()

      // Alice taps and does a direct transfer to Bob
      aliceRemoteWallet.tap(50)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((50, 50)))
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 10)
      bobWallet.remoteParticipant.ledger_api.acs.await(bobUserParty, coinCodegen.Coin)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10)))

      // Bob asks for more coins, alice accepts
      aliceRemoteWallet.listOnChannelPaymentRequests().size shouldBe 0
      val request = bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      utils.retry_until_true(aliceRemoteWallet.listOnChannelPaymentRequests().size == 1)
      aliceRemoteWallet.listOnChannelPaymentRequests().headOption.value.contractId shouldBe request
      bobRemoteWallet.listOnChannelPaymentRequests() shouldBe aliceRemoteWallet
        .listOnChannelPaymentRequests()
      aliceRemoteWallet.acceptOnChannelPaymentRequest(request)
      utils.retry_until_true(
        bobWallet.remoteParticipant.ledger_api.acs
          .of_party(bobUserParty, None, true, Seq(coinCodegen.Coin.id))
          .size == 2
      )
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, alice rejects
      val request1 =
        bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please reject")
      utils.retry_until_true(aliceRemoteWallet.listOnChannelPaymentRequests().size == 1)
      aliceRemoteWallet.rejectOnChannelPaymentRequest(request1)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, then withdraws
      val request2 =
        bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "will withdraw")
      bobRemoteWallet.withdrawOnChannelPaymentRequest(request2)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobRemoteWallet, Seq((9, 10), (9, 10)))

      aliceRemoteWallet.proposePaymentChannel(
        bobUserParty,
        Some(aliceRemoteWallet.listPaymentChannels().head.contractId),
        allowDirectTransfers = false,
      )
      utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
      bobRemoteWallet.acceptPaymentChannelProposal(
        bobRemoteWallet.listPaymentChannelProposals().head.contractId
      )
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannels().size == 1)
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        aliceRemoteWallet
          .executeDirectTransfer(bobUserParty, 10),
        _.errorMessage should include("failed due to an exception"),
        _.errorMessage should include("Direct transfers are allowed"),
      )
    }

    "allow two remote wallets to connect to one local wallet and tap" in { implicit env =>
      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceWallet.initialize(aliceValidatorParty)
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      aliceRemoteWallet.tap(50.0)
      checkWallet(aliceUserParty, aliceRemoteWallet, Seq((50, 50)))

      // Onboard charlie onto alice's validator
      val charlieDamlUser = charlieRemoteWallet.config.damlUser
      val charlieUserParty = aliceValidator.onboardUser(charlieDamlUser)

      charlieRemoteWallet.tap(50.0)
      checkWallet(charlieUserParty, charlieRemoteWallet, Seq((50, 50)))

      aliceWallet.setWalletContext(charlieDamlUser)
      checkWallet(charlieUserParty, aliceWallet, Seq((50, 50)))
    }

    "(propose, accept, and) cancel a payment channel by sender" in { implicit env =>
      import env._

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

      // Alice proposes payment channel to Bob
      aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceRemoteWallet.listPaymentChannelProposals()
      val aliceProposal = aliceProposals(0)

      // Bob monitors proposals and accepts the one
      utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob requests a payment, and then immediately cancels the channel
      bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      bobRemoteWallet.cancelPaymentChannelBySender(aliceUserParty)

      // Neither sees the payment channel nor the payment request anymore
      bobRemoteWallet.listPaymentChannels() shouldBe empty
      bobRemoteWallet.listOnChannelPaymentRequests() should not be empty
      utils.retry_until_true(aliceRemoteWallet.listOnChannelPaymentRequests().nonEmpty)
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannels().isEmpty)
    }

    "(propose, accept, and) cancel a payment channel by receiver" in { implicit env =>
      import env._

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

      // Alice proposes payment channel to Bob
      aliceRemoteWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceRemoteWallet.listPaymentChannelProposals()
      val aliceProposal = aliceProposals(0)

      // Bob monitors proposals and accepts the one
      utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob requests a payment, and then immediately cancels the channel
      bobRemoteWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      aliceRemoteWallet.cancelPaymentChannelByReceiver(bobUserParty)

      // Neither sees the payment channel nor the payment request anymore
      aliceRemoteWallet.listPaymentChannels() shouldBe empty
      aliceRemoteWallet.listOnChannelPaymentRequests() should not be empty
      utils.retry_until_true(bobRemoteWallet.listOnChannelPaymentRequests().nonEmpty)
      utils.retry_until_true(bobRemoteWallet.listPaymentChannels().isEmpty)
    }

    "list and collect app & validator rewards" in { implicit env =>
      import env._

      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceDamlUser = aliceRemoteWallet.config.damlUser
      aliceWallet.initialize(aliceValidatorParty)
      aliceValidator.installWalletForValidator()
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobDamlUser = bobRemoteWallet.config.damlUser
      bobWallet.initialize(bobValidatorParty)
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)

      // Setup payment channel between alice and bob
      val aliceProposalId =
        aliceRemoteWallet.proposePaymentChannel(bobUserParty, senderTransferFeeRatio = 0.5)
      utils.retry_until_true(bobRemoteWallet.listPaymentChannelProposals().size == 1)
      bobRemoteWallet.acceptPaymentChannelProposal(aliceProposalId)
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannels().size == 1)

      // Setup payment channel between bob and alice
      val bobProposalId =
        bobRemoteWallet.proposePaymentChannel(aliceUserParty, senderTransferFeeRatio = 0.5)
      utils.retry_until_true(aliceRemoteWallet.listPaymentChannelProposals().size == 1)
      aliceRemoteWallet.acceptPaymentChannelProposal(bobProposalId)
      utils.retry_until_true(bobRemoteWallet.listPaymentChannels().size == 2)

      // Tap coin and do a transfer from alice to bob
      aliceRemoteWallet.tap(50)
      utils.retry_until_true(aliceRemoteWallet.list().coins.size == 1)
      aliceRemoteWallet.executeDirectTransfer(bobUserParty, 40)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice; bob will receive some app rewards
      utils.retry_until_true(bobRemoteWallet.list().coins.size == 1)
      bobRemoteWallet.executeDirectTransfer(aliceUserParty, 30)

      // Wait for app rewards to become visible in bob's wallet, and check structure
      bobWallet.remoteParticipant.ledger_api.acs
        .await(bobUserParty, coinCodegen.AppReward)
        .contractId
      val appRewards = bobRemoteWallet.listAppRewards()
      appRewards should have size 1
      bobRemoteWallet.listValidatorRewards() shouldBe empty

      // Wait for validator rewards to become visible in alice's wallet, check structure
      val validatorRewards = aliceValidatorRemoteWallet.listValidatorRewards()
      validatorRewards should have size 1
      aliceRemoteWallet.tap(200)
      utils.retry_until_true(aliceRemoteWallet.list().coins.size == 3)

      // Bob collects/realizes rewards
      val prevCoins = bobRemoteWallet.list().coins
      svc.openRound(1)
      svc.startClosingRound(0)
      svc.startIssuingRound(0)
      bobRemoteWallet.collectRewards(0)
      bobRemoteWallet.listValidatorRewards() shouldBe empty
      // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
      // for rewards.
      checkWallet(
        bobUserParty,
        bobRemoteWallet,
        prevCoins
          .map(c =>
            (
              c.contract.payload.quantity.initialQuantity,
              c.contract.payload.quantity.initialQuantity + 2,
            )
          )
          .sortBy(_._1),
      )
    }
  }

  "support coin redistribution" in { implicit env =>
    val aliceValidatorParty = aliceValidator.initialize()
    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    aliceWallet.initialize(aliceValidatorParty)
    val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)

    aliceRemoteWallet.tap(50)
    val coin2 = aliceRemoteWallet.tap(10)
    val coin3 = aliceRemoteWallet.tap(15)
    aliceRemoteWallet.redistribute(Seq(coin2, coin3), Seq(Some(3.0), Some(5.0), None))
    val exactly = (x: BigDecimal) => (x, x)
    checkWallet(
      aliceUserParty,
      aliceRemoteWallet,
      Seq(exactly(3.0), exactly(5.0), (16.0, 17.0), exactly(50.0)),
    )

    val all = aliceRemoteWallet.list().coins.map(c => c.contract.contractId)
    aliceRemoteWallet.redistribute(all, Seq(None))
    checkWallet(aliceUserParty, aliceRemoteWallet, Seq((74.0, 75.0)))
  }

  "fails with an understandable error when not initialized" in { implicit env =>
    aliceValidator.initialize()
    val aliceDamlUser = aliceRemoteWallet.config.damlUser
    aliceValidator.onboardUser(aliceDamlUser)
    aliceWallet.setWalletContext(aliceDamlUser)
    assertThrowsAndLogsCommandFailures(
      aliceRemoteWallet.tap(10),
      _.errorMessage should include("Wallet is not initialized"),
    )
  }

  /** @param expectedQuantityRanges: lower and upper bounds for coins sorted by their initial quantity in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppReference,
      expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
  ): Unit = {
    eventually(10.seconds, 500.millis) {
      val coins = wallet.list().coins.sortBy(coin => coin.contract.payload.quantity.initialQuantity)
      coins should have size (expectedQuantityRanges.size.toLong)
      clue(
        s"Comparing ${coins.map(_.contract.payload.quantity.initialQuantity)} with $expectedQuantityRanges"
      ) {
        coins
          .zip(expectedQuantityRanges)
          .foreach { case (coin, quantityBounds) =>
            coin.contract.payload.owner shouldBe walletParty.toPrim
            val ExpiringQuantity(initialQuantity, createdAt, ratePerRound) =
              coin.contract.payload.quantity
            assertInRange(initialQuantity, quantityBounds)
            ratePerRound shouldBe RatePerRound(
              CoinUtil.defaultHoldingFee.rate.doubleValue
            )
          }
      }
    }
  }

  def checkBalance(
      balance: Balance,
      expectedRound: Long,
      expectedUQRange: (BigDecimal, BigDecimal),
      expectedLQRange: (BigDecimal, BigDecimal),
      expectedHRange: (BigDecimal, BigDecimal),
  ): Unit = {
    balance.round shouldBe expectedRound
    assertInRange(balance.unlockedQty, expectedUQRange)
    assertInRange(balance.lockedQty, expectedLQRange)
    assertInRange(balance.holdingFees, expectedHRange)
  }

  def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
    value should (be >= range._1 and be <= range._2)
  }

  def lockCoins(userParty: PartyId, amt: Int)(implicit
      env: CoinTestConsoleEnvironment
  ): Unit = {
    aliceWallet.remoteParticipant.ledger_api.commands.submit(
      Seq(userParty),
      optTimeout = None,
      commands = Seq(
        testWalletCodegen
          .TestReference(
            p = userParty.toPrim,
            description = "description",
          )
          .create
          .command
      ),
    )

    val referenceId =
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(userParty, testWalletCodegen.TestReference)
        .contractId

    // Create a payment request to self.
    val reqC = walletCodegen.AppPaymentRequest(
      sender = userParty.toPrim,
      provider = userParty.toPrim,
      receiver = userParty.toPrim,
      svc = svcParty.toPrim,
      quantity = BigDecimal(amt),
      expiresAt = binding.Primitive.Timestamp
        .discardNanos(java.time.Instant.now().plus(30, ChronoUnit.MINUTES))
        .getOrElse(sys.error("Invalid instant")),
      collectionDuration = RelTime(microseconds = 60 * 1000000),
      reference = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
    )
    aliceWallet.remoteParticipant.ledger_api.commands.submit(
      actAs = Seq(userParty),
      optTimeout = None,
      commands = Seq(reqC.create.command),
    )

    // Check that we can see the created payment request
    val reqFound = aliceRemoteWallet.listAppPaymentRequests().headOption.value

    // Accept the payment request
    aliceRemoteWallet.acceptAppPaymentRequest(reqFound.contractId)
  }

  def nextRound()(implicit env: CoinTestConsoleEnvironment): Unit = {
    svc.startClosingRound(0)
    svc.startIssuingRound(0)
    svc.closeRound(0)
    svc.openRound(1)
  }
}
