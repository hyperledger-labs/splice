// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

object ValidatorInternalTables {

  val tableName: String = "validator_scan_config"

  case class ScanConfigRow(
      svName: String,
      scanUrl: String,
      restart_count: Int,
  )
}
