package com.daml.network.integration.tests

import com.daml.ledger.client.binding
import com.daml.network.console.WalletAppReference
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
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.CoinRules
import com.digitalasset.network.CC.Round.Round
import com.digitalasset.network.CC.CoinRules.{CoinRules, CoinRulesRequest}
import com.digitalasset.network.CN.Wallet.AppPaymentRequest
import com.digitalasset.network.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.digitalasset.network.DA.Time.Types.RelTime
import com.digitalasset.network.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  // same as damlUser in config
  private val walletDamlUser = "alice"

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
      val validatorParty = validator1.initialize()
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      val userParty = validator1.onboardUser(walletDamlUser)
      wallet1.initialize(validatorParty)
      val remoteWallet1 = rw("remoteWallet1")

      // ensure wallet's participant sees the CoinRules
      wallet1.remoteParticipant.ledger_api.acs.await(validatorParty, CoinRules)
      wallet1.list() shouldBe Seq()

      val exactly = (x: BigDecimal) => (x, x)
      val ranges1 = Seq(exactly(50))
      wallet1.tap(50)
      checkWallet(userParty, wallet1, ranges1)
      checkWallet(userParty, remoteWallet1, ranges1)

      val ranges2 = Seq(exactly(50), exactly(60))
      remoteWallet1.tap(60)
      checkWallet(userParty, wallet1, ranges2)
      checkWallet(userParty, remoteWallet1, ranges2)
    }

    "allow a user to create, list, and reject payment requests" in { implicit env =>
      import env._
      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party
      val validatorParty = validator1.initialize()
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      val userParty = validator1.onboardUser(walletDamlUser)
      wallet1.initialize(validatorParty)

      // ensure wallet's participant sees the CoinRules
      val coinRulesId =
        wallet1.remoteParticipant.ledger_api.acs.await(validatorParty, CoinRules).contractId

      // Check that no payment requests exist
      wallet1.listAppPaymentRequests() shouldBe empty

      // Create a payment request to self.
      val reqC = AppPaymentRequest(
        payer = userParty.toPrim,
        payee = userParty.toPrim,
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
      wallet1.remoteParticipant.ledger_api.commands.submit(
        actAs = Seq(userParty),
        optTimeout = None,
        commands = Seq(reqC.create.command),
      )

      // Check that we can see the created payment request
      val reqFound = wallet1.listAppPaymentRequests().headOption.value
      reqFound.payload shouldBe reqC

      // Reject the payment request
      wallet1.rejectAppPaymentRequest(reqFound.contractId)

      // Check that there are no more payment requests
      val requests2 = wallet1.listAppPaymentRequests()
      requests2 shouldBe empty
    }

    "allow two users to create a payment channel and use it for a transfer" in { implicit env =>
      import env._

      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party

      // Onboard alice on her self-hosted validator
      val aliceValidatorParty = validator1.initialize()
      val aliceUserParty = validator1.onboardUser("alice")
      // TODO(M1-92): improve naming in simple-topology.conf
      val aliceWallet = wallet1
      aliceWallet.initialize(aliceValidatorParty)

      // Onboard bob on his self-hosted validator
      val bobValidator = v("bobValidator")
      val bobValidatorParty = bobValidator.initialize()
      val bobUserParty = bobValidator.onboardUser("bob")
      val bobWallet = w("bobWallet")
      bobWallet.initialize(bobValidatorParty)

      // Alice proposes payment channel to Bob, and Bob accepts
      val proposalId = aliceWallet.proposePaymentChannel(bobUserParty)
      bobWallet.acceptPaymentChannelProposal(proposalId)

      // Alice taps and does a direct transfer to Bob
      val coinCid = aliceWallet.tap(50)
      checkWallet(aliceUserParty, aliceWallet, Seq((50, 50)))
      aliceWallet.executeDirectTransfer(bobUserParty, 10, coinCid)
      checkWallet(aliceUserParty, aliceWallet, Seq((39, 40)))
      checkWallet(bobUserParty, bobWallet, Seq((9, 10)))
    }
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
        createdAt shouldBe Round(0)
        ratePerRound shouldBe RatePerRound(
          CoinUtil.defaultHoldingFee.rate.doubleValue
        )
      }
  }
}
