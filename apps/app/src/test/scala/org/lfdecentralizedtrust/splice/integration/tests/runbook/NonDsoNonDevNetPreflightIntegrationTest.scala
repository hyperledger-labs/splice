package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  IntegrationTestWithSharedEnvironment,
}
import org.lfdecentralizedtrust.splice.util.DataExportTestUtil

// Integration test for everything from a core deployment that is not part of an SV node
final class NonDsoNonDevNetPreflightIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with DataExportTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  // For now treating this as a non-SV app since it is really run directly as the DSO
  // rather than as SV-1. It just happens to be in SV-1's namespace.
  def splitwellClient(implicit env: SpliceTestConsoleEnvironment) = rsw("splitwell")
  def splitwellValidatorClient(implicit env: SpliceTestConsoleEnvironment) = vc(
    "splitwellValidator"
  )
  def validator1Client(implicit env: SpliceTestConsoleEnvironment) = vc("validator1")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
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
