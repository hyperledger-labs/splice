package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.SvTestUtil

import com.digitalasset.canton.integration.BaseEnvironmentDefinition

/** Integration test that onboards an SV then starts a clone without the onboarding config.
  */
class SvOnboardingConfigIntegrationTest extends IntegrationTest with SvTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "include/svs/sv2-onboarded.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withManualStart

  "start previously onboarded participant without onboarding config" in { implicit env =>
    val dsoInfoFromSv2 = clue("Start sv1, sv2 and get DsoInfo from sv2") {
      startAllSync(
        sv1ScanBackend,
        sv1ValidatorBackend,
        sv1Backend,
        sv2ScanBackend,
        sv2ValidatorBackend,
        sv2Backend,
      )
      sv2Backend.getDsoInfo()
    }
    clue("stop sv2") {
      sv2Backend.stop()
    }
    clue(
      "start an sv2 clone without the onboarding config and verify that it sees the same DsoInfo"
    ) {
      sv2OnboardedBackend.start()
      sv2OnboardedBackend.waitForInitialization()
      val dsoInfoFromSv2Onboarded = sv2OnboardedBackend.getDsoInfo()
      dsoInfoFromSv2Onboarded shouldEqual dsoInfoFromSv2
    }
  }

  "An onboarded SV can initialize even if its onboarding sponsor is down" in { implicit env =>
    startAllSync(
      sv1Backend,
      sv2Backend,
    )
    clue("Stopping SV1 and SV2") {
      sv1Backend.stop()
      sv2Backend.stop()
    }
    clue("SV2 can start back up without changing its onboarding config") {
      sv2Backend.startSync()
    }
  }

}
