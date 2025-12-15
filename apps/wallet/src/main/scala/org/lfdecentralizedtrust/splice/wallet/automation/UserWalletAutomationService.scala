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
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.QualifiedName
import org.lfdecentralizedtrust.splice.wallet.config.{AutoAcceptTransfersConfig, WalletSweepConfig}
import org.lfdecentralizedtrust.splice.wallet.store.{
  FetchCommandPriority,
  TxLogEntry,
  UserWalletStore,
  ValidatorLicenseStore,
}
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.{ExecutionContext, Future}

class UserWalletAutomationService(
    store: UserWalletStore,
    licenseStore: ValidatorLicenseStore,
    val updateHistory: UpdateHistory,
    treasury: TreasuryService,
    ledgerClient: SpliceLedgerClient,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    scanConnection: BftScanConnection,
    retryProvider: RetryProvider,
    packageVersionSupport: PackageVersionSupport,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    walletSweep: Option[WalletSweepConfig],
    autoAcceptTransfers: Option[AutoAcceptTransfersConfig],
    dedupDuration: DedupDuration,
    txLogBackfillEnabled: Boolean,
    txLogBackfillingBatchSize: Int,
    paramsConfig: SpliceParametersConfig,
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
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
      paramsConfig,
    ) {
  override def companion
      : org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService.type =
    UserWalletAutomationService

  registerUpdateHistoryIngestion(
    updateHistory,
    ingestUpdateHistoryFromParticipantBegin,
  )

  registerTrigger(
    new ExpireTransferOfferTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
  registerTrigger(
    new ExpireAcceptedTransferOfferTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
  registerTrigger(
    new ExpireBuyTrafficRequestsTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
  registerTrigger(new SubscriptionReadyForPaymentTrigger(triggerContext, store, treasury))
  registerTrigger(
    new AcceptedTransferOfferTrigger(
      triggerContext,
      store,
      treasury,
      connection(SpliceLedgerConnectionPriority.Medium),
    )
  )
  registerTrigger(
    new CompleteBuyTrafficRequestTrigger(
      triggerContext,
      store,
      treasury,
      connection(SpliceLedgerConnectionPriority.High),
    )
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
    validatorTopupConfigO match {
      case Some(_) => ()
        // true iff this a validatorParty; faucet coupons are handled by ValidatorAutomation
      case None => {
        val priorityFetcher = new FetchCommandPriority() {
           override def getCommandPriority()(
             implicit  tc: TraceContext
           ): Future[CommandPriority] = {
              Future.successful(CommandPriority.Low)
           }
        }
        registerTrigger(
          new ReceiveFaucetCouponTrigger(
            triggerContext,
            scanConnection,
            licenseStore,
            connection(SpliceLedgerConnectionPriority.Medium),
            store.key.endUserParty,
            priorityFetcher
          )
        )
      }
    }
  }

  walletSweep.foreach { config =>
    if (config.useTransferPreapproval) {
      registerTrigger(
        new WalletPreapprovalSweepTrigger(
          triggerContext,
          store,
          connection(SpliceLedgerConnectionPriority.Low),
          config,
          scanConnection,
          treasury,
          dedupDuration,
          packageVersionSupport,
        )
      )
    } else {
      registerTrigger(
        new WalletTransferOfferSweepTrigger(
          triggerContext,
          store,
          connection(SpliceLedgerConnectionPriority.Low),
          config,
          scanConnection,
          packageVersionSupport,
        )
      )
    }
  }

  autoAcceptTransfers.foreach { config =>
    registerTrigger(
      new AutoAcceptTransferOffersTrigger(
        triggerContext,
        store,
        connection(SpliceLedgerConnectionPriority.Low),
        config,
        scanConnection,
        validatorTopupConfigO,
        clock,
      )
    )
  }

  registerTrigger(
    new AmuletMetricsTrigger(triggerContext, store, scanConnection, packageVersionSupport)
  )

  if (txLogBackfillEnabled) {
    registerTrigger(
      new TxLogBackfillingTrigger(
        store,
        updateHistory,
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
      aTrigger[ReceiveFaucetCouponTrigger],
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
