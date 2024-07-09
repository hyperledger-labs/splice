// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

import java.nio.file.Path

sealed abstract class BackupDumpConfig {
  def locationDescription: String
}

object BackupDumpConfig {
  final case class Directory(
      directory: Path
  ) extends BackupDumpConfig {
    override val locationDescription = s"directory $directory"
  }

  final case class Gcp(
      bucket: GcpBucketConfig,
      prefix: Option[String],
  ) extends BackupDumpConfig {
    override val locationDescription = s"GCP bucket ${bucket.bucketName}"
  }
}
