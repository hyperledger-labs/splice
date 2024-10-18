package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import java.nio.file.{Files, Path, Paths}

final class CombinedDumpDirectoryExportIntegrationTest extends IntegrationTest {

  private val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  // Not using temp-files so test-generated outputs are easy to inspect.
  private val testDumpOutputDir: Path = testDumpDir.resolve("test-outputs")

  // we write out participant identities dumps manually
  // because it give us more convenient control over file name and storage location
  def writeParticipantDump(nodeName: String, content: String)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val now = env.environment.clock.now
    val filename = s"${nodeName}_participant_dump_${now}.json"
    import better.files.File
    val dumpFile =
      File(testDumpOutputDir) / filename
    Files.createDirectories(dumpFile.path.getParent())
    dumpFile.overwrite(content)
  }

  "sv1" should {
    "produce a participant identities dump via a download from the ValidatorApp admin api" in {
      implicit env =>
        val svParticipantDump = clue("Getting participant identities dump from SV1") {
          sv1ValidatorBackend.dumpParticipantIdentities()
        }
        writeParticipantDump("sv1", svParticipantDump.toJson.spaces2)
    }
  }
  "alice" should {
    "produce a participant identities dump via a download from the ValidatorApp admin api" in {
      implicit env =>
        val validatorParticipantDump =
          clue("Getting participant identities dump from Alice's validator") {
            aliceValidatorBackend.dumpParticipantIdentities()
          }
        writeParticipantDump("alice", validatorParticipantDump.toJson.spaces2)
    }
  }
}
