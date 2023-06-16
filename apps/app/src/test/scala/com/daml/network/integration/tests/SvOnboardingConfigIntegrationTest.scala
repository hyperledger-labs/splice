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
        Seq("simple-topology.conf", "include/svs/sv1-onboarded.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withManualStart

  "start previously onboarded participant without onboarding config" in { implicit env =>
    val svcInfoFromSv1 = clue("Start sv1 and get SvcInfo") {
      startAllSync(sv1Scan, sv1Validator, sv1)
      val svcInfo = sv1.getSvcInfo()
      sv1.stop()
      svcInfo
    }
    clue(
      "start an sv1 clone without the onboarding config and verify that it sees the same SvcInfo"
    ) {
      sv1Onboarded.start()
      sv1Onboarded.waitForInitialization()
      val svcInfoFromSv1Onboarded = sv1Onboarded.getSvcInfo()
      svcInfoFromSv1Onboarded shouldBe svcInfoFromSv1
    }
  }
}
