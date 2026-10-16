// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.*
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService

import scala.concurrent.{ExecutionContext, Future}

class SvOnboardingSnapshotCleanupTrigger(
    triggerContext: TriggerContext,
    snapshotService: SvOnboardingSnapshotService,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PeriodicTaskTrigger(
      triggerContext.copy(triggerEnabledSync = TriggerEnabledSynchronization.Noop),
      quiet = true,
    ) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] =
    snapshotService.removeExpired(task.now).map {
      case 0 => TaskNoop
      case removed => TaskSuccess(s"Removed $removed expired onboarding snapshots")
    }
}
