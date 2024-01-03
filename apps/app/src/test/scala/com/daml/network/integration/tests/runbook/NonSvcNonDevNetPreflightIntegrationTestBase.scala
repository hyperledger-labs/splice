package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestConsoleEnvironment,
  CNNodeIntegrationTestWithSharedEnvironment,
}
import com.daml.network.util.DataExportTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

// Integration test for everything from a core deployment that is not part of an SV node
final class NonSvcNonDevNetPreflightIntegrationTestBase
    extends CNNodeIntegrationTestWithSharedEnvironment
    with DataExportTestUtil {

  // For now treating this as a non-SV app since it is really run directly as the SVC
  // rather than as SV-1. It just happens to be in SV-1's namespace.
  def splitwellClient(implicit env: CNNodeTestConsoleEnvironment) = rsw("splitwell")
  def splitwellValidatorClient(implicit env: CNNodeTestConsoleEnvironment) = vc(
    "splitwellValidator"
  )
  def validator1Client(implicit env: CNNodeTestConsoleEnvironment) = vc("validator1")

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "Check readiness of non-SV applications" in { implicit env =>
    eventually() {
      forAll(
        Seq(
          validator1Client,
          splitwellValidatorClient,
          splitwellClient,
        )
      )(_.httpReady shouldBe true)
    }
  }

  "Check that there is a recent participant identities backup on GCP for validator1" in { _ =>
    testRecentParticipantIdentitiesDump("validator1")
  }

  "Check that there is a recent participant identities backup on GCP for splitwell validator" in {
    _ =>
      testRecentParticipantIdentitiesDump("splitwell")
  }
}
