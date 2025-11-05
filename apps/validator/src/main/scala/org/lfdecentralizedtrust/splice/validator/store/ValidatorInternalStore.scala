// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.validator.store.db.ScanConfigTables.ScanConfigRow

import scala.concurrent.Future

/** Defines the core operations for managing internal, non-Daml-specific data
  * used by the validator (e.g., Scan Node configuration).
  */
trait ValidatorInternalStore {

  /** Replaces the entire existing list of Scan Node configurations with the new list provided.
    * This operation is typically performed within a transaction:
    * 1. DELETE all existing rows in 'validator_scan_config'.
    * 2. INSERT all rows from the provided list of `ScanConfigRow` (svName-url pairs).
    *
    * @param rows The complete new list of configurations (svName-url pairs) to save.
    * @param tc The trace context.
    * @return A Future completing with Unit on success.
    */
  def setScanConfigs(rows: Seq[ScanConfigRow])(implicit tc: TraceContext): Future[Unit]

  /** Retrieves all configured ScanConfigRows (svName-url pairs) from the internal database.
    *
    * @param tc The trace context.
    * @return A Future holding a sequence of all configured ScanConfigRows.
    */
  def getScanConfigs()(implicit tc: TraceContext): Future[Seq[ScanConfigRow]]
}
