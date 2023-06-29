package com.daml.network.integration.tests

import com.daml.network.config.{CNNodeConfigTransforms, BackupDumpConfig, GcpBucketConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.GcpBucket
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.file.Paths

abstract class AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[T <: BackupDumpConfig]
    extends AcsStoreDumpExportTimeBasedIntegrationTestBase {

  protected def acsStoreDumpConfig: T

  protected def readDump(filename: String): String

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        (_, conf) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs_(c =>
            c.copy(acsStoreDump = Some(acsStoreDumpConfig))
          )(conf),
      )

  "sv1" should {
    "produce an ACS store dump via triggering the writing to a file" in { implicit env =>
      val expectedContractIds = createTestContracts()

      eventually() {
        // Note: use eventually to ensure if the propagation to the SvSvcStore has not completed
        val response = sv1Backend.triggerAcsDump()

        val dump = readDump(response.filename)
        val jsonDump = io.circe.parser
          .decode[http.GetAcsStoreDumpResponse](dump)
          .fold(
            err => throw new IllegalArgumentException(s"Failed to parse dump: $err"),
            result => result,
          )
        checkDump(expectedContractIds, jsonDump)
      }
    }
  }

}

final class DirectoryAcsStoreDumpTriggerExportTimeBasedIntegrationTest
    extends AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[BackupDumpConfig.Directory] {
  override def acsStoreDumpConfig = BackupDumpConfig.Directory(Paths.get("dumps/testing"), None)

  override def readDump(filename: String) = {
    import better.files.File
    val dumpDir = File(acsStoreDumpConfig.directory)
    val dumpFile = dumpDir / filename
    dumpFile.contentAsString
  }

}

final class GcpBucketAcsStoreDumpTriggerExportTimeBasedIntegrationTest
    extends AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[BackupDumpConfig.Gcp] {
  override def acsStoreDumpConfig =
    BackupDumpConfig.Gcp(GcpBucketConfig.inferForTesting, None, None)
  val bucket = new GcpBucket(acsStoreDumpConfig.bucket, loggerFactory)
  override def readDump(filename: String) = bucket.readStringFromBucket(Paths.get(filename))
}
