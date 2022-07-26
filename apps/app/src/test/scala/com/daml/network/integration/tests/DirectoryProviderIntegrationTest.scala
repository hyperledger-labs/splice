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

class DirectoryProviderIntegrationTest
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

  "A directory provider" should {
    "call hello" in { implicit env =>
      import env._

      clue("setup") {
        directoryProvider.remoteParticipant.domains.connect_local(da)
        val pId = directoryProvider.remoteParticipant.parties.enable(damlUser, Some(damlUser))
        directoryProvider.remoteParticipant.ledger_api.users
          .create(damlUser, Set(pId.toLf), Some(pId.toLf))
      }

      clue("call hello") {
        directoryProvider.hello() shouldBe ()
      }
    }
  }
}
