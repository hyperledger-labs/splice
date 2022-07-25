package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinCreation, CoinUtil, CommonCoinAppInstanceReferences}
import com.daml.network.wallet.ExpiringQuantity
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.network.CC.CoinRules.{CoinRules, CoinRulesRequest}

import scala.concurrent.duration._

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  // same as damlUser in config
  private val walletDamlUser = "god"
  private val quantity = "50.0"
  private val coinDarPath = "canton-coin/.daml/dist/canton-coin.dar"

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
  }
}
