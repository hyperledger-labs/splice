// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import better.files.File
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Decoder
import io.grpc.Status
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

  /** Blocking function to write bytes to a file within the given backup dump location.
    *
    * Returns the path to the written file as a string.
    */
  def writeBytes(
      config: BackupDumpConfig,
      filename: Path,
      content: Array[Byte],
      loggerFactory: NamedLoggerFactory,
  )(implicit tc: TraceContext): Path =
    config match {
      case BackupDumpConfig.Gcp(bucketConfig, prefix) =>
        val gcpBucket = new GcpBucket(bucketConfig, loggerFactory)
        val path = prefix.fold(filename)(prefix => Paths.get(prefix).resolve(filename))
        gcpBucket.dumpBytesToBucket(content, path.toString)
        path
      case _ =>
        throw Status.UNIMPLEMENTED
          .withDescription("Writing bytes works only with GCP buckets")
          .asRuntimeException()
    }

  def writeToPath(path: Path, content: String): File = {
    import better.files.File
    val file = File(path)
    withParentDirectoryFor(path) {
      // even though the default is UTF-8 the String implementation of encoding is broken so we need to explicitly set
      // StandardCharsets.UTF_8 and not Charset.defaultCharset()
      // for more details check #2864
      file.write(content)(File.OpenOptions.default, StandardCharsets.UTF_8)
    }
    file
  }

  def withParentDirectoryFor(path: Path)(f: => Unit): Unit = {
    import better.files.File
    val file = File(path)
    file.parent.createDirectories()
    f
  }

  def fileExists(path: Path): Boolean = {
    import better.files.File
    File(path).exists
  }

  def bucketExists(
      config: BackupDumpConfig,
      offset: String,
      loggerFactory: NamedLoggerFactory,
  ): Boolean = {
    config match {
      case BackupDumpConfig.Gcp(bucketConfig, prefix) =>
        val gcpBucket = new GcpBucket(bucketConfig, loggerFactory)
        val pref = prefix match {
          case Some(p) => s"$p/"
          case None => ""
        }
        val blobs = gcpBucket.listBlobsByPrefix(prefix = s"$pref$offset")
        blobs.nonEmpty
      case _ =>
        throw Status.UNIMPLEMENTED
          .withDescription("Topology snapshot works only with GCP buckets")
          .asRuntimeException()
    }
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
