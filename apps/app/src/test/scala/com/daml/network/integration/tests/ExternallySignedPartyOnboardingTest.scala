package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class ExternallySignedPartyOnboardingTest
    extends IntegrationTest
    with HasExecutionContext
    with ExternallySignedPartyTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] = {
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "a ccsp provider" should {

    "should be able to onboard a party with externally signed topology transactions" in {
      implicit env =>
        val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)

        eventually() {
          aliceValidatorBackend.participantClient.parties
            .hosted(filterParty = party.filterString) should not be empty
        }
    }
  }

}
