package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  CNNodeAppAutomationService,
  TransferInTrigger,
  TransferOutTrigger,
}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.DomainAlias
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
      store,
      ledgerClient,
      retryProvider,
      enableOffsetIngestionService = false,
    ) {

  registerTrigger(new ExpireTransferOfferTrigger(triggerContext, store, connection))
  registerTrigger(
    new ExpireAcceptedTransferOfferTrigger(triggerContext, store, connection)
  )
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(triggerContext, store, connection)
  )
  registerTrigger(new SubscriptionReadyForPaymentTrigger(triggerContext, store, treasury))
  registerTrigger(
    new AcceptedTransferOfferTrigger(triggerContext, store, treasury, connection)
  )
  if (automationConfig.enableAutomaticRewardsCollectionAndCoinMerging) {
    registerTrigger(
      new CollectRewardsAndMergeCoinsTrigger(triggerContext, treasury)
    )
  }

  registerTrigger(
    new TransferOutTrigger.Template(
      triggerContext,
      store,
      connection,
      globalDomain,
      store.key.endUserParty,
      paymentCodegen.AppPaymentRequest.COMPANION,
    )
  )
  registerTrigger(
    new TransferOutTrigger.Interface(
      triggerContext,
      store,
      connection,
      globalDomain,
      store.key.endUserParty,
      paymentCodegen.DeliveryOffer.INTERFACE,
    )
  )

  registerTrigger(new TransferInTrigger(triggerContext, store, connection, store.key.endUserParty))
}
