package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.SvTestUtil

import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Integration test that onboards an SV then starts a clone without the onboarding config.
  */
class SvOnboardingConfigIntegrationTest extends CNNodeIntegrationTest with SvTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "include/svs/sv2-onboarded.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withManualStart

  "start previously onboarded participant without onboarding config" in { implicit env =>
    val svcInfoFromSv2 = clue("Start sv1, sv2 and get SvcInfo from sv2") {
      startAllSync(sv1ScanBackend, sv1ValidatorBackend, sv1Backend, sv2ValidatorBackend, sv2Backend)
      sv2Backend.getSvcInfo()
    }
    clue("stop sv2") {
      sv2Backend.stop()
    }
    clue(
      "start an sv2 clone without the onboarding config and verify that it sees the same SvcInfo"
    ) {
      sv2OnboardedBackend.start()
      sv2OnboardedBackend.waitForInitialization()
      val svcInfoFromSv2Onboarded = sv2OnboardedBackend.getSvcInfo()
      svcInfoFromSv2Onboarded shouldEqual svcInfoFromSv2
    }
  }
}
