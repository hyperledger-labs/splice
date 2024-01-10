package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn as daml
import com.daml.network.codegen.java.cn.cometbft.SequencingKeyConfig
import com.daml.network.codegen.java.cn.svc.globaldomain.{MediatorConfig, SequencerConfig}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvScanConfig
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A trigger to publish the node-id's and validator keys of an SV's CometBFT nodes
  * to the SVC-wide shared configuration of what validators should be used in the network.
  */
class PublishLocalCometBftNodeConfigTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
    cometBftNode: CometBftNode,
    scanConfig: Option[SvScanConfig],
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
    ] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask]] =
    (for {
      svcRules <- OptionT(store.lookupSvcRules())
      svNodeMemberInfo <- OptionT.fromOption[Future](
        CometBftNode.extractSvNodeMemberInfo(svcRules.payload, store.key.svParty)
      )
      localSvNodeConfig <- OptionT.liftF(cometBftNode.getLocalNodeConfig())
      // TODO (#4901) reconcile cometBft networks for multiple domains instead using one default domain id here.
      domainId = svcRules.domain
      domainNodeConfig = svNodeMemberInfo.domainNodes.asScala.get(domainId.toProtoPrimitive)
      // create a task if the local config is different than the one we have published to the SVC
      // or if no domain bft is configured
      if
      // "TODO(#4901): reconcile all configured CometBFT networks"
      !domainNodeConfig
        .map(domainNodeConfig => CometBftNode.svNodeConfigToProto(domainNodeConfig.cometBft))
        .contains(localSvNodeConfig)
    } yield PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask(
      svNodeMemberInfo.name,
      svcRules,
      domainNodeConfig.flatMap(_.sequencer.toScala).toJava,
      domainNodeConfig.flatMap(_.mediator.toScala).toJava,
      localSvNodeConfig,
      scanConfig,
      domainId,
    )).value
      .map(_.toList)

  override protected def completeTask(
      task: PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val cmd = task.svcRules.exercise(
      _.exerciseSvcRules_SetDomainNodeConfig(
        store.key.svParty.toProtoPrimitive,
        task.domainId.toProtoPrimitive,
        task.damlSvNodeConfig,
      )
    )
    for {
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.svcParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(show"Updated SVC-wide CometBFT node configuration for ${store.key.svParty}")
  }

  override protected def isStaleTask(
      task: PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
  )(implicit tc: TraceContext): Future[Boolean] = {
    for {
      contract <- store.multiDomainAcsStore
        .lookupContractById(daml.svcrules.SvcRules.COMPANION)(task.svcRules.contractId)
      currentNodeConfig <- cometBftNode.getLocalNodeConfig()
    } yield {
      contract.isEmpty || task.localSvNodeConfig != currentNodeConfig
    }
  }
}

object PublishLocalCometBftNodeConfigTrigger {
  case class PublishLocalConfigTask(
      svNodeId: String,
      svcRules: AssignedContract[daml.svcrules.SvcRules.ContractId, daml.svcrules.SvcRules],
      sequencerConfig: Optional[SequencerConfig],
      mediatorConfig: Optional[MediatorConfig],
      localSvNodeConfig: proto.cometbft.SvNodeConfig,
      scanConfig: Option[SvScanConfig],
      domainId: DomainId,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    implicit private val svNodeConfigPretty: Pretty[proto.cometbft.SvNodeConfig] =
      PrettyUtil.adHocPrettyInstance

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("svcRules", _.svcRules),
      param("localSvNodeConfig", _.localSvNodeConfig),
    )

    // TODO(#5889): unify or align with CometBftNode.getLocalNodeConfig and the functions backing `diffNetworkConfig`
    val damlSvNodeConfig: daml.svc.globaldomain.DomainNodeConfig =
      new daml.svc.globaldomain.DomainNodeConfig(
        new daml.cometbft.CometBftConfig(
          localSvNodeConfig.cometbftNodes.map { case (nodeId, config) =>
            (
              nodeId,
              new daml.cometbft.CometBftNodeConfig(
                config.validatorPubKey,
                config.votingPower,
              ),
            )
          }.asJava,
          localSvNodeConfig.governanceKeys
            .map(key => new daml.cometbft.GovernanceKeyConfig(key.pubKey))
            .asJava,
          localSvNodeConfig.sequencingKeys.map(key => new SequencingKeyConfig(key.pubKey)).asJava,
        ),
        sequencerConfig,
        mediatorConfig,
        scanConfig.map(c => new daml.svc.globaldomain.ScanConfig(c.publicUrl.toString)).toJava,
      )
  }
}
