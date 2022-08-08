package com.daml.network.integration.tests

import com.daml.ledger.client.binding
import com.daml.network.console.{LocalWalletAppReference, WalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences, Contract}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.DA
import com.digitalasset.network.CC.Coin.{AppReward, Coin, ValidatorRight}
import com.digitalasset.network.CC.CoinRules.{
  CoinRules,
  ReceiverCoinType,
  Transfer,
  TransferInput,
  TransferOutput,
}
import com.digitalasset.network.CC.Round.Round
import com.digitalasset.network.CN.{Wallet => walletCodegen}
import com.digitalasset.network.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.digitalasset.network.DA.Time.Types.RelTime

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.foreach(_.domains.connect_local(da))
    })

  "A wallet" should {
    "allow calling tap and then list the created coins - locally and remotely" in { implicit env =>
      import env._
      svc.initialize()
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

    "allow a user to create, list, and reject payment requests" in { implicit env =>
      import env._
      val svcParty = svc.initialize()
      val aliceValidatorParty = aliceValidator.initialize()
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      val aliceUserParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      aliceWallet.initialize(aliceValidatorParty)

      // ensure wallet's participant sees the CoinRules
      val coinRulesId =
        aliceWallet.remoteParticipant.ledger_api.acs
          .await(aliceValidatorParty, CoinRules)
          .contractId

      // Check that no payment requests exist
      aliceWallet.listAppPaymentRequests() shouldBe empty

      // Create a payment request to self.
      val reqC = walletCodegen.AppPaymentRequest(
        payer = aliceUserParty.toPrim,
        payee = aliceUserParty.toPrim,
        svc = svcParty.toPrim,
        quantity = BigDecimal(10: Int),
        expiresAt = binding.Primitive.Timestamp
          .discardNanos(java.time.Instant.now())
          .getOrElse(sys.error("Invalid instant")),
        collectionDuration = RelTime(microseconds = 1000),
        // Hack: we abuse the coinRulesId here, as the check that it implements
        // the PaymentReference interface only happens on a fetch, and not on the create.
        reference = binding.Primitive.ContractId.apply(coinRulesId.toString),
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

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      import env._

      svc.initialize()

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

      // Alice proposes payment channel to Bob
      val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
      val aliceProposals = aliceWallet.listPaymentChannelProposals()

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

      // Neither Alice nor Bob see a payment channel proposal
      aliceWallet.listPaymentChannelProposals() shouldBe empty
      bobWallet.listPaymentChannelProposals() shouldBe empty

      // Alice taps and does a direct transfer to Bob
      val coinCid = aliceWallet.tap(50)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))
      aliceWallet.executeDirectTransfer(bobUserParty, 10, coinCid)
      checkWallet(aliceUserParty, aliceWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10)))

      // Bob asks for more coins, alice accepts
      val request = bobWallet.createOnChannelPaymentRequest(aliceUserParty, 10, "please pay")
      aliceWallet.approveOnChannelPaymentRequest(request, aliceWallet.list().head.contractId)
      checkWallet(aliceUserParty, aliceWallet, Seq((29, 30)))
      checkWallet(bobUserParty, bobWallet, Seq((9,10), (9,10)))

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

    }

    "list app & validator rewards" in { implicit env =>
      import env._
      val svcParty = svc.initialize()

      // Onboard alice on her self-hosted validator
      val validatorParty = aliceValidator.initialize()
      val aliceParty = aliceValidator.onboardUser(aliceWallet.config.damlUser)
      val bobParty = aliceValidator.onboardUser("bob")
      aliceWallet.initialize(validatorParty)

      val tappedCoin = aliceWallet.tap(50)
      // Bare transfer so we get control over fee ratio
      bareTransfer(
        aliceWallet,
        senderParty = aliceParty,
        receiverParty = bobParty,
        validatorParty = validatorParty,
        svcParty = svcParty,
        coin = tappedCoin,
      )
      val transferredCoin =
        aliceWallet.remoteParticipant.ledger_api.acs.await(bobParty, Coin).contractId
      // Transfer back. Alice is now the receiver so they get an app reward.
      bareTransfer(
        aliceWallet,
        senderParty = bobParty,
        receiverParty = aliceParty,
        validatorParty = validatorParty,
        svcParty = svcParty,
        coin = transferredCoin,
      )
      aliceWallet.listAppRewards() should have size 1
      aliceWallet.listValidatorRewards() shouldBe empty
      // TODO(i296) We cannot use the wallet as the validator yet so create a validator right where alice is their own validator.
      aliceWallet.remoteParticipant.ledger_api.commands.submit(
        Seq(aliceParty),
        optTimeout = None,
        commands =
          Seq(ValidatorRight(svcParty.toPrim, aliceParty.toPrim, aliceParty.toPrim).create.command),
      )
      aliceWallet.listValidatorRewards() should have size 1
    }
  }

  def bareTransfer(
      wallet: LocalWalletAppReference,
      senderParty: PartyId,
      receiverParty: PartyId,
      validatorParty: PartyId,
      svcParty: PartyId,
      coin: binding.Primitive.ContractId[Coin],
  ) =
    wallet.remoteParticipant.ledger_api.commands.submit(
      Seq(senderParty, receiverParty, validatorParty),
      optTimeout = None,
      commands = Seq(
        CoinRules
          .key(DA.Types.Tuple2(svcParty.toPrim, validatorParty.toPrim))
          .exerciseCoinRules_Transfer(
            senderParty.toPrim,
            Transfer(
              sender = senderParty.toPrim,
              inputs = Seq(TransferInput.InputCoin(coin)),
              outputs = Seq(
                TransferOutput.OutputReceiverCoin(
                  receiver = receiverParty.toPrim,
                  coinType = ReceiverCoinType.FloatingReceiverCoin(()),
                )
              ),
              payload = "bare transfer",
            ),
          )
          .command
      ),
    )

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
        createdAt shouldBe Round(0)
        ratePerRound shouldBe RatePerRound(
          CoinUtil.defaultHoldingFee.rate.doubleValue
        )
      }
  }
}
