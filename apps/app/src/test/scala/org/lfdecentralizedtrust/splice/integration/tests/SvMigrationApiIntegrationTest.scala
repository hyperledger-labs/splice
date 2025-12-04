package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import better.files.File.*
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.console.CommandFailure
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.DecentralizedSynchronizerMigrationIntegrationTest.migrationDumpDir
import org.lfdecentralizedtrust.splice.integration.tests.SvMigrationApiIntegrationTest.{
  directoryForDump,
  migrationDumpPathForSv,
}

import java.time.Instant
import java.time.temporal.ChronoUnit

class SvMigrationApiIntegrationTest extends SvIntegrationTestBase {

  override def environmentDefinition: EnvironmentDefinition =
    super.environmentDefinition.addConfigTransform((_, conf) =>
      ConfigTransforms.updateAllSvAppConfigs((name, config) =>
        config.copy(
          domainMigrationDumpPath = Some(migrationDumpPathForSv(name).path)
        )
      )(conf)
    )

  "we can trigger a migration dump on a non paused sync" in { implicit env =>
    initDso()

    clue("triggering the dump fails if no timestamp is provided as the sync must be paused") {
      loggerFactory.assertLoggedWarningsAndErrorsSeq(
        a[CommandFailure] should be thrownBy {
          sv1Backend.triggerDecentralizedSynchronizerMigrationDump(0)
        },
        lines => forAll(lines)(_.errorMessage should include("HTTP 400 Bad Request")),
      )
    }
    val dumpTimestamp = Instant.now().minus(1, ChronoUnit.MINUTES)
    val expectedDirectory = directoryForDump(sv1Backend.name, dumpTimestamp)
    clue(s"export is written at ${expectedDirectory.toString()}") {
      sv1Backend.triggerDecentralizedSynchronizerMigrationDump(
        0,
        dumpTimestamp.some,
      )
      expectedDirectory.exists shouldBe true
      (expectedDirectory / "domain_migration_dump.json").exists shouldBe true
      (expectedDirectory / s"$dumpTimestamp-genesis-state").exists shouldBe true
      (expectedDirectory / s"$dumpTimestamp-acs-snapshot").exists shouldBe true
    }
  }

}

object SvMigrationApiIntegrationTest {

  def directoryForDump(name: String, timestamp: Instant): File = {
    migrationDumpPathForSv(
      name
    ).parent / s"export_at_${timestamp.toEpochMilli}"
  }

  private def migrationDumpPathForSv(name: String) = {
    migrationDumpDir(name) / "domain_migration_dump.json"
  }

}
