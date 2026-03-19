// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerAddReconciler
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.ExecutionContext

/** Ensure the local BFT sequencer peer list is in sync with the peer urls configured by the other sequncers in the daml state by adding peers that are part of the
  * daml state but not part of the local peer list
  */
class SvBftSequencerPeerOnboardingTrigger(
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
      None,
    ) {

  // Don't pause when the synchronizer is paused or lagging;
  // this trigger might be needed for unblocking a stuck BFT layer.
  override protected lazy val context: TriggerContext =
    noParallelismContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop)

  override val reconciler: SequencerBftPeerAddReconciler =
    new SequencerBftPeerAddReconciler(
      store,
      sequencerAdminConnection,
      loggerFactory,
      scanConnection,
      migrationId,
    )

}
