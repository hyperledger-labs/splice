package com.daml.network.splitwise.automation

import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.store.DomainStore
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractId}
import com.daml.ledger.javaapi.data.codegen.ContractCompanion
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
import com.daml.network.splitwise.config.SplitwiseDomainConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.store.SplitwiseStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwise app. */
class SplitwiseAutomationService(
    automationConfig: AutomationConfig,
    domainConfig: SplitwiseDomainConfig,
    clock: Clock,
    store: SplitwiseStore,
    ledgerClient: CoinLedgerClient,
    readAs: Set[PartyId],
    scanConnection: ScanConnection,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CoinAppAutomationService(
      automationConfig,
      clock,
      Map(store.providerParty -> store),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  registerTrigger(
    new AcceptedAppPaymentRequestsTrigger(
      triggerContext,
      store,
      connection,
      domainConfig.global,
      domainConfig.splitwise,
      scanConnection,
      readAs,
    )
  )
  registerTrigger(
    new SplitwiseInstallRequestTrigger(triggerContext, store, connection, domainConfig.splitwise)
  )
  registerTrigger(
    new GroupRequestTrigger(triggerContext, store, connection, domainConfig.splitwise)
  )

  // TODO(#2472) Share Domain Orchestrator for multiple apps
  def createTransferOutTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(domainAdded: DomainStore.DomainAdded): Trigger =
    new TransferOutTrigger(
      triggerContext,
      store.domains,
      connection,
      domainConfig.splitwise,
      domainAdded.domainId,
      store.providerParty,
      companion,
    )
  def createTransferInTrigger(domainAdded: DomainStore.DomainAdded): Trigger =
    new TransferInTrigger(
      triggerContext,
      store.domains,
      connection,
      domainConfig.global,
      domainAdded.domainId,
      store.providerParty,
    )

  Seq(
    createTransferOutTrigger(splitwiseCodegen.BalanceUpdate.COMPANION),
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
}
