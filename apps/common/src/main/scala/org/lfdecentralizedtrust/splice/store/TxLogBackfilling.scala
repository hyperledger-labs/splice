// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.Outcome
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

final class TxLogBackfilling(
    store: MultiDomainAcsStore,
    updateHistory: UpdateHistory,
    batchSize: Int,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging {

  private val currentMigrationId = updateHistory.domainMigrationInfo.currentMigrationId
  // ACS import updates should not be included in txlog
  private val sourceHistory = updateHistory.sourceHistory(excludeAcsImportUpdates = true)
  private val destinationHistory = store.destinationHistory
  private val backfilling =
    new HistoryBackfilling(
      destinationHistory,
      sourceHistory,
      currentMigrationId = currentMigrationId,
      batchSize = batchSize,
      loggerFactory,
    )

  def backfill()(implicit tc: TraceContext): Future[Outcome] = {
    backfilling.backfill()
  }
}
