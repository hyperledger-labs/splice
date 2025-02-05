// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.implicits.*
import com.digitalasset.canton.admin.api.client.commands.SequencerBftAdminCommands
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.admin.SequencerBftAdminData
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

trait SequencerBftAdminConnection {
  this: AppConnection =>
  implicit val ec: ExecutionContextExecutor

  def setBftPeerEndpoints(peers: Seq[Endpoint])(implicit tc: TraceContext): Future[Unit] = {
    peers.traverse_(addPeerEndpoint)
  }

  private def addPeerEndpoint(peer: Endpoint)(implicit tc: TraceContext) = {
    // TODO(#17414) - reconcile the list
    runCmd(
      SequencerBftAdminCommands.AddPeerEndpoint(endpoint = peer)
    )
  }

  def getSequencerOrderingTopology()(implicit
      tc: TraceContext
  ): Future[SequencerBftAdminData.OrderingTopology] = {
    runCmd(
      SequencerBftAdminCommands.GetOrderingTopology()
    )
  }

}
