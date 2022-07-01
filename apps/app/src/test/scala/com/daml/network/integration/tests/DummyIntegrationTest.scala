package com.daml.network.integration.tests

import com.daml.network.console.LocalValidatorReference
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
  IsolatedCoinEnvironments,
}

import scala.concurrent.duration._
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class DummyIntegrationTest extends CoinIntegrationTest with IsolatedCoinEnvironments {
  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.simpleTopology

  // essentially just the `SimplestPingIntegrationTest` from Canton
  "run a Canton ping" in { implicit env =>
    import env._
    clue("start Canton nodes") {
      env.da.start()
      participant1.start()
      participant2.start()
    }
    clue("Connect participants connect") {
      participant1.domains.connect_local(env.da)
      participant2.domains.connect_local(env.da)
    }
    clue("maybe ping") {
      participant1.health.maybe_ping(
        participant2,
        timeout = 30.seconds,
      ) shouldBe defined
    }
  }

  "run dummy command against validator" in { implicit env =>
    // TODO(Arne): move this into a trait analogue to Canton's `ConsoleEnvironmentTestHelpers`
    def v(name: String): LocalValidatorReference =
      env.validators
        .find(_.name == name)
        .getOrElse(sys.error(s"validator [$name] not configured"))

    clue("start validator and run dummy command") {
      val validator1 = v("validator1")
      validator1.start()
      val res = validator1.dummy_command("Hello. Please increment this number!", 5)
      res shouldBe 6
    }

  }

}
