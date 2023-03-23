package com.daml.network.wallet.automation

import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.{
  CNNodeAppAutomationService,
  DomainOrchestrator,
  TransferInTrigger,
  TransferOutTrigger,
  TriggerContext,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.store.DomainStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: CNLedgerClient,
    globalDomain: DomainAlias,
    participantAdminConnection: ParticipantAdminConnection,
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(store.key.endUserParty -> store),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  registerTrigger(new ExpireTransferOfferTrigger(triggerContext, store, connection, globalDomain))
  registerTrigger(
    new ExpireAcceptedTransferOfferTrigger(triggerContext, store, connection, globalDomain)
  )
  registerTrigger(new SubscriptionReadyForPaymentTrigger(triggerContext, store, treasury))
  registerTrigger(
    new AcceptedTransferOfferTrigger(triggerContext, store, treasury, connection, globalDomain)
  )
  if (automationConfig.enableAutomaticRewardsCollectionAndCoinMerging) {
    registerTrigger(
      new CollectRewardsAndMergeCoinsTrigger(triggerContext, treasury)
    )
  }
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(triggerContext, store, connection, globalDomain)
  )

  registerTrigger(new TransferInTrigger(triggerContext, store, connection, store.key.endUserParty))

  registerTrigger(
    DomainOrchestrator(
      triggerContext,
      store.domains,
      DomainOrchestrator.multipleServices(
        Seq(
          (domainAdded: DomainStore.DomainAdded, triggerContext: TriggerContext) =>
            new TransferOutTrigger.Template(
              triggerContext,
              store,
              connection,
              globalDomain,
              domainAdded.domainId,
              store.key.endUserParty,
              paymentCodegen.AppPaymentRequest.COMPANION,
            ),
          (domainAdded: DomainStore.DomainAdded, triggerContext: TriggerContext) =>
            new TransferOutTrigger.Interface(
              triggerContext,
              store,
              connection,
              globalDomain,
              domainAdded.domainId,
              store.key.endUserParty,
              paymentCodegen.DeliveryOffer.INTERFACE,
            ),
        ).map { createTrigger =>
          { case (domainAdded, triggerContext) =>
            val trigger = createTrigger(
              domainAdded,
              triggerContext,
            )
            trigger.run()
            trigger
          }
        },
        triggerContext.loggerFactory,
      ),
    )
  )
}
