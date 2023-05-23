package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.AutomationService
import com.daml.network.environment.RetryProvider
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class LeaderBasedAutomationService(
    clock: Clock,
    config: SvAppBackendConfig,
    svTaskContext: SvTaskBasedTrigger.Context,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      config.automation,
      clock,
      retryProvider,
    ) {

  registerTrigger(new AdvanceOpenMiningRoundTrigger(triggerContext, svTaskContext))
  registerTrigger(new CompletedSvOnboardingTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExecuteConfirmedActionTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExpiredCoinTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExpiredLockedCoinTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExpiredSvOnboardingRequestTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExpiredSvOnboardingConfirmedTrigger(triggerContext, svTaskContext))
  registerTrigger(new SvcRewardTrigger(triggerContext, svTaskContext))
  registerTrigger(new ExpireStaleConfirmationsTrigger(triggerContext, svTaskContext))
  registerTrigger(new GarbageCollectCoinPriceVotesTrigger(triggerContext, svTaskContext))
}
