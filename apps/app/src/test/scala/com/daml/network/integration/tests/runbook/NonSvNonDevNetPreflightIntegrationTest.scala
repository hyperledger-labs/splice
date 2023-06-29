package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestConsoleEnvironment,
  CNNodeIntegrationTestWithSharedEnvironment,
}

import com.digitalasset.canton.integration.BaseEnvironmentDefinition

// Integration test for everything that is not part of an SV node.
final class NonSvNonDevNetPreflightIntegrationTestBase
    extends CNNodeIntegrationTestWithSharedEnvironment {

  // For now treating this as a non-SV app since it is really run directly as the SVC
  // rather than as SV-1. It just happens to be in SV-1's namespace.
  def directoryClient(implicit env: CNNodeTestConsoleEnvironment) = rdp("directory")
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
          directoryClient,
          validator1Client,
          splitwellValidatorClient,
        )
      )(_.httpReady shouldBe true)
      splitwellClient.health.status.isActive shouldBe Some(true)
    }
  }
}
