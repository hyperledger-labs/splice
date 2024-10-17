// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.{
  PeriodicTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.PeriodicBackupDumpConfig
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicParticipantIdentitiesBackupTrigger(
    config: PeriodicBackupDumpConfig,
    triggerContext: TriggerContext,
    participantIdentitiesStore: NodeIdentitiesStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    path <- participantIdentitiesStore.backupNodeIdentities()
  } yield TaskSuccess(s"Backed up participant identities to $path")
}
