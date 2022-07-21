package com.daml.network.integration.tests

import com.daml.network.console.{LocalValidatorAppReference, LocalWalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.{CoinCreation, CommonCoinAppInstanceReferences}
import com.daml.network.wallet.ExpiringQuantity
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CoinCreation
    with CommonCoinAppInstanceReferences {
  override val defaultParticipant: String = "participant1"
  // same as damlUser in config
  private val damlUser = "god"
  private val quantity = 100d
  private val coinDarPath = "canton-coin/.daml/dist/canton-coin.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology.withSetup(env => {
      import env._
      participants.all.map(_.dars.upload(coinDarPath))
    })

  "A wallet" should {
    "list coins owned by its damlUser" in { implicit env =>
      import env._

      clue("setup") {
        wallet1.remoteParticipant.domains.connect_local(da)
        val pId = wallet1.remoteParticipant.parties.enable(damlUser, Some(damlUser))
        wallet1.remoteParticipant.ledger_api.users.create(damlUser, Set(pId.toLf), Some(pId.toLf))

        createCoin(damlUser, quantity, "da", participant1)
      }

      clue("call list") {
        val res = wallet1.list().headOption.value
        res.quantity shouldBe ExpiringQuantity(quantity, round, ratePerRound)
        res.owner.uid.id shouldBe damlUser
        res.svc.uid.id shouldBe damlUser
      }
    }
  }
}
