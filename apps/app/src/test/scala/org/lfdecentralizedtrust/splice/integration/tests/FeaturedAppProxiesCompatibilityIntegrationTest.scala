package org.lfdecentralizedtrust.splice.integration.tests

import better.files.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

import scala.util.matching.Regex

class FeaturedAppProxiesCompatibilityIntegrationTest extends IntegrationTest {

  override lazy val resetRequiredTopologyState = false

  private val proxiesDarCurrentPath = File(
    "daml/splice-util-featured-app-proxies/src/main/resources/dar/splice-util-featured-app-proxies-current.dar"
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart

  "upload all splice-util-featured-app-proxies.dar files w/o error" in { implicit env =>
    val darPattern: Regex = """splice-util-featured-app-proxies.*\.dar""".r

    val darPaths = File("daml/dars").listRecursively
      .filter(f => f.isRegularFile && darPattern.matches(f.name))
      .toSeq
      .appended(proxiesDarCurrentPath)

    darPaths
      .foreach { f =>
        splitwellBackend.participantClientWithAdminToken.upload_dar_unless_exists(f.toString())
      }
  }
}
