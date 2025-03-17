package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.BackupDumpConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllSvAppConfigs
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.SvIdentitiesDumpIntegrationTest.testDumpOutputDir
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import monocle.macros.syntax.lens.*
import better.files.*
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.sv.migration.SynchronizerNodeIdentities
import com.google.protobuf.ByteString

import java.nio.file.{Path, Paths}

class SvIdentitiesDumpIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override def environmentDefinition: SpliceEnvironmentDefinition =
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
