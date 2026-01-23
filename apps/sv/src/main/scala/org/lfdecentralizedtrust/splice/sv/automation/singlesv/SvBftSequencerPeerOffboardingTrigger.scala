// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler.BftPeerDifference
import org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerRemoveReconciler
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore

import scala.concurrent.ExecutionContext

/** Ensure the local BFT sequencer peer list is in sync with the peer urls configured by the other sequncers in the daml state by removing peers that are part of the
  * peer list but not part of the daml state
  */
class SvBftSequencerPeerOffboardingTrigger(
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

  override val reconciler: SequencerBftPeerRemoveReconciler =
    new SequencerBftPeerRemoveReconciler(
      store,
      sequencerAdminConnection,
      loggerFactory,
      scanConnection,
      migrationId,
    )

}
