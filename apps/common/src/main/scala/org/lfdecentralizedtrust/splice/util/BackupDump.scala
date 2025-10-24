// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import better.files.File
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Decoder
import org.lfdecentralizedtrust.splice.config.BackupDumpConfig

import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import scala.util.Try

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
      case BackupDumpConfig.Directory(directory) =>
        val file = directory.resolve(filename)
        writeToPath(file, content).path
      case BackupDumpConfig.Gcp(bucketConfig, prefix) =>
        val gcpBucket = new GcpBucket(bucketConfig, loggerFactory)
        val path = prefix.fold(filename)(prefix => Paths.get(prefix).resolve(filename))
        gcpBucket.dumpStringToBucket(content, path)
        path
    }

  def writeToPath(path: Path, content: String): File = {
    import better.files.File
    val file = File(path)
    file.parent.createDirectories()
    file.write(content)(File.OpenOptions.default, StandardCharsets.UTF_8)
    file
  }

  def fileExists(path: Path): Boolean = {
    import better.files.File
    File(path).exists
  }

  def readFromPath[T: Decoder](path: Path): Try[T] = Try {
    val dumpFile = better.files.File(path)
    if (!dumpFile.exists) {
      throw new FileNotFoundException(s"Failed to find dump file at $path")
    } else {
      val jsonString: String = dumpFile.contentAsString(StandardCharsets.UTF_8)
      io.circe.parser.decode[T](jsonString) match {
        case Left(error) =>
          throw new IllegalArgumentException(
            s"Failed to parse dump file at $path: $error"
          )
        case Right(dump) => dump
      }
    }
  }

}
