// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.{
  TriggerClass,
  aTrigger,
}
import org.lfdecentralizedtrust.splice.automation.{AutomationService, AutomationServiceCompanion}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.*
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.concurrent.ExecutionContext

class DsoDelegateBasedAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
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
      domainTimeSync,
      domainUnpausedSync,
      retryProvider,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.sv.automation.DsoDelegateBasedAutomationService.type =
    DsoDelegateBasedAutomationService

  def start(): Unit = {
    registerTrigger(new AdvanceOpenMiningRoundTrigger(triggerContext, svTaskContext))
    registerTrigger(new CompletedSvOnboardingTrigger(triggerContext, svTaskContext))
    if (config.automation.enableDsoGovernance) {
      registerTrigger(
        new ExecuteConfirmedActionTrigger(
          triggerContext,
          svTaskContext,
        )
      )
    }
    registerTrigger(new MergeMemberTrafficContractsTrigger(triggerContext, svTaskContext))

    if (config.automation.enableExpireAmulet) {
      registerTrigger(new ExpiredAmuletTrigger(triggerContext, svTaskContext))
    }

    registerTrigger(new ExpiredLockedAmuletTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredSvOnboardingRequestTrigger(triggerContext, svTaskContext))
    registerTrigger(new CloseVoteRequestTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredSvOnboardingConfirmedTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireIssuingMiningRoundTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireStaleConfirmationsTrigger(triggerContext, svTaskContext))
    registerTrigger(new GarbageCollectAmuletPriceVotesTrigger(triggerContext, svTaskContext))

    registerTrigger(new MergeUnclaimedRewardsTrigger(triggerContext, svTaskContext))
    registerTrigger(
      new ExpireRewardCouponsTrigger(
        triggerContext,
        svTaskContext,
      )
    )

    registerTrigger(new AnsSubscriptionRenewalPaymentTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredAnsEntryTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpireTransferPreapprovalsTrigger(triggerContext, svTaskContext))
    registerTrigger(new ExpiredAnsSubscriptionTrigger(triggerContext, svTaskContext))
    registerTrigger(new TerminatedSubscriptionTrigger(triggerContext, svTaskContext))
    registerTrigger(new MergeSvRewardStateContractsTrigger(triggerContext, svTaskContext))

    registerTrigger(
      new MergeValidatorLicenseContractsTrigger(
        triggerContext,
        svTaskContext,
      )
    )

    registerTrigger(
      new FeaturedAppActivityMarkerTrigger(
        triggerContext,
        svTaskContext,
        config,
      )
    )

    registerTrigger(
      new AllocateUnallocatedUnclaimedActivityRecordTrigger(
        triggerContext,
        svTaskContext,
      )
    )
    registerTrigger(
      new ExpiredUnallocatedUnclaimedActivityRecordTrigger(
        triggerContext,
        svTaskContext,
      )
    )
    registerTrigger(
      new ExpiredUnclaimedActivityRecordTrigger(
        triggerContext,
        svTaskContext,
      )
    )
  }

}

object DsoDelegateBasedAutomationService extends AutomationServiceCompanion {
  // defined because the service isn't available immediately in sv app state,
  // but created later by the restart trigger
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq(
    aTrigger[AdvanceOpenMiningRoundTrigger],
    aTrigger[CompletedSvOnboardingTrigger],
    aTrigger[ExecuteConfirmedActionTrigger],
    aTrigger[MergeMemberTrafficContractsTrigger],
    aTrigger[ExpiredAmuletTrigger],
    aTrigger[ExpiredLockedAmuletTrigger],
    aTrigger[ExpiredSvOnboardingRequestTrigger],
    aTrigger[CloseVoteRequestTrigger],
    aTrigger[ExpiredSvOnboardingConfirmedTrigger],
    aTrigger[ExpireIssuingMiningRoundTrigger],
    aTrigger[ExpireStaleConfirmationsTrigger],
    aTrigger[GarbageCollectAmuletPriceVotesTrigger],
    aTrigger[MergeUnclaimedRewardsTrigger],
    aTrigger[ExpireRewardCouponsTrigger],
    aTrigger[AnsSubscriptionRenewalPaymentTrigger],
    aTrigger[ExpiredAnsEntryTrigger],
    aTrigger[ExpireTransferPreapprovalsTrigger],
    aTrigger[ExpiredAnsSubscriptionTrigger],
    aTrigger[TerminatedSubscriptionTrigger],
    aTrigger[MergeSvRewardStateContractsTrigger],
    aTrigger[MergeValidatorLicenseContractsTrigger],
    aTrigger[FeaturedAppActivityMarkerTrigger],
    aTrigger[AllocateUnallocatedUnclaimedActivityRecordTrigger],
    aTrigger[ExpiredUnallocatedUnclaimedActivityRecordTrigger],
    aTrigger[ExpiredUnclaimedActivityRecordTrigger],
  )
}
