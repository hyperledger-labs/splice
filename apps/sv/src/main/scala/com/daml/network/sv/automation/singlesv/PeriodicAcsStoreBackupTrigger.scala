package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PeriodicTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.config.PeriodicBackupDumpConfig
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicAcsStoreBackupTrigger(
    config: PeriodicBackupDumpConfig,
    triggerContext: TriggerContext,
    svcStore: SvSvcStore,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.Task
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = {
    SvUtil
      .writeAcsStoreDump(
        config.location,
        loggerFactory,
        svcStore,
        task.now,
      )
      .map { case (path, dump @ _) => TaskSuccess(s"Wrote ACS store dump to $path") }
  }
}
