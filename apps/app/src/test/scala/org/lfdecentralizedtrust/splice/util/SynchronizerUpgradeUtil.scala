package org.lfdecentralizedtrust.splice.util

import java.nio.file.{Path, Paths}

object SynchronizerUpgradeUtil {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val migrationDumpDir: Path = testDumpDir.resolve(s"domain-migration-dump")

  def migrationTestDumpDir(node: String) = {
    val dumpDir = migrationDumpDir.resolve(s"$node")
    if (!dumpDir.toFile.exists()) {
      dumpDir.toFile.mkdirs()
    }
    dumpDir
  }

}
