// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.cometbft

import cats.Show.Shown
import cats.implicits.toTraverseOps
import org.lfdecentralizedtrust.splice.codegen.java.splice as daml
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider}
import org.lfdecentralizedtrust.splice.sv.config.CometBftConfig
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeStates
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.drivers.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest
import com.digitalasset.canton.drivers.cometbft.{
  GovernanceKey,
  NetworkConfigChangeRequest,
  SvBootstrapConfigChangeRequest,
  SvNodeConfig,
  SvNodeConfigChange,
  SvNodeConfigChangeRequest,
}
import com.digitalasset.canton.drivers.cometbft.SvNodeConfigChange.Kind.SetConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import scalapb.TimestampConverters

import java.time.Instant
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import io.grpc.Status

/** A handle to a CometBFT node.
  */
class CometBftNode(
    val cometBftClient: CometBftClient,
    val cometBftRequestSigner: CometBftRequestSigner,
    val cometBftConfig: CometBftConfig,
    protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode.*

  /** Rotate the sv1 keys of the CometBFT network.
    */
  def rotateGenesisGovernanceKeyForSV1(
      owningSvNode: String
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      actualConfig <- cometBftClient.readNetworkConfig()
      genesisSigner = CometBftRequestSigner.GenesisSigner
      currentKeysSetToGenesisKeys <- areGenesisGovernanceKeysConfigured(
        owningSvNode,
        genesisSigner,
      )
      localConfig <- getLocalNodeConfig()
      _ =
        if (currentKeysSetToGenesisKeys) {
          logger.info(
            s"Rotating sv1 governance keys from ${genesisSigner.Fingerprint} to ${cometBftRequestSigner.Fingerprint} (fingerprints) as they are set to the genesis keys."
          )
          val governanceKeysToKeep = actualConfig.svNodeConfigStates
            .get(owningSvNode)
            .flatMap(_.currentConfig.map(_.governanceKeys)) match {
            case Some(govKeys) => govKeys.filterNot(_.pubKey == genesisSigner.PublicKeyBase64)
            case None => List.empty
          }
          val currentConfigRevision =
            actualConfig.svNodeConfigStates
              .get(owningSvNode)
              .map(_.currentConfigRevision)
              .getOrElse(1L)
          val request = NetworkConfigChangeRequest(
            chainId = actualConfig.chainId,
            submitterSvNodeId = owningSvNode,
            submitterKeyId = genesisSigner.Fingerprint,
            submittedAt = Some(TimestampConverters.fromJavaInstant(Instant.now())),
            kind = NodeConfigChangeRequest(
              SvNodeConfigChangeRequest.of(
                owningSvNode,
                currentConfigRevision = currentConfigRevision,
                change = Some(
                  SvNodeConfigChange.of(
                    SetConfig(
                      localConfig.copy(
                        governanceKeys = governanceKeysToKeep :+
                          GovernanceKey(
                            cometBftRequestSigner.PublicKeyBase64,
                            cometBftRequestSigner.Fingerprint,
                          )
                      )
                    )
                  )
                ),
              )
            ),
          )
          submitChangeRequest(request, genesisSigner)
        }
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "keys_rotated",
        "Governance keys are not set to the genesis keys anymore",
        areGenesisGovernanceKeysConfigured(owningSvNode, genesisSigner).map(
          genesisKeysConfigured => {
            if (genesisKeysConfigured)
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  "Governance keys are set to genesis keys"
                )
                .asRuntimeException()
          }
        ),
        logger,
      )
    } yield {
      if (currentKeysSetToGenesisKeys) {
        logger.info(
          s"Successfully rotated sv1 governance keys from ${genesisSigner.Fingerprint} to ${cometBftRequestSigner.Fingerprint} (fingerprints)"
        )
      } else {
        logger.info(
          s"Skipping rotating sv1 governance keys as they are not set to the genesis keys."
        )
      }
    }
  }

  /** Reconcile the CometBFT network config seen by this node with the target configuration specified
    * in the `DsoRules` contract.
    *
    * @param owningSvNode: the identifier of the SvNode that owns and manages this CometBFT node.
    * This method is idempotent and safe to retry.
    */
  def reconcileNetworkConfig(
      owningSvNode: String,
      target: DsoRulesWithSvNodeStates,
  )(implicit tc: TraceContext): Future[Unit] = {

    val ourGovernanceKey = {
      proto.cometbft.GovernanceKey(
        cometBftRequestSigner.PublicKeyBase64,
        cometBftRequestSigner.Fingerprint,
      )
    }
    val synchronizerId = target.dsoRules.domain
    val targetNodeStates = target.svNodeStates.values.map(_.payload).toSeq
    val keepsOurOwnGovernanceKey = targetNodeStates
      .find(state => state.svName == owningSvNode)
      .exists(state =>
        state.state.synchronizerNodes.asScala
          .get(synchronizerId.toProtoPrimitive)
          .exists(config =>
            config.cometBft.governanceKeys.asScala.exists(_.pubKey == ourGovernanceKey.pubKey)
          )
      )
    if (!keepsOurOwnGovernanceKey) {
      // Play it safe! We don't want to lock ourselves out!
      // This works with two step key rotations: one to add the new key, and one to remove the old one.
      import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
      logger.info(
        show"We ${owningSvNode.singleQuoted} are not reconciling with a target state that removes our governance key ${ourGovernanceKey.pubKey} on $synchronizerId. Target: $targetNodeStates"
      )
      Future.unit
    } else {
      for {
        actualConfig <- cometBftClient.readNetworkConfig()
        networkConfigChanges = diffNetworkConfig(
          owningSvNode,
          cometBftRequestSigner.Fingerprint,
          targetNodeStates,
          actualConfig,
          synchronizerId,
          logger,
        )
        // We minimize latency by issuing updates and deletes in parallel, which is safe as we expect <= 16 SV nodes
        // TODO(M3-47): consider moving `diffNetworkConfig` into this class to minimize the parameter passing; it's currently kept outside for ease of unit testing
        _ <-
          if (networkConfigChanges.requests.nonEmpty) {
            val summary = CometBftNode.NetworkDiffSummary(
              actualConfig,
              targetNodeStates,
              networkConfigChanges.requests,
              target.dsoRules.domain,
            )
            val ourKeyIsRegistered = actualConfig.svNodeConfigStates
              .get(owningSvNode)
              .exists(state =>
                state.currentConfig.exists(config =>
                  config.governanceKeys.contains(ourGovernanceKey)
                )
              )
            if (ourKeyIsRegistered) {
              val currentRegisteredNodes = actualConfig.svNodeConfigStates.keySet
              if (currentRegisteredNodes == Set(owningSvNode)) {
                logger.debug(
                  show"Bootstrapping CometBft network configuration: $summary"
                )
                submitChangeRequest(
                  NetworkConfigChangeRequest(
                    chainId = actualConfig.chainId,
                    submitterSvNodeId = owningSvNode,
                    submittedAt = Some(TimestampConverters.fromJavaInstant(Instant.now())),
                    submitterKeyId = cometBftRequestSigner.Fingerprint,
                    kind = NetworkConfigChangeRequest.Kind.BootstrapConfigChangeRequest(
                      SvBootstrapConfigChangeRequest(
                        networkConfigChanges.requests.map(_.getNodeConfigChangeRequest)
                      )
                    ),
                  ),
                  cometBftRequestSigner,
                )
              } else {
                logger.debug(
                  show"Reconciling difference in CometBft network configuration: $summary"
                )
                networkConfigChanges.requests.traverse(
                  submitChangeRequest(_, cometBftRequestSigner)
                )
              }
            } else {
              logger.info(
                show"Skipping reconciling difference in CometBft network configuration, as our governance public key ${ourGovernanceKey.toString.singleQuoted} is not yet registered: $summary"
              )
              Future.unit
            }
          } else Future.unit
      } yield ()
    }
  }

  private def areGenesisGovernanceKeysConfigured(
      owningSvNode: String,
      genesisSigner: CometBftRequestSigner,
  )(implicit tc: TraceContext) = {
    for {
      actualConfig <- cometBftClient.readNetworkConfig()
    } yield actualConfig.svNodeConfigStates
      .get(owningSvNode)
      .exists(state =>
        state.currentConfig.exists(config =>
          config.governanceKeys
            .exists(_.pubKey == genesisSigner.PublicKeyBase64)
        )
      )
  }

  private def submitChangeRequest(
      changeRequest: proto.cometbft.NetworkConfigChangeRequest,
      signer: CometBftRequestSigner,
  )(implicit tc: TraceContext) = {
    @SuppressWarnings(Array("org.wartremover.warts.Product"))
    implicit val prettyNetworkConfigChangeRequest
        : Pretty[proto.cometbft.NetworkConfigChangeRequest] =
      PrettyUtil.adHocPrettyInstance

    logger.debug(show"Applying CometBft network change: $changeRequest")
    val request = proto.cometbft.UpdateNetworkConfigRequest(
      changeRequestBytes = changeRequest.toByteString,
      signature = com.google.protobuf.ByteString
        .copyFrom(signer.signRequest(changeRequest)),
    )
    cometBftClient
      .updateNetworkConfig(request)
      .map { _ =>
        logger.info(show"Applied CometBft network change: $changeRequest")
      }
  }

  def getLocalNodeConfig()(implicit
      tc: TraceContext
  ): Future[SvNodeConfig] = {
    cometBftClient
      .nodeStatus()
      .map { status =>
        proto.cometbft.SvNodeConfig(
          cometbftNodes = Map(
            status.nodeInfo.id ->
              proto.cometbft.CometBftNodeConfig(
                validatorPubKey = status.validatorInfo.publicKey.value,
                votingPower = 1, // hard-coded to one for now, as we only have one node at most
              )
          ),
          governanceKeys = List(
            proto.cometbft.GovernanceKey(
              cometBftRequestSigner.PublicKeyBase64,
              cometBftRequestSigner.Fingerprint,
            )
          ),
          // TODO(#5882): add support for sequencing keys
          sequencingKeys = List.empty,
        )
      }
  }

  def getLatestBlockHeight()(implicit tc: TraceContext): Future[Long] = {
    cometBftClient
      .nodeStatus()
      .map(_.syncInfo.latestBlockHeight)
  }

  def getEarliestBlockHeight()(implicit tc: TraceContext): Future[Long] = {
    cometBftClient
      .nodeStatus()
      .map(_.syncInfo.earliestBlockHeight)
  }
}

object CometBftNode {
  case class NetworkConfigDiff(
      deletes: Seq[proto.cometbft.NetworkConfigChangeRequest],
      updates: Seq[proto.cometbft.NetworkConfigChangeRequest],
  ) {
    // updates must be sent before deletes to ensure that we don't end up in a state where we try to delete all the validators
    lazy val requests: Seq[proto.cometbft.NetworkConfigChangeRequest] = updates ++ deletes
  }

  /** Compute the set of change requests that, when applied, make the current network config
    * match the target config specified by the `DsoRules` contract provided that target config doesn't lock us out.
    */
  def diffNetworkConfig(
      owningSvNode: String,
      signingKeyId: String,
      targetNodeStates: Seq[daml.dso.svstate.SvNodeState],
      currentNetworkConfig: proto.cometbft.GetNetworkConfigResponse,
      synchronizerId: SynchronizerId,
      logger: TracedLogger,
  )(implicit tc: TraceContext): NetworkConfigDiff = {
    val targetConfig = svNodeStatesToNetworkConfig(targetNodeStates, synchronizerId)
    val actualOrPendingConfig =
      getActualOrPendingConfig(owningSvNode, currentNetworkConfig, logger)

    def mkChangeRequest(
        svNodeId: String,
        currentConfigRevision: Long,
        change: proto.cometbft.SvNodeConfigChange.Kind,
    ): proto.cometbft.NetworkConfigChangeRequest = {
      val changeRequest = proto.cometbft.SvNodeConfigChangeRequest(
        svNodeId = svNodeId,
        currentConfigRevision = currentConfigRevision,
        change = Some(proto.cometbft.SvNodeConfigChange(kind = change)),
      )

      proto.cometbft.NetworkConfigChangeRequest(
        chainId = currentNetworkConfig.chainId,
        submitterSvNodeId = owningSvNode,
        submitterKeyId = signingKeyId,
        submittedAt = Some(TimestampConverters.fromJavaInstant(Instant.now())),
        kind = proto.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest(changeRequest),
      )
    }

    // Delete SV node configs for SV nodes that have been deleted, and whose deletion is not yet scheduled
    val toDelete = actualOrPendingConfig.filter { case (svNodeId, svNodeConfigState) =>
      !targetConfig.contains(svNodeId) && svNodeConfigState.actualOrPendingConfig.isDefined
    }
    val deleteConfigRequests = toDelete.map { case (svNodeId, svNodeConfigState) =>
      mkChangeRequest(
        svNodeId,
        svNodeConfigState.currentRevision,
        proto.cometbft.SvNodeConfigChange.Kind.DeleteConfig(com.google.protobuf.empty.Empty()),
      )
    }

    // Set SV node configs for SV nodes whose config has changed, and for whom we did not yet
    // create a pending change request.
    val missingOrDifferent =
      targetConfig
        .flatMap { case (svNodeId, targetSvNodeConfig) =>
          actualOrPendingConfig.get(svNodeId) match {
            case None =>
              Some((svNodeId, 0L, targetSvNodeConfig))
            case Some(configState) =>
              if (configState.actualOrPendingConfig.contains(targetSvNodeConfig))
                None
              else
                Some((svNodeId, configState.currentRevision, targetSvNodeConfig))
          }
        }
    val setConfigRequests = missingOrDifferent.map {
      case (svNodeId, currentRevision, targetSvNodeConfig) =>
        mkChangeRequest(
          svNodeId,
          currentRevision,
          proto.cometbft.SvNodeConfigChange.Kind.SetConfig(targetSvNodeConfig),
        )
    }

    NetworkConfigDiff(deleteConfigRequests.toSeq, setConfigRequests.toSeq)
  }

  /** SvNodeConfig as seen from a specific node. */
  private case class ReconcilableSvNodeConfigState(
      currentRevision: Long,
      actualOrPendingConfig: Option[proto.cometbft.SvNodeConfig],
  )

  /** Merge the actual and pending config updates of a specific SvNode into a unified view. */
  private def getActualOrPendingConfig(
      owningSvNode: String,
      response: proto.cometbft.GetNetworkConfigResponse,
      logger: TracedLogger,
  )(implicit tc: TraceContext): immutable.Map[String, ReconcilableSvNodeConfigState] = {
    @SuppressWarnings(Array("org.wartremover.warts.Product"))
    implicit val pendingChangePretty: Pretty[proto.cometbft.SvNodeConfigPendingChange] =
      PrettyUtil.adHocPrettyInstance

    response.svNodeConfigStates.map { case (svNodeId, svNodeConfigState) =>
      val actualOrPendingConfig = svNodeConfigState.pendingChanges.get(owningSvNode) match {
        case None => svNodeConfigState.currentConfig
        case Some(pendingChange) =>
          pendingChange.change match {
            case None =>
              // The `.change` field is not set. This should not happen, but if it does we
              // expect the change to not go through and thus assume the current config is what wins.
              logger.warn(
                show"Field `change` is unexpectedly not set in pending change for ${svNodeId.singleQuoted} by ${owningSvNode.singleQuoted}: $pendingChange"
              )
              svNodeConfigState.currentConfig
            case Some(change) =>
              change.kind match {
                case proto.cometbft.SvNodeConfigChange.Kind.DeleteConfig(_) => None
                case proto.cometbft.SvNodeConfigChange.Kind.SetConfig(config) => Some(config)
                case proto.cometbft.SvNodeConfigChange.Kind.Empty =>
                  // We do not expect this case, but could encounter it in case a future version of
                  // the governance proto is used by a new version of this app.
                  // Given that we don't know better, we assume that the current config wins, which likely
                  // will lead to us issuing a new change request overriding the one we don't understand,
                  // which is what we want in case of a downgrade scenario.
                  logger.warn(
                    show"Field `kind` is unexpectedly set to `Kind.Empty` in pending change for ${svNodeId.singleQuoted} by ${owningSvNode.singleQuoted}, which might be due to downgrading the SV app: $pendingChange"
                  )
                  svNodeConfigState.currentConfig
              }
          }
      }
      val currentRevision = svNodeConfigState.currentConfigRevision
      (svNodeId, ReconcilableSvNodeConfigState(currentRevision, actualOrPendingConfig))
    }
  }

  private def cometBftNodeConfigToProto(
      config: daml.cometbft.CometBftNodeConfig
  ): proto.cometbft.CometBftNodeConfig =
    proto.cometbft.CometBftNodeConfig(
      validatorPubKey = config.validatorPubKey,
      votingPower = config.votingPower,
    )

  def svNodeConfigToProto(config: daml.cometbft.CometBftConfig): proto.cometbft.SvNodeConfig =
    proto.cometbft.SvNodeConfig(
      cometbftNodes = config.nodes.asScala
        .map({ case (nodeId, nodeConfig) =>
          (nodeId, cometBftNodeConfigToProto(nodeConfig))
        })
        .toMap,
      governanceKeys = config.governanceKeys.asScala
        .map(key =>
          proto.cometbft.GovernanceKey(
            key.pubKey,
            CometBftRequestSigner.fingerprintForBase64PublicKey(key.pubKey),
          )
        )
        .toSeq,
      sequencingKeys =
        config.sequencingKeys.asScala.map(key => proto.cometbft.SequencingKey(key.pubKey)).toSeq,
    )

  private def svNodeStatesToNetworkConfig(
      svNodeStates: Seq[daml.dso.svstate.SvNodeState],
      synchronizerId: SynchronizerId,
  ): immutable.Map[String, proto.cometbft.SvNodeConfig] =
    svNodeStates
      .flatMap(state =>
        extractSynchronizerNodeConfig(state, synchronizerId).map(synchronizerNode =>
          state.svName -> svNodeConfigToProto(synchronizerNode.cometBft)
        )
      )
      .toMap
  private def extractSynchronizerNodeConfig(
      nodeState: daml.dso.svstate.SvNodeState,
      synchronizerId: SynchronizerId,
  ) = {
    // TODO(#4901): reconcile all configured CometBFT networks
    nodeState.state.synchronizerNodes.asScala.get(synchronizerId.toProtoPrimitive)
  }

  /** Used only for pretty-printing a summary. */

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  private case class NetworkDiffSummary(
      actualConfig: proto.cometbft.GetNetworkConfigResponse,
      svNodeStates: Seq[daml.dso.svstate.SvNodeState],
      changes: Seq[proto.cometbft.NetworkConfigChangeRequest],
      synchronizerId: SynchronizerId,
  ) extends PrettyPrinting {
    implicit val prettyGetNetworkConfigResponse: Pretty[proto.cometbft.GetNetworkConfigResponse] =
      PrettyUtil.adHocPrettyInstance

    implicit val prettySvNodeConfig: Pretty[proto.cometbft.SvNodeConfig] =
      PrettyUtil.adHocPrettyInstance

    implicit val prettyNetworkConfigChangeRequest
        : Pretty[proto.cometbft.NetworkConfigChangeRequest] =
      PrettyUtil.adHocPrettyInstance

    private val targetConfig: Seq[(Shown, proto.cometbft.SvNodeConfig)] =
      svNodeStatesToNetworkConfig(svNodeStates, synchronizerId).view.map {
        case (memberId, config) =>
          (memberId.singleQuoted, config)
      }.toSeq
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("actual", _.actualConfig),
        param("target", x => x.targetConfig),
        param("changes", _.changes),
      )
    }
  }

}
