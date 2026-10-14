// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import org.lfdecentralizedtrust.splice.store.UpdateHistory

import scala.concurrent.{ExecutionContext, Future}

trait HistoryStartSources {
  def isFoundingSv: Boolean

  def historyBackfillEnabled: Boolean

  def historyBackfilledFromGenesis(implicit tc: TraceContext): Future[Option[Boolean]]

  def dsoPartyHostedSince(implicit tc: TraceContext): Future[Option[CantonTimestamp]]
}

class ParticipantHistoryStartSources(
    override val isFoundingSv: Boolean,
    override val historyBackfillEnabled: Boolean,
    updateHistory: UpdateHistory,
    migrationId: Long,
    participantAdminConnection: ParticipantAdminConnection,
    synchronizerAlias: SynchronizerAlias,
    participantId: ParticipantId,
    dsoParty: PartyId,
)(implicit ec: ExecutionContext)
    extends HistoryStartSources {

  override def historyBackfilledFromGenesis(implicit
      tc: TraceContext
  ): Future[Option[Boolean]] =
    if (!updateHistory.isReady) Future.successful(None)
    else updateHistory.isHistoryBackfilled(migrationId).map(Some(_))

  override def dsoPartyHostedSince(implicit tc: TraceContext): Future[Option[CantonTimestamp]] =
    for {
      synchronizerId <- participantAdminConnection.getSynchronizerId(synchronizerAlias)
      added <- participantAdminConnection
        .getDsoPartyToParticipantTransaction(synchronizerId, participantId, dsoParty)
        .value
    } yield added.map(mapping => CantonTimestamp.assertFromInstant(mapping.base.validFrom))
}
