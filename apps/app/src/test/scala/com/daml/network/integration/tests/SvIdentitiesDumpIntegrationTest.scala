package com.daml.network.integration.tests

import com.daml.network.config.BackupDumpConfig
import com.daml.network.config.ConfigTransforms.updateAllSvAppConfigs
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.integration.tests.SvIdentitiesDumpIntegrationTest.testDumpOutputDir
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import monocle.macros.syntax.lens.*
import better.files.*
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.sv.migration.SynchronizerNodeIdentities
import com.google.protobuf.ByteString

import java.nio.file.{Path, Paths}

class SvIdentitiesDumpIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
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
          val ids = backend.getSynchronizerNodeIdentitiesDump()
          val dir = File(testDumpOutputDir(backend.config))
          val files = dir.list
          files should not be empty
          files.foreach(file => {
            val jsonString = file.contentAsString
            val parsed = SynchronizerNodeIdentities
              .fromHttp(io.circe.parser.decode[http.SynchronizerNodeIdentities](jsonString).value)
              .value
            // The authorized store snapshots are not always byte-for-byte equal
            // TODO(#11611): Figure out why and if we can improve the checking here
            scrubAuthorizedStoreSnapshots(ids) shouldBe scrubAuthorizedStoreSnapshots(
              parsed
            ) withClue s"Identities dump for sv ${backend.name} is consistent with identities fetched through the endpoint"
          })
        })
        backends.foreach(backend => File(testDumpOutputDir(backend.config)).delete())
      }
    }
  }

  private def scrubAuthorizedStoreSnapshots(
      dump: SynchronizerNodeIdentities
  ): SynchronizerNodeIdentities = {
    dump.copy(
      participant = scrubAuthorizedStoreSnapshot(dump.participant),
      sequencer = scrubAuthorizedStoreSnapshot(dump.sequencer),
      mediator = scrubAuthorizedStoreSnapshot(dump.mediator),
    )
  }

  private def scrubAuthorizedStoreSnapshot(dump: NodeIdentitiesDump): NodeIdentitiesDump = {
    dump.copy(authorizedStoreSnapshot = ByteString.EMPTY)
  }
}

object SvIdentitiesDumpIntegrationTest {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  def testDumpOutputDir(sv: SvAppBackendConfig) =
    testDumpDir.resolve(s"test-outputs/${sv.ledgerApiUser}")
}
