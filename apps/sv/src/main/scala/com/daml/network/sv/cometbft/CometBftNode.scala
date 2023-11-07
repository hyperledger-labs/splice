package com.daml.network.sv.cometbft

import cats.Show.Shown
import cats.implicits.toTraverseOps
import com.daml.network.codegen.java.cn as daml
import com.daml.network.codegen.java.cn.svcrules.{MemberInfo, SvcRules}
import com.daml.network.sv.config.CometBftConfig
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.drivers.cometbft.SvNodeConfig
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A handle to a CometBFT node.
  */
class CometBftNode(
    cometBftClient: CometBftClient,
    val cometBftConfig: CometBftConfig,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  import com.daml.network.sv.cometbft.CometBftNode.*

  /** Reconcile the CometBFT network config seen by this node with the target configuration specified
    * in the `SvcRules` contract.
    *
    * @param owningSvNode: the identifier of the SvNode that owns and manages this CometBFT node.
    * This method is idempotent and safe to retry.
    */
  def reconcileNetworkConfig(
      owningSvNode: String,
      target: AssignedContract[?, daml.svcrules.SvcRules],
  )(implicit tc: TraceContext): Future[Unit] = for {
    actualConfig <- cometBftClient.readNetworkConfig()
    networkConfigChanges = diffNetworkConfig(
      owningSvNode,
      target.payload.members,
      actualConfig,
      target.domain,
      logger,
    )
    // We minimize latency by issuing updates and deletes in parallel, which is safe as we expect <= 16 SV nodes
    // TODO(M3-47): consider moving `diffNetworkConfig` into this class to minimize the parameter passing; it's currently kept outside for ease of unit testing
    _ <-
      if (networkConfigChanges.requests.nonEmpty) {
        val summary = CometBftNode.NetworkDiffSummary(
          actualConfig,
          target.payload.members,
          networkConfigChanges.requests,
          target.domain,
        )
        // TODO(#4925): select the governance key to use for submitting the change requests comparing the KMS (Canton ;-)) against the configured state
        val ourGovernanceKey =
          proto.cometbft.GovernanceKey(CometBftRequestSigner.GenesisPubKeyBase64)
        val ourKeyIsRegistered = actualConfig.svNodeConfigStates
          .get(owningSvNode)
          .exists(state =>
            state.currentConfig.exists(config => config.governanceKeys.contains(ourGovernanceKey))
          )
        if (ourKeyIsRegistered) {
          logger.debug(
            show"Reconciling difference in CometBft network configuration: $summary"
          )
          networkConfigChanges.requests.traverse(
            submitChangeRequest
          )
        } else {
          logger.info(
            show"Skipping reconciling difference in CometBft network configuration, as our governance public key ${ourGovernanceKey.toString.singleQuoted} is not yet registered: $summary"
          )
          Future.unit
        }
      } else Future.unit
  } yield ()

  private def submitChangeRequest(
      changeRequest: proto.cometbft.NetworkConfigChangeRequest
  )(implicit tc: TraceContext) = {
    @SuppressWarnings(Array("org.wartremover.warts.Product"))
    implicit val prettyNetworkConfigChangeRequest
        : Pretty[proto.cometbft.NetworkConfigChangeRequest] =
      PrettyUtil.adHocPrettyInstance

    logger.debug(show"Applying CometBft network change: $changeRequest")
    val request = proto.cometbft.UpdateNetworkConfigRequest(
      changeRequestBytes = changeRequest.toByteString,
      signature =
        com.google.protobuf.ByteString.copyFrom(CometBftRequestSigner.signRequest(changeRequest)),
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
          // TODO(#4925) use a per-node key, instead of this static one
          governanceKeys = List(
            proto.cometbft.GovernanceKey(CometBftRequestSigner.GenesisPubKeyBase64)
          ),
          // TODO(#5882): add support for sequencing keys
          sequencingKeys = List.empty,
        )
      }
  }
}

object CometBftNode {

  def extractSvNodeMemberInfo(rules: SvcRules, svParty: PartyId): Option[MemberInfo] = {
    val members = rules.members.asScala
    // require our svParty to be registered as a member
    members.get(svParty.toProtoPrimitive)
  }

  case class NetworkConfigDiff(
      deletes: Seq[proto.cometbft.NetworkConfigChangeRequest],
      updates: Seq[proto.cometbft.NetworkConfigChangeRequest],
  ) {
    lazy val requests: Seq[proto.cometbft.NetworkConfigChangeRequest] = deletes ++ updates
  }

  /** Compute the set of change requests that, when applied, make the current network config
    * match the target config specified by the `SvcRules` contract.
    */
  def diffNetworkConfig(
      owningSvNode: String,
      targetMemberInfos: java.util.Map[String, daml.svcrules.MemberInfo],
      currentNetworkConfig: proto.cometbft.GetNetworkConfigResponse,
      domainId: DomainId,
      logger: TracedLogger,
  ): NetworkConfigDiff = {
    val targetConfig = memberInfosToNetworkConfig(targetMemberInfos, domainId)
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
    proto.cometbft.SvNodeConfig(
      cometbftNodes = config.nodes.asScala
        .map({ case (nodeId, nodeConfig) =>
          (nodeId, cometBftNodeConfigToProto(nodeConfig))
        })
        .toMap,
      governanceKeys =
        config.governanceKeys.asScala.map(key => proto.cometbft.GovernanceKey(key.pubKey)).toSeq,
      sequencingKeys =
        config.sequencingKeys.asScala.map(key => proto.cometbft.SequencingKey(key.pubKey)).toSeq,
    )

  private def memberInfosToNetworkConfig(
      memberInfos: java.util.Map[String, daml.svcrules.MemberInfo],
      domainId: DomainId,
  ): immutable.Map[String, proto.cometbft.SvNodeConfig] =
    memberInfos.asScala.values
      .flatMap(info =>
        extractDomainNodeConfig(info, domainId).map(domainNode =>
          info.name -> svNodeConfigToProto(domainNode.cometBft)
        )
      )
      .toMap

  private def extractDomainNodeConfig(
      info: daml.svcrules.MemberInfo,
      domainId: DomainId,
  ) = {
    // TODO(#4901): reconcile all configured CometBFT networks
    info.domainNodes.asScala.get(domainId.toProtoPrimitive)
  }

  /** Used only for pretty-printing a summary. */

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  private case class NetworkDiffSummary(
      actualConfig: proto.cometbft.GetNetworkConfigResponse,
      memberInfos: java.util.Map[String, daml.svcrules.MemberInfo],
      changes: Seq[proto.cometbft.NetworkConfigChangeRequest],
      domainId: DomainId,
  ) extends PrettyPrinting {
    implicit val prettyGetNetworkConfigResponse: Pretty[proto.cometbft.GetNetworkConfigResponse] =
      PrettyUtil.adHocPrettyInstance

    implicit val prettySvNodeConfig: Pretty[proto.cometbft.SvNodeConfig] =
      PrettyUtil.adHocPrettyInstance

    implicit val prettyNetworkConfigChangeRequest
        : Pretty[proto.cometbft.NetworkConfigChangeRequest] =
      PrettyUtil.adHocPrettyInstance

    private val targetConfig: Seq[(Shown, proto.cometbft.SvNodeConfig)] =
      memberInfosToNetworkConfig(memberInfos, domainId).view.map { case (memberId, config) =>
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
