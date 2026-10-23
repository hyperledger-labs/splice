// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.votesbackfilling

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.scan.store.db.DbVotesStore.{
  PeerVoteResult,
  VotesBackfillCursor,
}

import scala.concurrent.Future

/** What [[VotesStoreBackfilling]] needs from the votes store. */
trait VotesBackfillingStore {

  /** The record time of the first transaction that the ingestion of the votes store has seen.
    * Vote results from before that time have to be backfilled.
    */
  def getFirstIngestedRecordTime()(implicit tc: TraceContext): Future[Option[CantonTimestamp]]

  /** Where backfilling should resume from, or `None` if it has not started yet. */
  def getBackfillingCursor()(implicit tc: TraceContext): Future[Option[VotesBackfillCursor]]

  /** Inserts vote results backfilled from a peer, and persists `cursor` in the same transaction,
    * so that backfilling never inserts the same page twice.
    */
  def insertPeerVoteResults(rows: Seq[PeerVoteResult], cursor: VotesBackfillCursor)(implicit
      tc: TraceContext
  ): Future[Unit]

  def isBackfillingComplete()(implicit tc: TraceContext): Future[Boolean]

  def markBackfillingComplete()(implicit tc: TraceContext): Future[Unit]
}
