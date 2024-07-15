package com.daml.network.integration.tests

import com.daml.network.integration.EnvironmentDefinition
import SpliceTests.IntegrationTest
import com.daml.network.util.SvTestUtil

trait SvIntegrationTestBase extends IntegrationTest with SvTestUtil {

  protected val amuletDarPath =
    "daml/splice-amulet/.daml/dist/splice-amulet-current.dar"
  protected val dsoGovernanceDarPath =
    "daml/splice-dso-governance/.daml/dist/splice-dso-governance-current.dar"

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .withAdditionalSetup(implicit env => {
        // Some tests rely on those DARs being present without starting the SV/validator app which usually upload these.
        sv2Backend.participantClient.upload_dar_unless_exists(dsoGovernanceDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(amuletDarPath)
      })
}
