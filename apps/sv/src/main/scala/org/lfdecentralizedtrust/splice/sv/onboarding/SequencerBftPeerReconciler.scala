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
) extends DsoRulesTopologyStateReconciler[BftPeerDifference]
    with NamedLogging {

  override protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[BftPeerDifference]] = {
    for {
      sequencerInitialized <- sequencerAdminConnection.isNodeInitialized()
      result <-
        if (!sequencerInitialized) Future.successful(Seq.empty)
        else
          for {
            sequencerId <- sequencerAdminConnection.getSequencerId
            psid <- sequencerAdminConnection.getPhysicalSynchronizerId()
            serialId = psid.serial.unwrap.toLong
            sequencers = dsoRulesAndState
              .currentSynchronizerNodeConfigs()
              .flatMap(config =>
                config.sequencerIdentity.toScala
                  .map(_.sequencerId)
                  .orElse(config.sequencer.toScala.map(_.sequencerId))
              )
              .flatMap(sequencerId =>
                SequencerId
                  .fromProtoPrimitive(sequencerId, "sequencerId")
                  .fold(
                    error => {
                      logger.warn(s"Failed to parse sequencer id $sequencerId. $error")
                      None
                    },
                    Some(_),
                  )
              )
            dsoSequencersWithoutSelf = sequencers.filter(_ != sequencerId)
            sequencersFromScan <- getAllBftSequencers()
            dsoSequencersWithScanInfo = dsoSequencersWithoutSelf.map { sequencerId =>
              sequencerId -> sequencersFromScan.find(scanSequencer =>
                scanSequencer.id == sequencerId && scanSequencer.serialId == serialId
              )
            }
            // TODO(#1929) Reconsider whether we can really ignore incoming connections.
            currentPeers <- sequencerAdminConnection
              .listCurrentOutgoingPeerEndpoints()
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
              Seq(
                BftPeerDifference(
                  peersToAdd.map(_._2.peerId),
                  peersToRemove.map(_._2),
                  currentPeers,
                )
              )
            else Seq()
          }
    } yield result
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
