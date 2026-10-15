// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.synchronizer.sequencer.block.bftordering.bindings.p2p.grpc.P2PGrpcNetworking.P2PEndpoint
import com.digitalasset.canton.topology.SequencerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.{
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
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
    retryProvider: RetryProvider,
) extends DsoRulesTopologyStateReconciler[BftPeerDifference]
    with NamedLogging {

  override protected def diffDsoRulesWithTopology(
      dsoRulesAndState: DsoRulesStore.DsoRulesWithSvNodeStates
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[BftPeerDifference]] = {
    for {
      sequencerInitialized <- retryProvider.retry(
        RetryFor.Automation,
        "sequencer_init_status",
        "Check if sequencer is initialized",
        sequencerAdminConnection
          .isNodeInitialized(),
        logger,
      )
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
            dsoSequencersWithEndpoint = dsoSequencersWithoutSelf.map { sequencerId =>
              sequencerId -> sequencersFromScan
                .find(scanSequencer =>
                  scanSequencer.id == sequencerId && scanSequencer.serialId == serialId
                )
                .map(_.peerId)
            }
            dsoSequencerEndpoints = dsoSequencersWithEndpoint.flatMap(_._2)
            configuredPeers <- sequencerAdminConnection
              .listConfiguredPeerEndpoints()
            peersToAdd = dsoSequencerEndpoints
              .filterNot(endpoint => configuredPeers.map(_._1).exists(_.id == endpoint.id))
            peersToRemove = computePeersToRemove(
              configuredPeers,
              dsoSequencersWithEndpoint,
            )
          } yield {
            if (peersToAdd.nonEmpty || peersToRemove.nonEmpty)
              Seq(
                BftPeerDifference(
                  peersToAdd,
                  peersToRemove.map(_.id),
                  configuredPeers,
                )
              )
            else Seq()
          }
    } yield result
  }

  private def computePeersToRemove(
      configuredPeers: Seq[(P2PEndpoint, Option[SequencerId])],
      dsoSequencersWithEndpoint: Seq[(SequencerId, Option[P2PEndpoint])],
  ): Seq[P2PEndpoint] = {
    val peersWithWrongSequencerId = configuredPeers.filter {
      case (_, Some(sequencerId)) =>
        !dsoSequencersWithEndpoint.exists({ case (dsoSequencerId, _) =>
          sequencerId == dsoSequencerId
        })
      case _ => false
    }
    val peersWithChangedEndpoint = configuredPeers.filter {
      case (peer, Some(sequencerId)) =>
        dsoSequencersWithEndpoint.exists({ case (dsoSequencerId, endpoint) =>
          sequencerId == dsoSequencerId && endpoint.exists(_.id != peer.id)
        })
      case _ => false
    }
    // we only remove connections for which we don't have a sequencer id when we have been able to query all scans to get connections. otherwise a temporary scan issue could result in us removing the peer.
    val unknownPeers =
      if (dsoSequencersWithEndpoint.forall { case (_, endpoint) => endpoint.isDefined }) {
        configuredPeers.filter(peer =>
          !dsoSequencersWithEndpoint.exists { case (_, endpoint) =>
            endpoint.exists(_.id == peer._1.id)
          }
        )
      } else Seq.empty

    (peersWithWrongSequencerId ++ peersWithChangedEndpoint ++ unknownPeers)
      .map(
        _._1
      )
      .distinct
  }

  private def getAllBftSequencers()(implicit ec: ExecutionContext, tc: TraceContext) = {
    scanConnection
      .fromAllScans(includeSelf = false) { scan =>
        scan
          .listSvBftSequencers()
          .recover { case NonFatal(ex) =>
            // not a warn because short-term failures are benign and longer-term outages should be covered by other monitoring
            logger.info(s"Failed to read bft sequencers list from scan ${scan.url}", ex)
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
      currentPeers: Seq[(P2PEndpoint, Option[SequencerId])],
  )
}
