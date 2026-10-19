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
import org.lfdecentralizedtrust.splice.store.{DomainTimeSynchronization, UnavailablePartiesStore}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{BftScanConnection, ScanConnection}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.*
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpiredAmuletAllocationTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.concurrent.{ExecutionContextExecutor, Future}

class DsoDelegateBasedAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    config: SvAppBackendConfig,
    svTaskContext: SvTaskBasedTrigger.Context,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    val unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      config.automation,
      clock,
      domainTimeSync,
      retryProvider,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.sv.automation.DsoDelegateBasedAutomationService.type =
    DsoDelegateBasedAutomationService

  def start(): Unit = {
    registerTrigger(new AdvanceOpenMiningRoundTrigger(triggerContext, svTaskContext))
    registerTrigger(new UpdateExternalPartyConfigStateTrigger(triggerContext, svTaskContext))
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

    registerTrigger(
      new ExpiredAmuletTrigger(
        config,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpiredLockedAmuletTrigger(
        config,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpiredAmuletTransferInstructionTrigger(
        config,
        clock,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpiredAmuletAllocationTrigger(
        config,
        clock,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpiredAmuletAllocationV2Trigger(
        config,
        clock,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )
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
        unavailablePartiesStore,
        config,
      )
    )

    registerTrigger(new AnsSubscriptionRenewalPaymentTrigger(triggerContext, svTaskContext))
    registerTrigger(
      new ExpiredAnsEntryTrigger(
        triggerContext,
        svTaskContext,
        config,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpireTransferPreapprovalsTrigger(
        triggerContext,
        svTaskContext,
        config,
        unavailablePartiesStore,
      )
    )
    registerTrigger(
      new ExpiredAnsSubscriptionTrigger(
        triggerContext,
        svTaskContext,
        config,
        unavailablePartiesStore,
      )
    )
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
        unavailablePartiesStore,
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
    registerTrigger(
      new MergeUnclaimedDevelopmentFundCouponsTrigger(config, triggerContext, svTaskContext)
    )
    registerTrigger(
      new ExpiredDevelopmentFundCouponTrigger(
        triggerContext,
        svTaskContext,
      )
    )

    registerTrigger(
      new ExpireRewardCouponV2Trigger(
        config,
        triggerContext,
        svTaskContext,
        unavailablePartiesStore,
      )
    )

    registerTrigger(new UnhideRewardCouponV2Trigger(config, triggerContext, svTaskContext))

    registerTrigger(
      new BootstrapExternalPartyConfigStateInstructionTrigger(
        triggerContext,
        svTaskContext,
      )
    )

    registerTrigger(
      new ProcessRewardsTrigger(
        triggerContext,
        svTaskContext,
        getOwnScanConnection,
        getPeerBftScanConnection,
      )
    )
    registerTrigger(
      new ProcessRewardsDryRunTrigger(
        triggerContext,
        svTaskContext,
        getOwnScanConnection,
        getPeerBftScanConnection,
      )
    )
  }

}

object DsoDelegateBasedAutomationService extends AutomationServiceCompanion {
  // defined because the service isn't available immediately in sv app state,
  // but created later
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq(
    aTrigger[AdvanceOpenMiningRoundTrigger],
    aTrigger[UpdateExternalPartyConfigStateTrigger],
    aTrigger[CompletedSvOnboardingTrigger],
    aTrigger[ExecuteConfirmedActionTrigger],
    aTrigger[MergeMemberTrafficContractsTrigger],
    aTrigger[ExpiredAmuletTrigger],
    aTrigger[ExpiredLockedAmuletTrigger],
    aTrigger[ExpiredAmuletTransferInstructionTrigger],
    aTrigger[ExpiredAmuletAllocationTrigger],
    aTrigger[ExpiredAmuletAllocationV2Trigger],
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
    aTrigger[MergeUnclaimedDevelopmentFundCouponsTrigger],
    aTrigger[ExpiredDevelopmentFundCouponTrigger],
    aTrigger[ExpireRewardCouponV2Trigger],
    aTrigger[UnhideRewardCouponV2Trigger],
    aTrigger[BootstrapExternalPartyConfigStateInstructionTrigger],
    aTrigger[ProcessRewardsTrigger],
    aTrigger[ProcessRewardsDryRunTrigger],
  )
}
