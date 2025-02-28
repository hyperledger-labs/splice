// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.commands.SequencerBftAdminCommands
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.admin.SequencerBftAdminData
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

trait SequencerBftAdminConnection {
  this: AppConnection =>
  implicit val ec: ExecutionContextExecutor

  def addPeerEndpoint(peer: Endpoint)(implicit tc: TraceContext): Future[Unit] = {
    runCmd(
      SequencerBftAdminCommands.AddPeerEndpoint(endpoint = peer)
    )
  }

  def removePeerEndpoint(peer: Endpoint)(implicit tc: TraceContext): Future[Unit] = {
    runCmd(
      SequencerBftAdminCommands.RemovePeerEndpoint(endpoint = peer)
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
  ): Future[Seq[Endpoint]] = {
    runCmd(
      SequencerBftAdminCommands.GetPeerNetworkStatus(None)
    ).map(_.endpointStatuses.map(_.endpoint))
  }

}
