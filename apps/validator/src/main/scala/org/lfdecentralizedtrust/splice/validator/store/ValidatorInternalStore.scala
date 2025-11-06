// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.validator.store.db.ValidatorInternalTables.ScanConfigRow

import scala.concurrent.Future

trait ValidatorInternalStore {

  def setScanConfigs(rows: Seq[ScanConfigRow])(implicit tc: TraceContext): Future[Unit]

  def getScanConfigs()(implicit tc: TraceContext): Future[Seq[ScanConfigRow]]
}
