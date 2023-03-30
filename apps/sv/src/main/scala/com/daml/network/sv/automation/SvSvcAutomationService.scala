package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvSvcAutomationService(
    clock: Clock,
    config: LocalSvAppConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      Map(svcStore.key.svcParty -> svcStore),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  registerTrigger(new AdvanceOpenMiningRoundTrigger(triggerContext, svcStore, connection))
  registerTrigger(new CompletedSvOnboardingTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExecuteConfirmedActionTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExpiredCoinTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExpiredLockedCoinTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExpiredSvOnboardingTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExpiredSvConfirmedTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvOnboardingTrigger(triggerContext, svcStore, svStore, connection))
  registerTrigger(new SvcRewardTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, svcStore, connection))
  if (config.automation.enableUnclaimedRewardExpiration) {
    registerTrigger(new ExpireRewardCouponsTrigger(triggerContext, svcStore, connection))
  }
  registerTrigger(new MergeUnclaimedRewardsTrigger(triggerContext, svcStore, connection))
}
