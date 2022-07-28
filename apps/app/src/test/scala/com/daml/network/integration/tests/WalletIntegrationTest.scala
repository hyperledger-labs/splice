package com.daml.network.integration.tests

import com.daml.ledger.client.binding
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences}
import com.daml.network.wallet.domain.{ExpiringQuantity, PaymentRequest}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.network.CC.CoinRules.{CoinRules, CoinRulesRequest}
import com.digitalasset.network.CN.Wallet
import com.digitalasset.network.DA.Time.Types.RelTime

import scala.concurrent.duration._

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  // same as damlUser in config
  private val walletDamlUser = "god"
  private val quantity = "50.0"
  private val coinDarPath = "apps/wallet/daml/.daml/dist/wallet.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.foreach(_.dars.upload(coinDarPath))
      participants.all.foreach(_.domains.connect_local(da))
    })

  "A wallet" should {
    "allow calling tap and then list the created coins" in { implicit env =>
      import env._
      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party
      val validatorParty = validator1.initialize("validator1", svcParty)
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      validator1.onboardUser(walletDamlUser)
      wallet1.initialize(svcParty, validatorParty)
      svc.acceptValidators()

      // ensure wallet's participant sees the CoinRules
      wallet1.remoteParticipant.ledger_api.acs.await(validatorParty, CoinRules)
      wallet1.tap(quantity)
      val res = wallet1.list().headOption.value
      res.quantity shouldBe ExpiringQuantity(
        BigDecimal(quantity),
        createdAt = 0,
        ratePerRound = CoinUtil.defaultHoldingFee.rate.doubleValue,
      )
      res.owner.uid.id shouldBe walletDamlUser
    }

    "allow listing a user's payment requests" in { implicit env =>
      import env._
      svc.initialize()
      val svcParty =
        svc.remoteParticipant.parties.list(filterParty = "svc").headOption.value.party
      val validatorParty = validator1.initialize("validator1", svcParty)
      // TODO(Arne): consider adding synchronization 'wait-for-participant-x' to this command
      val userParty = validator1.onboardUser(walletDamlUser)
      wallet1.initialize(svcParty, validatorParty)
      svc.acceptValidators()

      // ensure wallet's participant sees the CoinRules
      val coinRulesId =
        wallet1.remoteParticipant.ledger_api.acs.await(validatorParty, CoinRules).contractId

      // Check that no payment requests exist
      val reqs1 = wallet1.listPaymentRequests()
      reqs1 shouldBe empty

      // Create a payment request to self.
      val reqC = Wallet.PaymentRequest.PaymentRequest(
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
      val reqFound = wallet1.listPaymentRequests().headOption.value
      val reqExpected = PaymentRequest.fromContract(reqC)
      reqFound shouldBe reqExpected
    }
  }
}
