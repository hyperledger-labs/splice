// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import java.nio.file.Path

sealed abstract class TopologySnapshotConfig {
  def locationDescription: String
}

object TopologySnapshotConfig {
  final case class Directory(
      directory: Path
  ) extends TopologySnapshotConfig {
    override val locationDescription = s"directory $directory"
  }

  final case class Gcp(
      bucket: GcpBucketConfig,
      prefix: Option[String],
  ) extends TopologySnapshotConfig {
    override val locationDescription = s"GCP bucket ${bucket.bucketName}"
  }
}
