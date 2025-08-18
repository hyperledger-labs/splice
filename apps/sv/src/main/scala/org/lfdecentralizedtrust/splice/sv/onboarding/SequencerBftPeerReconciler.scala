// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.store.DsoRulesStore
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.DsoRulesTopologyStateReconciler
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.RichOptional
import scala.util.control.NonFatal

abstract class SequencerBftPeerReconciler(
    sequencerAdminConnection: SequencerAdminConnection,
    scanConnection: AggregatingScanConnection,
    migrationId: Long,
) extends DsoRulesTopologyStateReconciler[BftPeerDifference]
    with NamedLogging {

  override protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[BftPeerDifference]] = {
    for {
      sequencerId <- sequencerAdminConnection.getSequencerId
      sequencers = dsoRulesAndState
        .currentSynchronizerNodeConfigs()
        .flatMap(_.sequencer.toScala)
        .flatMap(config =>
          SequencerId
            .fromProtoPrimitive(
              config.sequencerId,
              "sequencerId",
            )
            .fold(
              error => {
                logger
                  .warn(s"Failed to parse sequencer id $sequencerId for sequencer $config. $error")
                None
              },
              Some(_),
            )
        )
      dsoSequencersWithoutSelf = sequencers.filter(_ != sequencerId)
      sequencersFromScan <- getAllBftSequencers()
      dsoSequencersWithScanInfo = dsoSequencersWithoutSelf.map { sequencerId =>
        sequencerId -> sequencersFromScan.find(scanSequencer =>
          scanSequencer.id == sequencerId && scanSequencer.migrationId == migrationId
        )
      }
      // TODO(#1929) Reconsider whether we can really ignore incoming connections.
      currentPeers <- sequencerAdminConnection.listCurrentOutgoingPeerEndpoints()

      peersToAdd = dsoSequencersWithScanInfo
        .collect { case (id, Some(config)) =>
          id -> config
        }
        .filterNot { case (dsoSequencerId, peer) =>
          currentPeers.exists { case (peerSequencerId, endpointId) =>
            peerSequencerId.forall(_ == dsoSequencerId) && peer.peerId.id == endpointId
          }
        }
      peersToRemove = currentPeers
        .filterNot {
          case (Some(peerSequencerId), endpointId) =>
            dsoSequencersWithScanInfo.exists { case (sequencerId, config) =>
              sequencerId == peerSequencerId && config.forall(_.peerId.id == endpointId)
            }
          case (None, endpointId) =>
            dsoSequencersWithScanInfo.exists { case (_, config) =>
              config.exists(_.peerId.id == endpointId)
            }
        }
    } yield {
      if (peersToAdd.nonEmpty || peersToRemove.nonEmpty)
        Seq(BftPeerDifference(peersToAdd.map(_._2.peerId), peersToRemove.map(_._2), currentPeers))
      else Seq()
    }
  }

  private def getAllBftSequencers()(implicit ec: ExecutionContext, tc: TraceContext) = {
    scanConnection
      .fromAllScans(includeSelf = false) { scan =>
        scan
          .listSvBftSequencers()
          .recover { case NonFatal(ex) =>
            logger.warn(s"Failed to read bft sequencers list from scan ${scan.url}", ex)
            Seq.empty
          }
      }
      .map(_.flatten)
  }
}

object SequencerBftPeerReconciler {
  case class BftPeerDifference(
      toAdd: Seq[P2PEndpoint],
      toRemove: Seq[P2PEndpoint.Id],
      currentPeers: Seq[(Option[SequencerId], P2PEndpoint.Id)],
  )
}
