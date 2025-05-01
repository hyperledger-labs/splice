// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  TransferFollowTrigger,
  TxLogBackfillingTrigger,
  UnassignTrigger,
}
import AutomationServiceCompanion.{TriggerClass, aTrigger}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.util.QualifiedName
import org.lfdecentralizedtrust.splice.wallet.config.{AutoAcceptTransfersConfig, WalletSweepConfig}
import org.lfdecentralizedtrust.splice.wallet.store.{TxLogEntry, UserWalletStore}
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: SpliceLedgerClient,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    scanConnection: BftScanConnection,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    walletSweep: Option[WalletSweepConfig],
    autoAcceptTransfers: Option[AutoAcceptTransfersConfig],
    dedupDuration: DedupDuration,
    enableCantonPackageSelection: Boolean,
    txLogBackfillEnabled: Boolean,
    txLogBackfillingBatchSize: Int,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      store,
      PackageIdResolver.inferFromAmuletRulesIfEnabled(
        enableCantonPackageSelection,
        clock,
        scanConnection,
        loggerFactory,
        UserWalletAutomationService.bootstrapPackageIdResolver,
      ),
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
    ) {
  override def companion
      : org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService.type =
    UserWalletAutomationService

  registerTrigger(new ExpireTransferOfferTrigger(triggerContext, store, connection))
  registerTrigger(
    new ExpireAcceptedTransferOfferTrigger(triggerContext, store, connection)
  )
  registerTrigger(new ExpireBuyTrafficRequestsTrigger(triggerContext, store, connection))
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(triggerContext, store, connection)
  )
  registerTrigger(new SubscriptionReadyForPaymentTrigger(triggerContext, store, treasury))
  registerTrigger(
    new AcceptedTransferOfferTrigger(triggerContext, store, treasury, connection)
  )
  registerTrigger(
    new CompleteBuyTrafficRequestTrigger(triggerContext, store, treasury, connection)
  )
  if (automationConfig.enableAutomaticRewardsCollectionAndAmuletMerging) {
    registerTrigger(
      new CollectRewardsAndMergeAmuletsTrigger(
        triggerContext,
        store,
        treasury,
        scanConnection,
        validatorTopupConfigO,
        clock,
      )
    )
  }

  walletSweep.foreach { config =>
    if (config.useTransferPreapproval) {
      registerTrigger(
        new WalletPreapprovalSweepTrigger(
          triggerContext,
          store,
          connection,
          config,
          scanConnection,
          treasury,
          dedupDuration,
        )
      )
    } else {
      registerTrigger(
        new WalletTransferOfferSweepTrigger(
          triggerContext,
          store,
          connection,
          config,
          scanConnection,
        )
      )
    }
  }

  autoAcceptTransfers.foreach { config =>
    registerTrigger(
      new AutoAcceptTransferOffersTrigger(
        triggerContext,
        store,
        connection,
        config,
        scanConnection,
        validatorTopupConfigO,
        clock,
      )
    )
  }

  registerTrigger(new AmuletMetricsTrigger(triggerContext, store, scanConnection))

  if (txLogBackfillEnabled) {
    registerTrigger(
      new TxLogBackfillingTrigger(
        store,
        txLogBackfillingBatchSize,
        triggerContext,
      )
    )
  }
}

object UserWalletAutomationService extends AutomationServiceCompanion {
  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] = None

  // defined because instances are created by UserWalletService, not immediately
  // available in the app state
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[ExpireTransferOfferTrigger],
      aTrigger[ExpireAcceptedTransferOfferTrigger],
      aTrigger[ExpireBuyTrafficRequestsTrigger],
      aTrigger[ExpireAppPaymentRequestsTrigger],
      aTrigger[SubscriptionReadyForPaymentTrigger],
      aTrigger[AcceptedTransferOfferTrigger],
      aTrigger[CompleteBuyTrafficRequestTrigger],
      aTrigger[CollectRewardsAndMergeAmuletsTrigger],
      aTrigger[UnassignTrigger.Template[?, ?]],
      aTrigger[AssignTrigger],
      aTrigger[TransferFollowTrigger],
      aTrigger[WalletTransferOfferSweepTrigger],
      aTrigger[WalletPreapprovalSweepTrigger],
      aTrigger[AutoAcceptTransferOffersTrigger],
      aTrigger[AmuletMetricsTrigger],
      aTrigger[TxLogBackfillingTrigger[TxLogEntry]],
    )
}
