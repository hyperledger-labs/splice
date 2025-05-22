// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.ExecutionContext

/** Ensure the local BFT sequencer peer list is in sync with the peer urls configured by the other sequncers in the daml state
  */
class SvBftSequencerPeerReconcilingTrigger(
    baseContext: TriggerContext,
    store: SvDsoStore,
    sequencerAdminConnection: SequencerAdminConnection,
    scanConnection: AggregatingScanConnection,
    migrationId: Long,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[BftPeerDifference](
      baseContext,
      store,
    ) {

  // Don't pause when the synchronizer is paused or lagging;
  // this trigger might be needed for unblocking a stuck BFT layer.
  // TODO(#19670) Double-check how safe this is if we end up putting p2p URLs in Daml again.
  override protected lazy val context: TriggerContext =
    noParallelismContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override val reconciler
      : org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler =
    new SequencerBftPeerReconciler(
      store,
      sequencerAdminConnection,
      loggerFactory,
      scanConnection,
      migrationId,
    )

}
