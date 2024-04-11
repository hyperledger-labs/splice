package com.daml.network.validator.automation

import com.daml.network.automation.{PeriodicTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.config.PeriodicBackupDumpConfig
import com.daml.network.identities.NodeIdentitiesStore
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class PeriodicParticipantIdentitiesBackupTrigger(
    config: PeriodicBackupDumpConfig,
    scanConnection: BftScanConnection,
    triggerContext: TriggerContext,
    participantIdentitiesStore: NodeIdentitiesStore,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PeriodicTaskTrigger(config.backupInterval, triggerContext) {

  override def completeTask(
      task: PeriodicTaskTrigger.PeriodicTask
  )(implicit traceContext: TraceContext): Future[TaskOutcome] = for {
    domain <- amuletRulesDomain()
    path <- participantIdentitiesStore.backupNodeIdentities(domain)
  } yield TaskSuccess(s"Backed up participant identities to $path")

  private[this] def amuletRulesDomain()(implicit tc: TraceContext) =
    scanConnection.getAmuletRulesDomain()(tc)
}
