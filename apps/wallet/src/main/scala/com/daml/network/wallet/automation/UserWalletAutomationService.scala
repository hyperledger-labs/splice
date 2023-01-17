package com.daml.network.wallet.automation

import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CoinAppAutomationService
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

class UserWalletAutomationService(
    store: UserWalletStore,
    treasury: TreasuryService,
    ledgerClient: CoinLedgerClient,
    globalDomain: DomainAlias,
    participantAdminConnection: ParticipantAdminConnection,
    automationConfig: AutomationConfig,
    clock: Clock,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends CoinAppAutomationService(
      automationConfig,
      clock,
      store,
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
  registerTrigger(
    new CollectRewardsAndMergeCoinsTrigger(triggerContext, store, treasury)
  )
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(triggerContext, store, connection, globalDomain)
  )
}
