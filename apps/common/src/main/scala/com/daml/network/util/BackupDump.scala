package com.daml.network.util

import better.files.File
import com.daml.network.config.BackupDumpConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

import java.nio.file.{Path, Paths}

object BackupDump {

  /** Blocking function to write a string to a file within the given backup dump location.
    *
    * Returns the path to the written file as a string.
    */
  def write(
      config: BackupDumpConfig,
      filename: Path,
      content: String,
      loggerFactory: NamedLoggerFactory,
  )(implicit tc: TraceContext): Path =
    config match {
      case BackupDumpConfig.Directory(directory, _) =>
        val file = directory.resolve(filename)
        writeToPath(file, content).path
      case BackupDumpConfig.Gcp(bucketConfig, prefix, _) =>
        val gcpBucket = new GcpBucket(bucketConfig, loggerFactory)
        val path = prefix.fold(filename)(prefix => Paths.get(prefix).resolve(filename))
        gcpBucket.dumpStringToBucket(content, path)
        path
    }

  def writeToPath(path: Path, content: String): File = {
    import better.files.File
    val file = File(path)
    file.parent.createDirectories()
    file.write(content)
    file
  }

  def fileExists(path: Path): Boolean = {
    import better.files.File
    File(path).exists
  }
}
