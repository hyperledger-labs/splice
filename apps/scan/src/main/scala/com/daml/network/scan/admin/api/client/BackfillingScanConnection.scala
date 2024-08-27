// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.api.client

import com.daml.network.environment.ledger.api.LedgerClient
import com.daml.network.store.HistoryBackfilling.MigrationInfo
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait BackfillingScanConnection extends FlagCloseableAsync {

  def getMigrationInfo(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[MigrationInfo]

  def getUpdatesBefore(
      migrationId: Long,
      domainId: DomainId,
      before: CantonTimestamp,
      count: Int,
  )(implicit tc: TraceContext): Future[Seq[LedgerClient.GetTreeUpdatesResponse]]
}
