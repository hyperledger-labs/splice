package com.daml.network.config

import java.nio.file.Path

sealed abstract class ParticipantBootstrapDumpConfig {
  def description: String
}

object ParticipantBootstrapDumpConfig {
  final case class File(file: Path, newParticipantIdentifier: Option[String] = None)
      extends ParticipantBootstrapDumpConfig {
    override val description = s"Local file $file"
  }
  // We don't plan to support additional sources in the short term, but who knows.
}
