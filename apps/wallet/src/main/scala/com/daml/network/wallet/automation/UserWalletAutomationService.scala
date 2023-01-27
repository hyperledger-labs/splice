package com.daml.network.wallet.automation

import com.digitalasset.canton.topology.DomainId
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractId}
import com.daml.ledger.javaapi.data.codegen.ContractCompanion
import com.daml.network.codegen.java.cn.scripts.testwallet as testWalletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.codegen.java.cn.wallet.{payment as paymentCodegen}
import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.{
  CoinAppAutomationService,
  DomainOrchestrator,
  Trigger,
  TransferInTrigger,
  TransferOutTrigger,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.store.DomainStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

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
  if (!automationConfig.disableAutomaticRewardsCollectionAndCoinMerging) {
    registerTrigger(
      new CollectRewardsAndMergeCoinsTrigger(triggerContext, store, treasury)
    )
  }
  registerTrigger(
    new ExpireAppPaymentRequestsTrigger(triggerContext, store, connection, globalDomain)
  )

  // TODO(#2472) Share Domain Orchestrator for multiple apps
  def createTransferOutTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(domainAdded: DomainStore.DomainAdded): Trigger =
    new TransferOutTrigger(
      triggerContext,
      store.domains,
      connection,
      globalDomain,
      domainAdded.domainId,
      store.key.endUserParty,
      companion,
    )
  def createTransferInTrigger(domainAdded: DomainStore.DomainAdded): Trigger =
    new TransferInTrigger(
      triggerContext,
      store.domains,
      connection,
      globalDomain,
      domainAdded.domainId,
      store.key.endUserParty,
    )

  Seq(
    createTransferOutTrigger(paymentCodegen.AppPaymentRequest.COMPANION),
    createTransferOutTrigger(testWalletCodegen.TestDeliveryOffer.COMPANION),
    createTransferOutTrigger(splitwiseCodegen.TransferInProgress.COMPANION),
    createTransferInTrigger,
  ).foreach { createTrigger =>
    registerTrigger(
      new DomainOrchestrator(
        triggerContext,
        store.domains,
        domainAdded => {
          val trigger = createTrigger(domainAdded)
          trigger.run()
          trigger
        },
      )
    )
  }

  override def getIngestionDomain: () => Future[DomainId] = () =>
    store.domains.signalWhenConnected(globalDomain)
}
