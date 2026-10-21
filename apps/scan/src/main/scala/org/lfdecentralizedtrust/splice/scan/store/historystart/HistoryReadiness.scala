// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.UpdateHistory

import scala.concurrent.{ExecutionContext, Future}

trait HistoryReadiness {
  def isHistoryBackfilled(migrationId: Long)(implicit tc: TraceContext): Future[Boolean]

  def historyStart()(implicit tc: TraceContext): Future[Option[HistoryStart]]

  def isAcsSnapshotSeeded(implicit tc: TraceContext): Future[Boolean]

  final def acsSnapshotsMayStart(migrationId: Long)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Boolean] =
    isHistoryBackfilled(migrationId).flatMap { backfilled =>
      if (backfilled) Future.successful(true)
      else
        historyStart().flatMap {
          case Some(HistoryStart.From(_)) => isAcsSnapshotSeeded
          case Some(HistoryStart.Genesis) | None => Future.successful(false)
        }
    }
}

class ScanHistoryReadiness(updateHistory: UpdateHistory, scanHistoryStart: ScanHistoryStart)
    extends HistoryReadiness {

  override def isHistoryBackfilled(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    if (updateHistory.isReady) updateHistory.isHistoryBackfilled(migrationId)
    else Future.successful(false)

  override def historyStart()(implicit tc: TraceContext): Future[Option[HistoryStart]] =
    scanHistoryStart.get

  override def isAcsSnapshotSeeded(implicit tc: TraceContext): Future[Boolean] =
    Future.successful(false)
}
