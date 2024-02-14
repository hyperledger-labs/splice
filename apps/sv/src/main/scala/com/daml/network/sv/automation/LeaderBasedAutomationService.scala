package com.daml.network.sv.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{AutomationServiceCompanion, AutomationService}
import AutomationServiceCompanion.{TriggerClass, aTrigger}
import com.daml.network.environment.RetryProvider
import com.daml.network.sv.automation.leaderbased.*
import com.daml.network.sv.config.SvAppBackendConfig
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
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      config.automation,
      clock,
      retryProvider,
    ) {

  override def companion = LeaderBasedAutomationService

  def start(): Unit = {
    registerTrigger(new AdvanceOpenMiningRoundTrigger(triggerContext, svTaskContext))
    registerTrigger(new CompletedSvOnboardingTrigger(triggerContext, svTaskContext))
    if (config.automation.enableSvcGovernance) {
      registerTrigger(new ExecuteConfirmedActionTrigger(triggerContext, svTaskContext))
      registerTrigger(new ExecuteVoteRequestActionTrigger(triggerContext, svTaskContext))
    }
    registerTrigger(new MergeMemberTrafficContractsTrigger(triggerContext, svTaskContext))

    if (config.automation.enableExpireCoin) {
      registerTrigger(new ExpiredCoinTrigger(triggerContext, svTaskContext))
    }

    registerTrigger(new ExpiredLockedCoinTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredSvOnboardingRequestTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireVoteRequestTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredSvOnboardingConfirmedTrigger(triggerContext, svTaskContext))
    registerTrigger(new SvcRewardTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireIssuingMiningRoundTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireStaleConfirmationsTrigger(triggerContext, svTaskContext))
    registerTrigger(new GarbageCollectCoinPriceVotesTrigger(triggerContext, svTaskContext))

    registerTrigger(new MergeUnclaimedRewardsTrigger(triggerContext, svTaskContext))
    if (config.automation.enableUnclaimedRewardExpiration) {
      registerTrigger(new ExpireRewardCouponsTrigger(triggerContext, svTaskContext))
    }

    registerTrigger(new ExpireElectionRequestsTrigger(triggerContext, svTaskContext))
    registerTrigger(new CnsSubscriptionRenewalPaymentTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredCnsEntryTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredCnsSubscriptionTrigger(triggerContext, svTaskContext))
    registerTrigger(new TerminatedSubscriptionTrigger(triggerContext, svTaskContext))
  }

}

object LeaderBasedAutomationService extends AutomationServiceCompanion {
  // defined because the service isn't available immediately in sv app state,
  // but created later by the restart trigger
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq(
    aTrigger[AdvanceOpenMiningRoundTrigger],
    aTrigger[CompletedSvOnboardingTrigger],
    aTrigger[ExecuteConfirmedActionTrigger],
    aTrigger[ExecuteVoteRequestActionTrigger],
    aTrigger[MergeMemberTrafficContractsTrigger],
    aTrigger[ExpiredCoinTrigger],
    aTrigger[ExpiredLockedCoinTrigger],
    aTrigger[ExpiredSvOnboardingRequestTrigger],
    aTrigger[ExpireVoteRequestTrigger],
    aTrigger[ExpiredSvOnboardingConfirmedTrigger],
    aTrigger[SvcRewardTrigger],
    aTrigger[ExpireIssuingMiningRoundTrigger],
    aTrigger[ExpireStaleConfirmationsTrigger],
    aTrigger[GarbageCollectCoinPriceVotesTrigger],
    aTrigger[MergeUnclaimedRewardsTrigger],
    aTrigger[ExpireRewardCouponsTrigger],
    aTrigger[ExpireElectionRequestsTrigger],
    aTrigger[CnsSubscriptionRenewalPaymentTrigger],
    aTrigger[ExpiredCnsEntryTrigger],
    aTrigger[ExpiredCnsSubscriptionTrigger],
    aTrigger[TerminatedSubscriptionTrigger],
  )
}
