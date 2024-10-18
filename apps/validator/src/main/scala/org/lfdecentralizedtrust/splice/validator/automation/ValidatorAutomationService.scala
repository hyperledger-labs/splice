// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.automation

import org.lfdecentralizedtrust.splice.automation.TransferFollowTrigger.Task as FollowTask
import org.lfdecentralizedtrust.splice.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  TransferFollowTrigger,
}
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, PeriodicBackupDumpConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.util.QualifiedName
import org.lfdecentralizedtrust.splice.validator.config.AppManagerConfig
import org.lfdecentralizedtrust.splice.validator.domain.DomainConnector
import org.lfdecentralizedtrust.splice.validator.migration.DecentralizedSynchronizerMigrationTrigger
import org.lfdecentralizedtrust.splice.validator.store.{AppManagerStore, ValidatorStore}
import org.lfdecentralizedtrust.splice.wallet.UserWalletManager
import org.lfdecentralizedtrust.splice.wallet.automation.{
  OffboardUserPartyTrigger,
  ValidatorRightTrigger,
  WalletAppInstallTrigger,
}
import org.lfdecentralizedtrust.splice.wallet.config.TransferPreapprovalConfig
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    backupDumpConfig: Option[PeriodicBackupDumpConfig],
    validatorTopupConfig: ValidatorTopupConfig,
    grpcDeadline: Option[NonNegativeFiniteDuration],
    appManagerConfig: Option[AppManagerConfig],
    transferPreapprovalConfig: TransferPreapprovalConfig,
    sequencerConnectionFromScan: Boolean,
    prevetDuration: NonNegativeFiniteDuration,
    isSvValidator: Boolean,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    walletManagerOpt: Option[UserWalletManager], // None when config.enableWallet=false
    store: ValidatorStore,
    scanConnection: BftScanConnection,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    participantIdentitiesStore: NodeIdentitiesStore,
    domainConnector: DomainConnector,
    domainMigrationDumpPath: Option[Path],
    domainMigrationId: Long,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    svValidator: Boolean,
    sequencerSubmissionAmplificationPatience: NonNegativeFiniteDuration,
    contactPoint: String,
    supportsSoftDomainMigrationPoc: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
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
        ValidatorAutomationService.bootstrapPackageIdResolver,
      ),
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
    ) {
  override def companion = ValidatorAutomationService

  val appManagerStore =
    new AppManagerStore(
      scanConnection.getAmuletRulesDomain,
      this,
      retryProvider,
      loggerFactory,
    )

  walletManagerOpt.foreach { walletManager =>
    registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager, connection))
    registerTrigger(
      new ValidatorRightTrigger(
        triggerContext,
        walletManager.externalPartyWalletManager,
        connection,
        participantAdminConnection,
      )
    )

    registerTrigger(new OffboardUserPartyTrigger(triggerContext, walletManager, connection))

    registerTrigger(
      new AcceptTransferPreapprovalProposalTrigger(
        triggerContext,
        store,
        walletManager,
        transferPreapprovalConfig,
        clock,
      )
    )

    registerTrigger(
      new RenewTransferPreapprovalTrigger(
        triggerContext,
        store,
        walletManager,
        transferPreapprovalConfig,
      )
    )

    if (automationConfig.enableAutomaticRewardsCollectionAndAmuletMerging) {
      registerTrigger(
        new ReceiveFaucetCouponTrigger(
          triggerContext,
          scanConnection,
          store,
          walletManager,
          validatorTopupConfig,
          connection,
          clock,
        )
      )
    }

    if (isSvValidator)
      logger.info(
        s"Not starting TopupMemberTrafficTrigger, as this is an SV validator."
      )(TraceContext.empty)
    else if (validatorTopupConfig.targetThroughput.value <= 0L)
      logger.info(
        s"Not starting TopupMemberTrafficTrigger, as the validator is not configured to buy extra traffic."
      )(TraceContext.empty)
    else
      registerTrigger(
        new TopupMemberTrafficTrigger(
          triggerContext
            .focus(_.config.pollingInterval)
            .replace(triggerContext.config.topupTriggerPollingInterval_),
          store,
          connection,
          participantAdminConnection,
          validatorTopupConfig,
          grpcDeadline,
          clock,
          walletManager,
          scanConnection,
          domainMigrationId,
        )
      )
  }

  backupDumpConfig.foreach(config =>
    registerTrigger(
      new PeriodicParticipantIdentitiesBackupTrigger(
        config,
        triggerContext,
        participantIdentitiesStore,
      )
    )
  )

  appManagerConfig.foreach(config =>
    registerTrigger(
      new PollInstalledApplicationsTrigger(
        config,
        triggerContext,
        appManagerStore,
      )
    )
  )

  if (!supportsSoftDomainMigrationPoc) {
    registerTrigger(
      new TransferFollowTrigger(
        triggerContext,
        store,
        connection,
        store.key.validatorParty,
        implicit tc =>
          scanConnection.getAmuletRulesWithState().flatMap { amuletRulesCWS =>
            amuletRulesCWS.toAssignedContract
              .map { amuletRules =>
                store
                  .listAmuletRulesTransferFollowers(amuletRules)
                  .map(_ map (FollowTask(amuletRules, _)))
              }
              .getOrElse(Future successful Seq.empty)
          },
      )
    )
  }
  registerTrigger(new AssignTrigger(triggerContext, store, connection, store.key.validatorParty))
  if (sequencerConnectionFromScan)
    registerTrigger(
      new ReconcileSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        scanConnection,
        domainConnector,
        sequencerSubmissionAmplificationPatience,
        supportsSoftDomainMigrationPoc,
      )
    )

  registerTrigger(
    new ValidatorPackageVettingTrigger(
      participantAdminConnection,
      scanConnection,
      prevetDuration,
      triggerContext,
    )
  )

  registerTrigger(
    new ValidatorLicenseMetadataTrigger(
      triggerContext,
      connection,
      store,
      scanConnection,
      contactPoint,
    )
  )

  registerTrigger(
    new ValidatorLicenseActivityTrigger(
      triggerContext,
      connection,
      store,
      scanConnection,
    )
  )

  if (!svValidator) {
    domainMigrationDumpPath.fold(
      logger.info(
        "Not starting SynchronizerUpgradeTrigger, as no domain migration dump path is configured."
      )(TraceContext.empty)
    ) { path =>
      registerTrigger(
        new DecentralizedSynchronizerMigrationTrigger(
          domainMigrationId,
          triggerContext,
          participantAdminConnection,
          path,
          scanConnection,
        )
      )
    }
  }
}

object ValidatorAutomationService extends AutomationServiceCompanion {
  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] =
    template.moduleName match {
      // App manager storage is participant local so we can freely choose the package id.
      case "Splice.AppManager.Store" => Some(DarResources.appManager.bootstrap.packageId)
      // ImportCrates are created before AmuletRules. Given that this is only a hack until we have upgrading
      // we can hardcode this.
      case "Splice.AmuletImport" => Some(DarResources.amulet.bootstrap.packageId)
      case _ => None
    }

  override protected[this] def expectedTriggerClasses = Seq.empty
}
