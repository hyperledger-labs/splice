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
      startAllSync(sv1Scan, sv1Validator, sv1, sv2Validator, sv2)
      sv2.getSvcInfo()
    }
    clue("stop sv2") {
      sv2.stop()
    }
    clue(
      "start an sv2 clone without the onboarding config and verify that it sees the same SvcInfo"
    ) {
      sv2Onboarded.start()
      sv2Onboarded.waitForInitialization()
      val svcInfoFromSv2Onboarded = sv2Onboarded.getSvcInfo()
      svcInfoFromSv2Onboarded shouldBe svcInfoFromSv2
    }
  }
}
