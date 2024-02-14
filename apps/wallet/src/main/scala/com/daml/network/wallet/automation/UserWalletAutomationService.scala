package com.daml.network.wallet.automation

import com.daml.network.automation.TransferFollowTrigger.Task as FollowTask
import com.daml.network.automation.UnassignTrigger.GetTargetDomain
import com.daml.network.automation.{
  AssignTrigger,
  CNNodeAppAutomationService,
  AutomationServiceCompanion,
  TransferFollowTrigger,
  UnassignTrigger,
}
import AutomationServiceCompanion.{TriggerClass, aTrigger}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.*
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.QualifiedName
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    globalDomain: GetTargetDomain,
    automationConfig: AutomationConfig,
    clock: Clock,
    scanConnection: BftScanConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
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
        UserWalletAutomationService.bootstrapPackageIdResolver,
      ),
      ledgerClient,
      retryProvider,
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
  if (automationConfig.enableAutomaticRewardsCollectionAndCoinMerging) {
    registerTrigger(
      new CollectRewardsAndMergeCoinsTrigger(triggerContext, treasury)
    )
  }

  registerTrigger(
    new UnassignTrigger.Template(
      triggerContext,
      store,
      connection,
      globalDomain,
      store.key.endUserParty,
      paymentCodegen.AppPaymentRequest.COMPANION,
    )
  )

  registerTrigger(new AssignTrigger(triggerContext, store, connection, store.key.endUserParty))

  registerTrigger(
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection,
      store.key.endUserParty,
      implicit tc =>
        scanConnection.getCoinRulesWithState() flatMap { coinRules =>
          coinRules.toAssignedContract map { coinRules =>
            store
              .listLaggingCoinRulesFollowers(coinRules.domain, participantAdminConnection)
              .map(_ map (FollowTask(coinRules, _)))
          } getOrElse Future.successful(Seq.empty)
        },
    )
  )
}

object UserWalletAutomationService extends AutomationServiceCompanion {
  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] =
    // ImportCrates are created before CoinRules. Given that this is only a hack until we have upgrading
    // we can hardcode this.
    Option.when(template.moduleName == "CC.CoinImport")(
      DarResources.cantonCoin.bootstrap.packageId
    )

  // defined because instances are created by UserWalletService, not immediately
  // available in the app state
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    CNNodeAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[ExpireTransferOfferTrigger],
      aTrigger[ExpireAcceptedTransferOfferTrigger],
      aTrigger[ExpireBuyTrafficRequestsTrigger],
      aTrigger[ExpireAppPaymentRequestsTrigger],
      aTrigger[SubscriptionReadyForPaymentTrigger],
      aTrigger[AcceptedTransferOfferTrigger],
      aTrigger[CompleteBuyTrafficRequestTrigger],
      aTrigger[CollectRewardsAndMergeCoinsTrigger],
      aTrigger[UnassignTrigger.Template[?, ?]],
      aTrigger[AssignTrigger],
      aTrigger[TransferFollowTrigger],
    )
}
