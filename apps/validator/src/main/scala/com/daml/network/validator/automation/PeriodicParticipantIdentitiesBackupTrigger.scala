package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.config.BackupDumpConfig
import com.daml.network.environment.RetryFor
import com.daml.network.validator.store.ParticipantIdentitiesStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicParticipantIdentitiesBackupTrigger(
    config: BackupDumpConfig,
    triggerContext: TriggerContext,
    participantIdentitiesStore: ParticipantIdentitiesStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override protected def context: TriggerContext = triggerContext.copy(
    config = triggerContext.config.copy(
      pollingInterval = config.backupInterval
    )
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    triggerContext.retryProvider
      .retry(
        RetryFor.Automation,
        s"backup participant identities to: ${config.locationDescription}",
        participantIdentitiesStore.backupParticipantIdentities(),
        logger,
      )
      .map(_ =>
        // We signal that no more work is available to make the polling trigger wait for the backup interval
        false
      )
  }
}
