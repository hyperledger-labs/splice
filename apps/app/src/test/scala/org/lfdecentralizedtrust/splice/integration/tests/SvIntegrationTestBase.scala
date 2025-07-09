package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.SvTestUtil

trait SvIntegrationTestBase extends IntegrationTest with SvTestUtil {

  protected val amuletDarPath =
    "daml/splice-amulet/.daml/dist/splice-amulet-current.dar"
  protected val dsoGovernanceDarPath =
    "daml/splice-dso-governance/.daml/dist/splice-dso-governance-current.dar"

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withManualStart
}
