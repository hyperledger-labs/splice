package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinTestConsoleEnvironment,
  CoinIntegrationTestWithSharedEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Preflight test that makes sure that all SVs have initialized fine.
  */
class SvcPreflightIntegrationTest extends CoinIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName(),
      sys.env("NETWORK_APPS_ADDRESS"),
    )

  "all SVs are online and reachable via HTTP" taggedAs LiveDevNetTest in { implicit env =>
    env.svs.remote.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        sv.getDebugInfo()
      }
    )
  }
}
