package com.daml.network.sv.cometbft

import com.digitalasset.canton.drivers as proto
import com.daml.network.codegen.java.cn as daml
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyUtil}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A handle to a CometBFT node.
  *
  * @param owningSvNode: the identifier of the SvNode that owns and manages this CometBFT node.
  */
class CometBftNode(
    owningSvNode: String,
    cometBftService: proto.cometbft.NodeGovernanceServiceGrpc.NodeGovernanceService,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  import com.daml.network.sv.cometbft.CometBftNode.*

  /** Reconcile the CometBFT network config seen by this node with the target configuration specified
    * in the `SvcRules` contract.
    *
    * This method is idempotent and safe to retry.
    */
  def reconcileNetworkConfig(target: daml.svcrules.SvcRules): Future[Unit] = for {
    actualConfig <- cometBftService.getNetworkConfig(com.google.protobuf.empty.Empty())
    // We minimize latency by issuing all change requests at once, which is safe as we expect <= 16 SV nodes
    _ <- Future.traverse(diffNetworkConfig(owningSvNode, target.members, actualConfig, logger))(
      // TODO(M3-47): consider moving `diffNetworkConfig` into this class to minimize the parameter passing; it's currently kept outside for ease of unit testing
      submitChangeRequest
    )
  } yield ()

  private def submitChangeRequest(
      changeRequest: proto.cometbft.NetworkConfigChangeRequest
  ): Future[com.google.protobuf.empty.Empty] = {
    val request = proto.cometbft.UpdateNetworkConfigRequest(
      changeRequestBytes = changeRequest.toByteString,
      signature = com.google.protobuf.ByteString.EMPTY, // TODO(M3-47): actually sign :)
    )
    cometBftService.updateNetworkConfig(request)
  }
}

object CometBftNode {

  /** Compute the set of change requests that, when applied, make the current network config
    * match the target config specified by the `SvcRules` contract.
    */
  def diffNetworkConfig(
      owningSvNode: String,
      targetMemberInfos: java.util.Map[String, daml.svcrules.MemberInfo],
      currentNetworkConfig: proto.cometbft.GetNetworkConfigResponse,
      logger: TracedLogger,
  ): Seq[proto.cometbft.NetworkConfigChangeRequest] = {
    val targetConfig = memberInfosToNetworkConfig(targetMemberInfos)
    val actualOrPendingConfig = getActualOrPendingConfig(owningSvNode, currentNetworkConfig, logger)

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
        kind = proto.cometbft.NetworkConfigChangeRequest.Kind.NodeConfigChangeRequest(changeRequest),
      )
    }

    // Delete SV node configs for SV nodes that have been deleted, and whose deletion is not yet scheduled
    val toDelete = actualOrPendingConfig.filter { case (svNodeId, svNodeConfigState) =>
      !targetConfig.contains(svNodeId) && svNodeConfigState.actualOrPendingConfig.isDefined
    }
    val deleteConfigRequests = toDelete.collect { case (svNodeId, svNodeConfigState) =>
      mkChangeRequest(
        svNodeId,
        svNodeConfigState.currentRevision,
        proto.cometbft.SvNodeConfigChange.Kind.DeleteConfig(com.google.protobuf.empty.Empty()),
      )
    }

    // Set SV node configs for SV nodes whose config has changed, and for whom we did not yet
    // create a pending change request.
    val missingOrDifferent =
      targetConfig.collect(Function.unlift { case (svNodeId, targetSvNodeConfig) =>
        actualOrPendingConfig.get(svNodeId) match {
          case None => Some((svNodeId, 0L, targetSvNodeConfig))
          case Some(configState) =>
            if (configState.actualOrPendingConfig.contains(targetSvNodeConfig))
              None
            else
              Some((svNodeId, configState.currentRevision, targetSvNodeConfig))
        }
      })
    val setConfigRequests = missingOrDifferent.map {
      case (svNodeId, currentRevision, targetSvNodeConfig) =>
        mkChangeRequest(
          svNodeId,
          currentRevision,
          proto.cometbft.SvNodeConfigChange.Kind.SetConfig(targetSvNodeConfig),
        )
    }

    (deleteConfigRequests ++ setConfigRequests).toSeq
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
  ): immutable.Map[String, ReconcilableSvNodeConfigState] = {
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
              )(TraceContext.empty)
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
                  )(TraceContext.empty)
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
    proto.cometbft.SvNodeConfig(cometbftNodes =
      config.nodes.asScala
        .map({ case (nodeId, nodeConfig) =>
          (nodeId, cometBftNodeConfigToProto(nodeConfig))
        })
        .toMap
    )

  private def memberInfosToNetworkConfig(
      memberInfos: java.util.Map[String, daml.svcrules.MemberInfo]
  ): immutable.Map[String, proto.cometbft.SvNodeConfig] =
    memberInfos.asScala.values
      .map(info => (info.name, svNodeConfigToProto(extractDefaultDomainNodeConfig(info).cometBft)))
      .toMap

  def extractDefaultDomainNodeConfig(
      info: daml.svcrules.MemberInfo
  ): daml.svc.globaldomain.DomainNodeConfig =
    info.domainNodes.asScala.getOrElse(
      SvUtil.defaultSvcDomainNumber,
      throw new NotImplementedError("TODO(#4901): reconcile all configured CometBFT networks"),
    )
}
