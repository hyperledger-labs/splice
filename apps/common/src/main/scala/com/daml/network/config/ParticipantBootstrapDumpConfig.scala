package com.daml.network.config

import java.nio.file.Path

sealed abstract class ParticipantBootstrapDumpConfig {
  def description: String
}

object ParticipantBootstrapDumpConfig {
  final case class File(file: Path) extends ParticipantBootstrapDumpConfig {
    override val description = s"Local file $file"
  }
  final case class Gcp(
      bucket: GcpBucketConfig,
      path: String,
  ) extends ParticipantBootstrapDumpConfig {
    override val description = s"Path $path in ${bucket.description}"
  }
}
