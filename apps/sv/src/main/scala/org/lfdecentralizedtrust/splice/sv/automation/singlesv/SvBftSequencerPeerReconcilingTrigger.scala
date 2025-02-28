// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
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
    migrationId: Long,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SvTopologyStatePollingAndAssignedTrigger[BftPeerDifference](
      baseContext,
      store,
    ) {

  override val reconciler
      : org.lfdecentralizedtrust.splice.sv.onboarding.SequencerBftPeerReconciler =
    new SequencerBftPeerReconciler(
      store,
      sequencerAdminConnection,
      loggerFactory,
      migrationId,
    )

}
