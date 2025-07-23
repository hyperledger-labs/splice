package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.{EnvironmentDefinition, InitialPackageVersions}
import SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.SvTestUtil

trait SvIntegrationTestBase extends IntegrationTest with SvTestUtil {

  protected val amuletDarPath =
    s"daml/dars/splice-amulet-${InitialPackageVersions.initialPackageVersion(DarResources.amulet)}.dar"
  protected val dsoGovernanceDarPath =
    s"daml/dars/splice-dso-governance-${InitialPackageVersions.initialPackageVersion(DarResources.dsoGovernance)}.dar"

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        // Some tests rely on those DARs being present without starting the SV/validator app which usually upload these.
        sv2Backend.participantClient.upload_dar_unless_exists(dsoGovernanceDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(amuletDarPath)
      })
      .withManualStart
}
