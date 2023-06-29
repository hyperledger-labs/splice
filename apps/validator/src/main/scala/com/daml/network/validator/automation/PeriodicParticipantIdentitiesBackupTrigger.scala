package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.validator.store.ParticipantIdentitiesStore
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicParticipantIdentitiesBackupTrigger(
    locationDescription: String,
    backupInterval: NonNegativeFiniteDuration,
    triggerContext: TriggerContext,
    participantIdentitiesStore: ParticipantIdentitiesStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override protected def context: TriggerContext = triggerContext.copy(
    config = triggerContext.config.copy(
      pollingInterval = backupInterval
    )
  )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    triggerContext.retryProvider
      .retryForAutomation(
        s"backup participant identities to: $locationDescription",
        participantIdentitiesStore.backupParticipantIdentities(),
        logger,
      )
      .map(_ =>
        // We signal that no more work is available to make the polling trigger wait for the backup interval
        false
      )
  }
}
