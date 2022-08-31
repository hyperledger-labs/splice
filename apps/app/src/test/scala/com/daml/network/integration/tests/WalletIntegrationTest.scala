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
import java.time.temporal.ChronoUnit

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
    "allow calling tap and then list the created coins - locally and remotely" in { implicit env =>
      val aliceValidatorParty = aliceValidator.initialize()
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)
      val aliceRemoteWallet = rw("aliceRemoteWallet")

      // ensure wallet's participant sees the CoinRules
      aliceWallet.remoteParticipant.ledger_api.acs.await(aliceValidatorParty, CoinRules)
      aliceWallet.list() shouldBe Seq()

      val exactly = (x: BigDecimal) => (x, x)
      val ranges1 = Seq(exactly(50))
      aliceWallet.tap(50)
      checkWallet(aliceUserParty, aliceWallet, ranges1)
      checkWallet(aliceUserParty, aliceRemoteWallet, ranges1)

      val ranges2 = Seq(exactly(50), exactly(60))
      aliceRemoteWallet.tap(60)
      checkWallet(aliceUserParty, aliceWallet, ranges2)
      checkWallet(aliceUserParty, aliceRemoteWallet, ranges2)
    }

    "allow a user to create, list, and reject app payment requests" in { implicit env =>
      val aliceValidatorParty = aliceValidator.initialize()
      // TODO(M1-90 Backlog): consider adding synchronization 'wait-for-participant-x' to this command
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

      // Check that no payment requests exist
      aliceWallet.listAppPaymentRequests() shouldBe empty

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
      val reqFound = aliceWallet.listAppPaymentRequests().headOption.value
      reqFound.payload shouldBe reqC

      // Reject the payment request
      aliceWallet.rejectAppPaymentRequest(reqFound.contractId)

      // Check that there are no more payment requests
      val requests2 = aliceWallet.listAppPaymentRequests()
      requests2 shouldBe empty
    }

    "allow a user to create, list, and accept app payment requests" in { implicit env =>
      val aliceValidatorParty = aliceValidator.initialize()
      // TODO(M1-90 Backlog): consider adding synchronization 'wait-for-participant-x' to this command
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

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

      val cid = inside(aliceWallet.listAppPaymentRequests()) { case Seq(r) =>
        r.payload shouldBe reqC
        r.contractId
      }

      val coin = aliceWallet.tap(50)
      val acceptedPaymentId = aliceWallet.acceptAppPaymentRequest(cid, coin)
      aliceWallet.listAppPaymentRequests() shouldBe empty
      inside(aliceWallet.listAcceptedAppPayments()) { case Seq(r) =>
        r.contractId shouldBe acceptedPaymentId
        r.payload shouldBe walletCodegen.AcceptedAppPayment(
          sender = aliceUserParty.toPrim,
          receiver = aliceUserParty.toPrim,
          svc = svcParty.toPrim,
          lockedCoin = r.payload.lockedCoin,
          reference = binding.Primitive.ContractId(ApiTypes.ContractId.unwrap(referenceId)),
        )
      }
    }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      import env._

      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
      bobWallet.initialize(bobValidatorParty)

      // Neither Alice nor Bob see a payment channel proposal
      aliceWallet.listPaymentChannelProposals() shouldBe empty
      bobWallet.listPaymentChannelProposals() shouldBe empty

      // Neither Alice nor Bob see any payment channels
      aliceWallet.listPaymentChannels() shouldBe empty
      bobWallet.listPaymentChannels() shouldBe empty

      // Alice proposes payment channel to Bob
      val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceWallet.listPaymentChannelProposals()

      // Alice and Bob still don't see the payment channel yet
      aliceWallet.listPaymentChannels() shouldBe empty
      bobWallet.listPaymentChannels() shouldBe empty

      aliceProposals should have size (1)
      val aliceProposal = aliceProposals(0)
      val aliceChannel = aliceProposal.payload.channel
      aliceProposal.contractId shouldBe proposalId
      aliceChannel.sender shouldBe aliceUserParty.toPrim
      aliceChannel.receiver shouldBe bobUserParty.toPrim

      // Bob monitors proposals and accepts the one
      utils.retry_until_true(bobWallet.listPaymentChannelProposals().size == 1)
      val bobProposals = bobWallet.listPaymentChannelProposals()
      aliceProposals shouldBe bobProposals
      bobWallet.acceptPaymentChannelProposal(aliceProposal.contractId)
      utils.retry_until_true(aliceWallet.listPaymentChannelProposals().isEmpty)

      // Neither Alice nor Bob see a payment channel proposal
      aliceWallet.listPaymentChannelProposals() shouldBe empty
      bobWallet.listPaymentChannelProposals() shouldBe empty

      // But both see the established channel now
      utils.retry_until_true(aliceWallet.listPaymentChannels().size == 1)
      aliceWallet.listPaymentChannels() shouldBe bobWallet.listPaymentChannels()

      // Alice taps and does a direct transfer to Bob
      val coinCid = aliceWallet.tap(50)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))
      aliceWallet.executeDirectTransfer(bobUserParty, 10, coinCid)
      bobWallet.remoteParticipant.ledger_api.acs.await(bobUserParty, coinCodegen.Coin)
      checkWallet(aliceUserParty, aliceWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10)))

      // Bob asks for more coins, alice accepts
      aliceWallet.listOnChannelPaymentRequests().size shouldBe 0
      val request = bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      utils.retry_until_true(aliceWallet.listOnChannelPaymentRequests().size == 1)
      aliceWallet.listOnChannelPaymentRequests().headOption.value.contractId shouldBe request
      bobWallet.listOnChannelPaymentRequests() shouldBe aliceWallet.listOnChannelPaymentRequests()
      aliceWallet.acceptOnChannelPaymentRequest(request, aliceWallet.list().head.contractId)
      utils.retry_until_true(
        bobWallet.remoteParticipant.ledger_api.acs
          .of_party(bobUserParty, None, true, Seq(coinCodegen.Coin.id))
          .size == 2
      )
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, alice rejects
      val request1 = bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please reject")
      aliceWallet.rejectOnChannelPaymentRequest(request1)
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      // Bob asks for more coins, then withdraws
      val request2 = bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "will withdraw")
      bobWallet.withdrawOnChannelPaymentRequest(request2)
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10), (9, 10)))

      aliceWallet.proposePaymentChannel(
        bobUserParty,
        Some(aliceWallet.listPaymentChannels().head.contractId),
        allowDirectTransfers = false,
      )
      utils.retry_until_true(bobWallet.listPaymentChannelProposals().size == 1)
      bobWallet.acceptPaymentChannelProposal(
        bobWallet.listPaymentChannelProposals().head.contractId
      )
      loggerFactory.assertThrowsAndLogs[CommandFailure](
        aliceWallet.executeDirectTransfer(bobUserParty, 10, aliceWallet.list().head.contractId),
        _.errorMessage should include("failed due to an exception"),
        _.errorMessage should include("Direct transfers are allowed"),
      )
    }

    "propose, accept, and cancel a payment channel" in { implicit env =>
      import env._

      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
      bobWallet.initialize(bobValidatorParty)

      // Alice proposes payment channel to Bob
      aliceWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceWallet.listPaymentChannelProposals()
      val aliceProposal = aliceProposals(0)

      // Bob monitors proposals and accepts the one
      utils.retry_until_true(bobWallet.listPaymentChannelProposals().size == 1)
      bobWallet.acceptPaymentChannelProposal(aliceProposal.contractId)

      // Bob then immediately cancels the channel
      bobWallet.cancelPaymentChannel(aliceUserParty)
      utils.retry_until_true(aliceWallet.listPaymentChannelProposals().isEmpty)

      // Neither sees the payment channel anymore
      aliceWallet.listPaymentChannels() shouldBe empty
      bobWallet.listPaymentChannels() shouldBe empty
    }

    "list and collect app & validator rewards" in { implicit env =>
      import env._

      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = aliceValidator.initialize()
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser(bobWallet.config.damlUser)
      bobWallet.initialize(bobValidatorParty)

      // Setup payment channel between alice and bob
      val aliceProposalId =
        aliceWallet.proposePaymentChannel(bobUserParty, senderTransferFeeRatio = 0.5)
      utils.retry_until_true(bobWallet.listPaymentChannelProposals().size == 1)
      bobWallet.acceptPaymentChannelProposal(aliceProposalId)
      utils.retry_until_true(aliceWallet.listPaymentChannels().size == 1)

      // Setup payment channel between bob and alice
      val bobProposalId =
        bobWallet.proposePaymentChannel(aliceUserParty, senderTransferFeeRatio = 0.5)
      utils.retry_until_true(aliceWallet.listPaymentChannelProposals().size == 1)
      aliceWallet.acceptPaymentChannelProposal(bobProposalId)
      utils.retry_until_true(bobWallet.listPaymentChannels().size == 2)

      // Tap coin and do a transfer from alice to bob
      val tappedCoin = aliceWallet.tap(50)
      aliceWallet.executeDirectTransfer(bobUserParty, 40, tappedCoin)

      // Retrieve transferred coin in bob's wallet and transfer part of it back to alice, and get her some app rewards
      utils.retry_until_true(bobWallet.list().size == 1)
      val transferredCoin = bobWallet.list()(0).contractId
      bobWallet.executeDirectTransfer(aliceUserParty, 30, transferredCoin)

      // Wait for app rewards to become visible, and check structure
      aliceWallet.remoteParticipant.ledger_api.acs
        .await(aliceUserParty, coinCodegen.AppReward)
        .contractId
      val appRewards = aliceWallet.listAppRewards()
      appRewards should have size 1
      aliceWallet.listValidatorRewards() shouldBe empty
      // TODO(i296) We cannot use the wallet as the validator yet so create a validator right where alice is their own validator.
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceUserParty),
        optTimeout = None,
        commands = Seq(
          coinCodegen
            .ValidatorRight(
              svcParty.toPrim,
              aliceUserParty.toPrim,
              aliceUserParty.toPrim,
            )
            .create
            .command
        ),
      )
      val validatorRewards = aliceWallet.listValidatorRewards()
      validatorRewards should have size 1
      val prevCoins = aliceWallet.list()
      val inputCoin = aliceWallet.tap(200)
      svc.openRound(1)
      svc.startClosingRound(0)
      svc.startIssuingRound(0)
      aliceWallet.collectRewards(inputCoin, 0)
      aliceWallet.listAppRewards() shouldBe empty
      aliceWallet.listValidatorRewards() shouldBe empty
      // We just check that we have a coin roughly in the right range, in particular higher than the input, rather than trying to repeat the calculation
      // for rewards.
      checkWallet(
        aliceUserParty,
        aliceWallet,
        (prevCoins.map(c =>
          (c.payload.quantity.initialQuantity, c.payload.quantity.initialQuantity)
        ) :+ (200, 202): Seq[(BigDecimal, BigDecimal)]).sortBy(_._1),
      )
    }
  }

  "fails with an understandable error when not initialized" in { implicit env =>
    aliceValidator.initialize()
    aliceValidator.onboardUser(aliceWallet.config.damlUser)
    assertThrowsAndLogsCommandFailures(
      aliceWallet.tap(10),
      _.errorMessage should include("Wallet is not initialized"),
    )
  }

  /** @param expectedQuantityRanges: lower and upper bounds for coins sorted by their initial quantity in ascending order. */
  def checkWallet(
      walletParty: PartyId,
      wallet: WalletAppReference,
      expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
  ) = {
    val coins = wallet.list().sortBy(coin => coin.payload.quantity.initialQuantity)
    coins should have size (expectedQuantityRanges.size.toLong)
    coins
      .zip(expectedQuantityRanges)
      .foreach { case (coin, (quantityLb: BigDecimal, quantityUb: BigDecimal)) =>
        coin.payload.owner shouldBe walletParty.toPrim
        val ExpiringQuantity(initialQuantity, createdAt, ratePerRound) = coin.payload.quantity
        initialQuantity should (be >= quantityLb and be <= quantityUb)
        ratePerRound shouldBe RatePerRound(
          CoinUtil.defaultHoldingFee.rate.doubleValue
        )
      }
  }
}
