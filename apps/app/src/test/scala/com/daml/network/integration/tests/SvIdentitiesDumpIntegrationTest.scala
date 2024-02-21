package com.daml.network.integration.tests

import com.daml.network.config.BackupDumpConfig
import com.daml.network.config.CNNodeConfigTransforms.updateAllSvAppConfigs
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.tests.SvIdentitiesDumpIntegrationTest.testDumpOutputDir
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*
import better.files.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.migration.DomainNodeIdentities

import java.nio.file.{Path, Paths}

class SvIdentitiesDumpIntegrationTest extends CNNodeIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, conf) =>
        updateAllSvAppConfigs((_, svConf) =>
          svConf
            .focus(_.identitiesDump)
            .modify(_ => Some(BackupDumpConfig.Directory(testDumpOutputDir(svConf))))
        )(conf)
      )

  "SVs" should {
    "Dump their identities upon initialization" in { implicit env =>
      {
        val backends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
        backends.foreach(backend => {
          val ids = backend.getDomainNodeIdentitiesDump()
          val dir = File(testDumpOutputDir(backend.config))
          val files = dir.list
          files should not be empty
          files.foreach(file => {
            val jsonString = file.contentAsString
            val parsed = DomainNodeIdentities
              .fromHttp(io.circe.parser.decode[http.DomainNodeIdentities](jsonString).value)
              .value
            ids shouldBe parsed withClue s"Identities dump for sv ${backend.name} is consistent with identities fetched through the endpoint"
          })
        })

        backends.foreach(backend => File(testDumpOutputDir(backend.config)).delete())
      }
    }

  }

}

object SvIdentitiesDumpIntegrationTest {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  def testDumpOutputDir(sv: SvAppBackendConfig) =
    testDumpDir.resolve(s"test-outputs/${sv.ledgerApiUser}")
}
