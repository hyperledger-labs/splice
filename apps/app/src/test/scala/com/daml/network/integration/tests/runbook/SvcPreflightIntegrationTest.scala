package com.daml.network.integration.tests.runbook

import com.daml.network.LiveDevNetTest
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Preflight test that makes sure that all SVs have initialized fine.
  */
class SvcPreflightIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "all SVs are online and reachable via HTTP" taggedAs LiveDevNetTest in { implicit env =>
    env.svs.remote.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        sv.getSvcInfo()
      }
    )
  }

  "we can use the admin API of all SVs" taggedAs LiveDevNetTest in { implicit env =>
    env.svs.local.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        sv.listOngoingValidatorOnboardings()
      }
    )
  }
}
