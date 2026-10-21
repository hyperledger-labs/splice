// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

class FakeHistoryReadiness(
    backfilled: => Boolean = false,
    start: => Option[HistoryStart] = None,
    snapshotSeeded: => Boolean = false,
) extends HistoryReadiness {
  override def isHistoryBackfilled(migrationId: Long)(implicit tc: TraceContext): Future[Boolean] =
    Future.successful(backfilled)

  override def historyStart()(implicit tc: TraceContext): Future[Option[HistoryStart]] =
    Future.successful(start)

  override def isAcsSnapshotSeeded(implicit tc: TraceContext): Future[Boolean] =
    Future.successful(snapshotSeeded)
}
