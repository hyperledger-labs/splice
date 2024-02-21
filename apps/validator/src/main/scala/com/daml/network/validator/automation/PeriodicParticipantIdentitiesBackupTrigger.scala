package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.config.PeriodicBackupDumpConfig
import com.daml.network.environment.RetryFor
import com.daml.network.identities.NodeIdentitiesStore
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
        s"backup participant identities to: ${config.location.locationDescription}",
        participantIdentitiesStore.backupNodeIdentities(),
        logger,
      )
      .map(_ =>
        // We signal that no more work is available to make the polling trigger wait for the backup interval
        false
      )
  }
}
