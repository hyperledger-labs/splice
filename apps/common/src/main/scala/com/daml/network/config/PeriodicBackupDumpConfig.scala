package com.daml.network.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

final case class PeriodicBackupDumpConfig(
    location: BackupDumpConfig,
    backupInterval: NonNegativeFiniteDuration,
)
