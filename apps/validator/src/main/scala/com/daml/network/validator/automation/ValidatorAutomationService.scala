package com.daml.network.validator.automation

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  AssignTrigger,
  CNNodeAppAutomationService,
  TransferFollowTrigger,
}
import TransferFollowTrigger.Task as FollowTask
import com.daml.network.config.{AutomationConfig, BackupDumpConfig}
import com.daml.network.environment.{
  CNLedgerClient,
  DarResources,
  PackageIdResolver,
  ParticipantAdminConnection,
  RetryProvider,
}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.QualifiedName
import com.daml.network.validator.config.{AppManagerConfig, BuyExtraTrafficConfig}
import com.daml.network.validator.store.{AppManagerStore, NodeIdentitiesStore, ValidatorStore}
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.automation.{OffboardUsersTrigger, WalletAppInstallTrigger}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

class ValidatorAutomationService(
    automationConfig: AutomationConfig,
    backupDumpConfig: Option[BackupDumpConfig],
    buyExtraTrafficConfig: BuyExtraTrafficConfig,
    appManagerConfig: Option[AppManagerConfig],
    sequencerConnectionFromScan: Boolean,
    prevetDuration: NonNegativeFiniteDuration,
    globalDomainAlias: DomainAlias,
    clock: Clock,
    walletManager: UserWalletManager,
    store: ValidatorStore,
    scanConnection: ScanConnection,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    participantIdentitiesStore: NodeIdentitiesStore,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      store,
      PackageIdResolver.inferFromCoinRules(
        clock,
        scanConnection,
        loggerFactory,
        ValidatorAutomationService.extraPackageIdResolver,
      ),
      ledgerClient,
      retryProvider,
    ) {

  val appManagerStore =
    new AppManagerStore(
      scanConnection.getCoinRulesDomain,
      this,
      retryProvider,
      loggerFactory,
    )

  registerTrigger(new WalletAppInstallTrigger(triggerContext, walletManager))
  registerTrigger(new OffboardUsersTrigger(triggerContext, walletManager, connection))
  registerTrigger(
    new TopupMemberTrafficTrigger(
      triggerContext,
      store,
      connection,
      participantAdminConnection,
      buyExtraTrafficConfig,
      clock,
      walletManager,
      scanConnection,
    )
  )
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

  registerTrigger(
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection,
      store.key.validatorParty,
      implicit tc =>
        scanConnection.getCoinRules().flatMap { coinRulesCWS =>
          coinRulesCWS.toAssignedContract
            .map { coinRules =>
              store
                .listCoinRulesTransferFollowers(coinRules, participantAdminConnection)
                .map(_ map (FollowTask(coinRules, _)))
            }
            .getOrElse(Future successful Seq.empty)
        },
    )
  )
  registerTrigger(new AssignTrigger(triggerContext, store, connection, store.key.validatorParty))
  if (sequencerConnectionFromScan)
    registerTrigger(
      new ReconcileSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        scanConnection,
        globalDomainAlias,
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
}

object ValidatorAutomationService {
  private[automation] def extraPackageIdResolver(template: QualifiedName): Option[String] =
    template.moduleName match {
      // App manager storage is participant local so we can freely choose the package id.
      case "CN.AppManager.Store" => Some(DarResources.appManager.bootstrap.packageId)
      // ImportCrates are created before CoinRules. Given that this is only a hack until we have upgrading
      // we can hardcode this.
      case "CC.CoinImport" => Some(DarResources.cantonCoin.bootstrap.packageId)
      case _ => None
    }
}
