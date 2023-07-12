package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.config.BackupDumpConfig
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicAcsStoreBackupTrigger(
    config: BackupDumpConfig,
    triggerContext: TriggerContext,
    svcStore: SvSvcStore,
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
      .retryForAutomation(
        s"backup AcsStore dump to: ${config.locationDescription}",
        SvUtil.writeAcsStoreDump(
          config,
          loggerFactory,
          svcStore,
          triggerContext.clock,
        ),
        logger,
      )
      .map(_ =>
        // We signal that no more work is available to make the polling trigger wait for the backup interval
        false
      )
  }
}
