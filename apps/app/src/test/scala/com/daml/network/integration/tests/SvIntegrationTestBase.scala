package com.daml.network.integration.tests

import com.daml.network.integration.CNNodeEnvironmentDefinition
import CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.SvTestUtil

trait SvIntegrationTestBase extends CNNodeIntegrationTest with SvTestUtil {

  protected val cantonAmuletDarPath =
    "daml/canton-amulet/.daml/dist/canton-amulet-0.1.0.dar"
  protected val svcGovernanceDarPath =
    "daml/svc-governance/.daml/dist/svc-governance-0.1.0.dar"

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
      .withAdditionalSetup(implicit env => {
        // Some tests rely on those DARs being present without starting the SV/validator app which usually upload these.
        sv2Backend.participantClient.upload_dar_unless_exists(svcGovernanceDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(cantonAmuletDarPath)
      })
}
