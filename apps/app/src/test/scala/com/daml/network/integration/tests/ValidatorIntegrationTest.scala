package com.daml.network.integration.tests

import java.util.concurrent.atomic.AtomicReference

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC

class ValidatorIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology

  "initialize svc and validator apps" in { implicit env =>
    import env._
    val validatorApp = v("validator1")
    val svcApp = svc

    val svcParty = new AtomicReference[PartyId]()
    val validatorParty = new AtomicReference[PartyId]()

    clue("start SVC app") {
      svcApp.start()
    }

    clue("connect SVC participant to domain") {
      // TODO(M1-90): Should the SVC app connect itself to the domain?
      //  That would be less manual steps in the setup script, at the cost of having to give the app
      //  access to the admin API. Same for other apps.
      svcApp.remoteParticipant.domains.connect_local(da)
    }

    clue("initialize SVC app") {
      svcApp.initialize()

      val config = svcApp.getValidatorConfig
      svcParty.set(config.svcParty)
    }

    clue("check: there is now an open mining round") {
      val coinRules = svcApp.remoteParticipant.ledger_api.acs
        .of_party(svcParty.get, filterTemplates = Seq(CC.CoinRules.CoinRules.id))
      coinRules.length shouldBe 1

      val openRounds = svcApp.remoteParticipant.ledger_api.acs
        .of_party(svcParty.get, filterTemplates = Seq(CC.Round.OpenMiningRound.id))
      openRounds.length shouldBe 1
    }

    clue("start validator app") {
      validatorApp.start()
    }

    clue("connect validator participant to domain") {
      validatorApp.remoteParticipant.domains.connect_local(da)
    }

    clue("initialize validator app") {
      // TODO(M1-90) Validator app should upload the dar, like the SVC app
      val coinDarPath = "canton-coin/.daml/dist/canton-coin.dar"
      validatorApp.remoteParticipant.dars.upload(coinDarPath)

      validatorParty.set(validatorApp.initialize())
      validatorParty.get.uid.id shouldBe "validator1"
    }

    clue("check: request for coin rules is gone, and the validator app sees CoinRules") {
      // synchronization here as it can take a sec for the automation in `SvcAppAutomation`
      // to see the CoinRulesRequest and accept it.
      utils.retry_until_true({
        val requests = svcApp.remoteParticipant.ledger_api.acs
          .filter(svcParty.get, CC.CoinRules.CoinRulesRequest)
        requests == Seq()
      })

      validatorApp.remoteParticipant.ledger_api.acs
        .await(validatorParty.get, CC.CoinRules.CoinRules)
    }

    clue("onboard an end-user") {
      val userParty = validatorApp.onboardUser(user = "user1")
      userParty.uid.id shouldBe "user1"
    }
  }

}
