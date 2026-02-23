// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{PhysicalSynchronizerId, SequencerId, SynchronizerId}
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, TopologyStoreId}
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import com.digitalasset.canton.topology.transaction.{
  GrpcConnection,
  LsuAnnouncement,
  LsuSequencerConnectionSuccessor,
  TopologyChangeOp,
  TopologyMapping,
}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  TopologyResult,
  TopologyTransactionType,
}

import scala.concurrent.{ExecutionContext, Future}

trait LsuTopologyAdminConnection {
  this: TopologyAdminConnection =>

  def lookupSequencerSuccessors(synchronizerId: SynchronizerId, sequencerId: SequencerId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[TopologyResult[LsuSequencerConnectionSuccessor]]] = runCmd(
    TopologyAdminCommands.Read.ListLsuSequencerConnectionSuccessor(
      BaseQuery(
        TopologyStoreId.Synchronizer(synchronizerId),
        proposals = false,
        timeQuery = TimeQuery.HeadState,
        ops = Some(TopologyChangeOp.Replace),
        filterSigningKey = "",
        protocolVersion = None,
      ),
      sequencerId.filterString,
    )
  ).map(_.headOption.map(r => TopologyResult(r.context, r.item)))

  def ensureSequencerSuccessor(
      synchronizerId: SynchronizerId,
      sequencerId: SequencerId,
      connection: GrpcConnection,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[TopologyResult[LsuSequencerConnectionSuccessor]] = {
    retryProvider.ensureThat(
      RetryFor.Automation,
      s"sequencer_successor_$sequencerId",
      s"sequencer successor for $sequencerId is published with connection $connection",
      lookupSequencerSuccessors(synchronizerId, sequencerId).map { result =>
        result.filter(_.mapping.connection == connection).toRight(result)
      },
      (previous: Option[TopologyResult[LsuSequencerConnectionSuccessor]]) => {
        logger.info(s"Adding sequencer $sequencerId successor with connection $connection")
        (previous match {
          case Some(successor) =>
            proposeMapping(
              synchronizerId,
              successor.mapping.copy(connection = connection),
              successor.base.serial + PositiveInt.one,
              isProposal = false,
            )
          case None =>
            proposeMapping(
              synchronizerId,
              LsuSequencerConnectionSuccessor(sequencerId, synchronizerId, connection),
              PositiveInt.one,
              isProposal = false,
            )
        }).map(_ => ())
      },
      logger,
    )
  }
  def lookupSynchronizerLsuAnnouncement(
      synchronizerId: SynchronizerId,
      timeQuery: TimeQuery,
      topologyTransactionType: TopologyTransactionType,
  )(implicit
      traceContext: TraceContext,
      ec: ExecutionContext,
  ): Future[Option[TopologyResult[LsuAnnouncement]]] = {
    val announcements: Future[Seq[TopologyResult[LsuAnnouncement]]] = runCommand(
      synchronizerId,
      topologyTransactionType,
      timeQuery,
    )(baseQuery =>
      TopologyAdminCommands.Read.ListLsuAnnouncement(
        baseQuery,
        filterSynchronizerId = synchronizerId.filterString,
      )
    )
    announcements.map(_.headOption)
  }

  def ensureLsuAnnouncement(
      synchronizerId: SynchronizerId,
      upgradeTime: CantonTimestamp,
      psid: NonNegativeInt,
      protocolVersion: ProtocolVersion,
  )(implicit tc: TraceContext, ec: ExecutionContext) = ensureTopologyMappingO(
    synchronizerId,
    s"LsuAnnouncement with upgrade time $upgradeTime",
    topologyType =>
      EitherT
        .liftF(lookupSynchronizerLsuAnnouncement(synchronizerId, HeadState, topologyType))
        .subflatMap {
          case Some(announcement) if announcement.mapping.successorSynchronizerId.serial == psid =>
            Right(announcement)
          case Some(existing) => Left(existing.some)
          case None => Left(None)
        },
    { (_: Option[TopologyMapping]) =>
      Right(
        LsuAnnouncement(PhysicalSynchronizerId(synchronizerId, psid, protocolVersion), upgradeTime)
      )
    },
    isProposal = true,
    retryFor = RetryFor.Automation,
  )

}
