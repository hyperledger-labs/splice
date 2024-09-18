// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.automation.UnassignTrigger.GetTargetDomain
import com.daml.network.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  TransferFollowTrigger,
  UnassignTrigger,
}
import AutomationServiceCompanion.{TriggerClass, aTrigger}
import com.daml.network.codegen.java.splice.wallet.payment as paymentCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.*
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.util.QualifiedName
import com.daml.network.wallet.config.{AutoAcceptTransfersConfig, WalletSweepConfig}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.daml.network.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: SpliceLedgerClient,
    decentralizedSynchronizer: GetTargetDomain,
    automationConfig: AutomationConfig,
    supportsSoftDomainMigrationPoc: Boolean,
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
      PackageIdResolver.inferFromAmuletRules(
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
  override def companion = UserWalletAutomationService

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

  if (!supportsSoftDomainMigrationPoc) {
    registerTrigger(
      new UnassignTrigger.Template(
        triggerContext,
        store,
        connection,
        decentralizedSynchronizer,
        store.key.endUserParty,
        paymentCodegen.AppPaymentRequest.COMPANION,
      )
    )
    registerTrigger(
      new TransferFollowTrigger(
        triggerContext,
        store,
        connection,
        store.key.endUserParty,
        implicit tc =>
          scanConnection.getAmuletRulesWithState() flatMap { amuletRules =>
            amuletRules.toAssignedContract map { amuletRules =>
              store
                .listLaggingAmuletRulesFollowers(amuletRules.domain)
                .map(_ map (FollowTask(amuletRules, _)))
            } getOrElse Future.successful(Seq.empty)
          },
      )
    )
    registerTrigger(new AssignTrigger(triggerContext, store, connection, store.key.endUserParty))
  }

  walletSweep.foreach { config =>
    registerTrigger(
      new WalletSweepTrigger(
        triggerContext,
        store,
        connection,
        config,
        scanConnection,
      )
    )
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
}

object UserWalletAutomationService extends AutomationServiceCompanion {
  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] =
    // ImportCrates are created before AmuletRules. Given that this is only a hack until we have upgrading
    // we can hardcode this.
    Option.when(template.moduleName == "Splice.AmuletImport")(
      DarResources.amulet.bootstrap.packageId
    )

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
      aTrigger[WalletSweepTrigger],
      aTrigger[AutoAcceptTransferOffersTrigger],
      aTrigger[AmuletMetricsTrigger],
    )
}
