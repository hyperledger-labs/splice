package com.daml.network.integration.tests

import com.daml.network.console.{LocalValidatorAppReference, LocalWalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration._

class DummyIntegrationTest
    extends CoinIntegrationTest
    with IsolatedCoinEnvironments
    with CommonCoinAppInstanceReferences {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology

  "run commands against validator" in { implicit env =>
    val validatorRemoteParReference = validator1.remoteParticipant
    val coinDarPath = "canton-coin/.daml/dist/canton-coin.dar"

    clue("start validator and run setup") {
      val svc = validator1.remoteParticipant.parties.enable(
        "svc"
      ) // TODO(luciano) replace with actual SVC party
      validator1.start()
      validator1.remoteParticipant.domains.connect_local(env.da)
      validator1.remoteParticipant.dars.upload(coinDarPath)
      val validatorParty = validator1.initialize("validator1", svc)
      validatorParty.uid.id shouldBe "validator1"
      val userParty = validator1.onboardUser(user = "user1")
      userParty.uid.id shouldBe "user1"
    }

    clue("connect to domain and run a ping with validator's participant") {
      validatorRemoteParReference.domains.connect_local(env.da)
      validatorRemoteParReference.health.ping(validatorRemoteParReference.id)
    }

  }

  "run commands against SVC" in { implicit env =>
    val svc = env.svc
    clue("start SVC") {
      svc.start()
    }

    val svcRemoteParReference = svc.remoteParticipant
    clue("connect to domain and run a ping with SVC's participant") {
      svcRemoteParReference.domains.connect_local(env.da)
      svcRemoteParReference.health.ping(svcRemoteParReference.id)
    }

    clue("run initialization") {
      svc.initialize()
      val info = svc.getDebugInfo()
      info.coinRulesCids.length shouldBe 1
    }

    clue("open next round") {
      // Note: Throws because it's not implemented
      an[CommandFailure] should be thrownBy svc.openNextRound()
    }
  }
}
