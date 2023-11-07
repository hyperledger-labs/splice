package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import cats.implicits.catsSyntaxApplicativeId
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
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    OptionT(store.lookupSvcRules())
      .subflatMap { svcRules =>
        CometBftNode
          .extractSvNodeMemberInfo(svcRules.payload, store.key.svParty)
          .map(svcRules -> _)
      }
      // in all cases there is no more work left to do, and the trigger should just wait for another polling interval
      // before doing another reconciliation
      // TODO(M3-47): consider whether the 10s default polling interval is fast enough; related to #4492, which aims to lower the default polling interval in tests
      .foldF(false.pure[Future]) { case (svcRules, owningNodeMemberInfo) =>
        cometBftNode
          .reconcileNetworkConfig(owningNodeMemberInfo.name, svcRules)
          .map(_ => false)
      }
  }
}
