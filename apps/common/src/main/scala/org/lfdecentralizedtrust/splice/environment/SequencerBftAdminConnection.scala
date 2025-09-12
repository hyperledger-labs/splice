// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.commands.SequencerBftAdminCommands
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.admin.SequencerBftAdminData
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.admin.SequencerBftAdminData.{
  PeerConnectionStatus,
  PeerEndpointHealthStatus,
}
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

trait SequencerBftAdminConnection {
  this: AppConnection =>
  implicit val ec: ExecutionContextExecutor

  def addPeerEndpoint(peer: P2PEndpoint)(implicit tc: TraceContext): Future[Unit] = {
    runCmd(
      SequencerBftAdminCommands.AddPeerEndpoint(endpoint = peer)
    )
  }

  def removePeerEndpoint(peer: P2PEndpoint.Id)(implicit tc: TraceContext): Future[Unit] = {
    runCmd(
      SequencerBftAdminCommands.RemovePeerEndpoint(endpointId = peer)
    )
  }

  def getSequencerOrderingTopology()(implicit
      tc: TraceContext
  ): Future[SequencerBftAdminData.OrderingTopology] = {
    runCmd(
      SequencerBftAdminCommands.GetOrderingTopology()
    )
  }

  def listCurrentPeerEndpoints()(implicit
      tc: TraceContext
  ): Future[Seq[(Option[SequencerId], Option[P2PEndpoint.Id])]] = {
    runCmd(
      SequencerBftAdminCommands.GetPeerNetworkStatus(None)
    ).map(_.endpointStatuses.map {
      case PeerConnectionStatus.PeerIncomingConnection(sequencerId) => (Some(sequencerId), None)
      case PeerConnectionStatus.PeerEndpointStatus(id, isOutgoing @ _, health) =>
        health.status match {
          case PeerEndpointHealthStatus.UnknownEndpoint =>
            None -> Some(id)
          case PeerEndpointHealthStatus.Unauthenticated =>
            None -> Some(id)
          case PeerEndpointHealthStatus.Disconnected =>
            None -> Some(id)
          case PeerEndpointHealthStatus.Authenticated(sequencerId) =>
            Some(sequencerId) -> Some(id)
        }
    })
  }

  def listCurrentOutgoingPeerEndpoints()(implicit
      tc: TraceContext
  ): Future[Seq[(Option[SequencerId], P2PEndpoint.Id)]] =
    listCurrentPeerEndpoints().map(_.collect { case (seqId, Some(endpointId)) =>
      (seqId, endpointId)
    })

}
