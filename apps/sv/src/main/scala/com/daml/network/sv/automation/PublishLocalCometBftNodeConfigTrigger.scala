package com.daml.network.sv.automation

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn as daml
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.automation.PublishLocalCometBftNodeConfigTrigger.*
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A trigger to publish the node-id's and validator keys of an SV's CometBFT nodes
  * to the SVC-wide shared configuration of what validators should be used in the network.
  *
  * This is not yet hooked up, as we're missing the CometBFTClient to actually talk to the local node.
  *
  * TODO(#4628): hook it up
  */
class PublishLocalCometBftNodeConfigTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[PublishLocalConfigTask] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishLocalConfigTask]] =
    (for {
      svcRules <- OptionT(store.lookupSvcRules())
      members = svcRules.payload.members.asScala
      // require our svParty to be registered as a member
      damlSvNodeConfig <- OptionT(
        Future.successful(members.get(store.key.svParty.toProtoPrimitive))
      )
      // create a task if the local config is different than the one we have published to the SVC
      localSvNodeConfig <- getLocalSvNodeConfig()
      if (CometBftNode.svNodeConfigToProto(damlSvNodeConfig.cometBftConfig) != localSvNodeConfig)
    } yield PublishLocalConfigTask(svcRules, localSvNodeConfig)).value.map(_.toList)

  private def getLocalSvNodeConfig(): OptionT[Future, proto.cometbft.SvNodeConfig] = {
    throw new NotImplementedError(
      "TODO(#4630): implement this by querying the local RPC, if available"
    )
  }

  override protected def completeTask(task: PublishLocalConfigTask)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] =
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      cmd = task.svcRules.contractId.exerciseSvcRules_SetCometBftConfig(
        store.key.svParty.toProtoPrimitive,
        task.damlSvNodeConfig,
      )
      (offset, _) <- connection.submitWithResultAndOffsetNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmd,
        domainId = domainId,
      )
      // make sure the store ingested our update so we don't
      // attempt to advance the same round twice
      _ <- store.multiDomainAcsStore.signalWhenIngestedOrShutdown(domainId, offset)

    } yield TaskSuccess(show"Updated SVC-wide CometBFT node configuration for ${store.key.svParty}")

  override protected def isStaleTask(
      task: PublishLocalConfigTask
  )(implicit tc: TraceContext): Future[Boolean] =
    (for {
      // not stale if the task's SvcRules contract is still active
      _ <- OptionT(
        store.multiDomainAcsStore
          .lookupContractById(daml.svcrules.SvcRules.COMPANION)(task.svcRules.contractId)
      )
      // and if the localSvNodeConfig is still the same
      localSvNodeConfig <- getLocalSvNodeConfig()
      // TODO(M3-47): figure out how to remove this dummy reference to avoid an unused param warning :'(
      _ = tc.traceId
      if (task.localSvNodeConfig == localSvNodeConfig)
    } yield ()).isEmpty
}

object PublishLocalCometBftNodeConfigTrigger {
  case class PublishLocalConfigTask(
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

    val damlSvNodeConfig: daml.cometbft.SvNodeConfig =
      new daml.cometbft.SvNodeConfig(
        localSvNodeConfig.cometbftNodes.map { case (nodeId, config) =>
          (
            nodeId,
            new daml.cometbft.CometBftNodeConfig(
              config.validatorPubKey,
              config.votingPower,
            ),
          )
        }.asJava,
        Seq[daml.cometbft.GovernanceKeyConfig]().asJava,
        Seq[daml.cometbft.SequencingKeyConfig]().asJava,
      )

  }
}
