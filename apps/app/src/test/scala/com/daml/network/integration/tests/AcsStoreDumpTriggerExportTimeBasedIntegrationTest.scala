package com.daml.network.integration.tests

import com.daml.network.config.{BackupDumpConfig, CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions as http
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.GcpBucket
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.nio.file.{Path, Paths}

abstract class AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[T <: BackupDumpConfig]
    extends AcsStoreDumpExportTimeBasedIntegrationTestBase {

  protected def acsStoreDumpConfig(testContext: String): T

  protected def readDump(filename: String): String

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    simpleTopologyWithSimtimeTuned
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs_(c =>
          c.copy(acsStoreDump = Some(acsStoreDumpConfig(conf.name.value)))
        )(conf)
      )

  "sv1" should {
    "produce an ACS store dump via triggering through the http endpoint" in { implicit env =>
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

// triggering of dump to directory is tested in CombinedDumpDirectoryExportTimeBasedIntegrationTest

final class GcpBucketAcsStoreDumpTriggerExportTimeBasedIntegrationTest
    extends AcsStoreDumpTriggerExportTimeBasedIntegrationTestBase[BackupDumpConfig.Gcp] {
  val bucketConfig = GcpBucketConfig.inferForTesting

  override def acsStoreDumpConfig(testContext: String) =
    BackupDumpConfig.Gcp(bucketConfig, prefix = Some(testContext), None)

  override def readDump(filename: String) = {
    val bucket = new GcpBucket(bucketConfig, loggerFactory)
    bucket.readStringFromBucket(Paths.get(filename))
  }
}

object AcsStoreDumpTriggerExportTimeBasedIntegrationTest {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  // Not using temp-files so test-generated outputs are easy to inspect.
  val testDumpOutputDir: Path = testDumpDir.resolve("test-outputs")
}
