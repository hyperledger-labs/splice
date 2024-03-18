package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to reconcile the CometBFT network configuration maintained by the ABCI app running on CometBFT
  * with the SVC-wide shared configuration in the SvcRules contract.
  */
class ReconcileCometBftNetworkConfigWithSvcRulesTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    cometBftNode: CometBftNode,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      rulesAndState <- store.getSvcRulesWithMemberNodeStates()
      owningNodeSvName <- rulesAndState.getSvMemberName(store.key.svParty)
      _ <- cometBftNode.reconcileNetworkConfig(owningNodeSvName, rulesAndState)
    } yield false
  }
}
