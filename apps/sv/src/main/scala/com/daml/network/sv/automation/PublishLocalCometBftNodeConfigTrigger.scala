package com.daml.network.sv.automation

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn as daml
import com.daml.network.codegen.java.cn.cometbft.SequencingKeyConfig
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.automation.PublishLocalCometBftNodeConfigTrigger.*
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.Contract
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A trigger to publish the node-id's and validator keys of an SV's CometBFT nodes
  * to the SVC-wide shared configuration of what validators should be used in the network.
  */
class PublishLocalCometBftNodeConfigTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
    cometBftNode: CometBftNode,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[PublishLocalConfigTask] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishLocalConfigTask]] =
    (for {
      svcRulesRC <- OptionT(store.lookupSvcRules())
      svcRules = svcRulesRC.contract
      svNodeMemberInfo <- OptionT.fromOption[Future](
        CometBftNode.extractSvNodeMemberInfo(svcRules.payload, store.key.svParty)
      )
      localSvNodeConfig <- OptionT.liftF(cometBftNode.getLocalNodeConfig())
      // create a task if the local config is different than the one we have published to the SVC
      // or if no domain bft is configured
      if
      // "TODO(#4901): reconcile all configured CometBFT networks"
      !svNodeMemberInfo.domainNodes.asScala
        .get(SvUtil.defaultSvcDomainNumber)
        .map(domainNodeConfig => CometBftNode.svNodeConfigToProto(domainNodeConfig.cometBft))
        .contains(localSvNodeConfig)
    } yield PublishLocalConfigTask(svNodeMemberInfo.name, svcRules, localSvNodeConfig)).value
      .map(_.toList)

  override protected def completeTask(task: PublishLocalConfigTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      cmd = task.svcRules.contractId.exerciseSvcRules_SetDomainNodeConfig(
        store.key.svParty.toProtoPrimitive,
        SvUtil.defaultSvcDomainNumber, // TODO(#4901): do not use default, but reconcile all configured CometBFT networks
        task.damlSvNodeConfig,
      )
      _ <- connection.submitWithResultNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmd,
        domainId = domainId,
      )
    } yield TaskSuccess(show"Updated SVC-wide CometBFT node configuration for ${store.key.svParty}")

  override protected def isStaleTask(
      task: PublishLocalConfigTask
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
      svcRules: Contract[daml.svcrules.SvcRules.ContractId, daml.svcrules.SvcRules],
      localSvNodeConfig: proto.cometbft.SvNodeConfig,
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
        )
      )
  }
}
