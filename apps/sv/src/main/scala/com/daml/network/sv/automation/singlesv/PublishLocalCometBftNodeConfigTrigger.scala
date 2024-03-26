package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice as daml
import com.daml.network.codegen.java.splice.cometbft.SequencingKeyConfig
import com.daml.network.codegen.java.splice.dso.globaldomain.{
  MediatorConfig,
  ScanConfig,
  SequencerConfig,
}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.sv.store.SvDsoStore.DsoRulesWithSvNodeState
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
  * to the DSO-wide shared configuration of what validators should be used in the network.
  */
class PublishLocalCometBftNodeConfigTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: CNLedgerConnection,
    cometBftNode: CometBftNode,
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
      rulesAndState <- OptionT.liftF(store.getDsoRulesWithSvNodeState(store.key.svParty))
      domainId = rulesAndState.dsoRules.domain
      nodeState = rulesAndState.svNodeState.payload
      domainNodeConfig = nodeState.state.domainNodes.asScala.get(domainId.toProtoPrimitive)
      localSvNodeConfig <- OptionT.liftF(cometBftNode.getLocalNodeConfig())
      // TODO (#4901) reconcile cometBft networks for multiple domains instead using one default domain id here.
      domainId = rulesAndState.dsoRules.domain
      // create a task if the local config is different than the one we have published to the DSO
      // or if no domain bft is configured
      if
      // "TODO(#4901): reconcile all configured CometBFT networks"
      !domainNodeConfig
        .map(domainNodeConfig => CometBftNode.svNodeConfigToProto(domainNodeConfig.cometBft))
        .contains(localSvNodeConfig)
    } yield PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask(
      nodeState.svName,
      rulesAndState,
      domainNodeConfig.flatMap(_.sequencer.toScala).toJava,
      domainNodeConfig.flatMap(_.mediator.toScala).toJava,
      domainNodeConfig.flatMap(_.scan.toScala).toJava,
      localSvNodeConfig,
      domainId,
    )).value
      .map(_.toList)

  override protected def completeTask(
      task: PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val cmd = task.dsoRulesAndState.dsoRules.exercise(
      _.exerciseDsoRules_SetDomainNodeConfig(
        store.key.svParty.toProtoPrimitive,
        task.domainId.toProtoPrimitive,
        task.damlSvNodeConfig,
        task.dsoRulesAndState.svNodeState.contractId,
      )
    )
    for {
      _ <- connection
        .submit(Seq(store.key.svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(show"Updated DSO-wide CometBFT node configuration for ${store.key.svParty}")
  }

  override protected def isStaleTask(
      task: PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      staleContracts <- task.dsoRulesAndState.isStale(store.multiDomainAcsStore)
      currentNodeConfig <- cometBftNode.getLocalNodeConfig()
    } yield {
      staleContracts || task.localSvNodeConfig != currentNodeConfig
    }
}

object PublishLocalCometBftNodeConfigTrigger {
  case class PublishLocalConfigTask(
      svNodeId: String,
      dsoRulesAndState: DsoRulesWithSvNodeState,
      sequencerConfig: Optional[SequencerConfig],
      mediatorConfig: Optional[MediatorConfig],
      scanConfig: Optional[ScanConfig],
      localSvNodeConfig: proto.cometbft.SvNodeConfig,
      domainId: DomainId,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    implicit private val svNodeConfigPretty: Pretty[proto.cometbft.SvNodeConfig] =
      PrettyUtil.adHocPrettyInstance

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("dsoRulesAndState", _.dsoRulesAndState),
      param("localSvNodeConfig", _.localSvNodeConfig),
    )

    // TODO(#5889): unify or align with CometBftNode.getLocalNodeConfig and the functions backing `diffNetworkConfig`
    val damlSvNodeConfig: daml.dso.globaldomain.DomainNodeConfig =
      new daml.dso.globaldomain.DomainNodeConfig(
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
        scanConfig,
      )
  }
}
