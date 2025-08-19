package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
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
import org.slf4j.event.Level

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
      .withManualStart

  "SVs" should {
    "Dump their identities upon initialization" in { implicit env =>
      {
        val backends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
        val svIdentitiesLogLineRegex = "Wrote node identities to directory.*at path: (.*\\.json)".r

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.Level(Level.INFO))(
          initDso(),
          logEntries => {
            forAll(backends) { backend =>
              {
                forAtLeast(1, logEntries)(logEntry => {
                  inside((logEntry.loggerName, logEntry.message)) {
                    case (name, svIdentitiesLogLineRegex(filename))
                        if name.contains(s"SV=${backend.name}") =>
                      val dir = File(testDumpOutputDir(backend.config))
                      val file = dir / filename
                      val jsonString = file.contentAsString
                      val parsed = SynchronizerNodeIdentities
                        .fromHttp(
                          io.circe.parser.decode[http.SynchronizerNodeIdentities](jsonString).value
                        )
                        .value
                      val ids = backend.getSynchronizerNodeIdentitiesDump()
                      // The authorized store snapshots are not always byte-for-byte equal
                      // TODO(#794): Figure out why and if we can improve the checking here
                      scrubAuthorizedStoreSnapshots(ids) shouldBe scrubAuthorizedStoreSnapshots(
                        parsed
                      ) withClue
                        s"Identities dump for sv ${backend.name} is consistent with identities fetched through the endpoint"
                  }
                })
              }
            }
          },
        )
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
