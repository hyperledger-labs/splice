// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice as daml
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeConfigClient
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeState
import com.digitalasset.canton.drivers as proto
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting, PrettyUtil}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to publish the node-id's and validator keys of an SV's CometBFT nodes
  * to the DSO-wide shared configuration of what validators should be used in the network.
  */
class PublishLocalCometBftNodeConfigTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    cometBftNode: CometBftNode,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
    ]
    with SynchronizerNodeConfigClient {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask]] =
    (for {
      localSvNodeConfig <- OptionT.liftF(cometBftNode.getLocalNodeConfig())
      (rulesAndState, synchronizerNodeConfig) <- getCometBftNodeConfigDsoState(
        store,
        store.key.svParty,
      )
      // TODO (#4901) reconcile cometBft networks for multiple domains instead using one default domain id here.
      // create a task if the local config is different than the one we have published to the DSO
      // or if no domain bft is configured
      if !synchronizerNodeConfig
        .map(synchronizerNodeConfig =>
          CometBftNode.svNodeConfigToProto(synchronizerNodeConfig.cometBft)
        )
        .contains(localSvNodeConfig)
    } yield PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask(
      rulesAndState,
      synchronizerNodeConfig,
      localSvNodeConfig,
    )).value
      .map(_.toList)

  override protected def completeTask(
      task: PublishLocalCometBftNodeConfigTrigger.PublishLocalConfigTask
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      _ <- updateSynchronizerNodeConfig(
        task.dsoRulesAndState,
        task.damlSvNodeConfig,
        store,
        connection,
      )
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
      dsoRulesAndState: DsoRulesWithSvNodeState,
      synchronizerNodeConfig: Option[SynchronizerNodeConfig],
      localSvNodeConfig: proto.cometbft.SvNodeConfig,
  ) extends PrettyPrinting
      with SynchronizerNodeConfigClient {

    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    implicit private val svNodeConfigPretty: Pretty[proto.cometbft.SvNodeConfig] =
      PrettyUtil.adHocPrettyInstance

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("dsoRulesAndState", _.dsoRulesAndState),
      param("localSvNodeConfig", _.localSvNodeConfig),
    )

    // TODO(#5889): unify or align with CometBftNode.getLocalNodeConfig and the functions backing `diffNetworkConfig`
    val damlSvNodeConfig: daml.dso.decentralizedsynchronizer.SynchronizerNodeConfig =
      getNewSynchronizerNodeConfig(synchronizerNodeConfig, localSvNodeConfig)
  }
}
