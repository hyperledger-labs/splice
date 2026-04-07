// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger to reconcile the CometBFT network configuration maintained by the ABCI app running on CometBFT
  * with the DSO-wide shared configuration in the DsoRules contract.
  */
class ReconcileCometBftNetworkConfigWithDsoRulesTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    cometBftNode: CometBftNode,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      rulesAndState <- store.getDsoRulesWithSvNodeStates()
      owningNodeSvName <- rulesAndState.getSvNameInDso(store.key.svParty)
      _ <- cometBftNode.reconcileNetworkConfig(owningNodeSvName, rulesAndState)
    } yield false
  }
}
