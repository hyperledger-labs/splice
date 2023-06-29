package com.daml.network.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

import java.nio.file.Path

sealed abstract class BackupDumpConfig {
  def locationDescription: String

  // TODO(#6228): make this required once it is also supported for AcsStoreDumps
  def backupInterval: Option[NonNegativeFiniteDuration]
}

object BackupDumpConfig {
  final case class Directory(
      directory: Path,
      override val backupInterval: Option[NonNegativeFiniteDuration],
  ) extends BackupDumpConfig {
    override val locationDescription = s"directory $directory"
  }
  final case class Gcp(
      bucket: GcpBucketConfig,
      prefix: Option[String],
      override val backupInterval: Option[NonNegativeFiniteDuration],
  ) extends BackupDumpConfig {
    override val locationDescription = s"GCP bucket ${bucket.bucketName}"
  }
}
