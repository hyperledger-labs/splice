package com.daml.network.splitwell.automation

import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
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
  TriggerContext,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.store.SplitwellStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwell app. */
class SplitwellAutomationService(
    automationConfig: AutomationConfig,
    domainConfig: SplitwellDomainConfig,
    clock: Clock,
    store: SplitwellStore,
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
      domainConfig.splitwell,
      scanConnection,
      readAs,
    )
  )
  registerTrigger(
    new SplitwellInstallRequestTrigger(triggerContext, store, connection, domainConfig.splitwell)
  )
  registerTrigger(
    new GroupRequestTrigger(triggerContext, store, connection, domainConfig.splitwell)
  )

  def createTransferOutTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(domainAdded: DomainStore.DomainAdded, triggerContext: TriggerContext): Trigger =
    new TransferOutTrigger.Template(
      triggerContext,
      store,
      connection,
      domainConfig.splitwell,
      domainAdded.domainId,
      store.providerParty,
      companion,
    )
  def createTransferInTrigger(
      domainAdded: DomainStore.DomainAdded,
      triggerContext: TriggerContext,
  ): Trigger =
    new TransferInTrigger(
      triggerContext,
      store.domains,
      connection,
      domainConfig.global,
      domainAdded.domainId,
      store.providerParty,
    )

  registerTrigger(
    DomainOrchestrator(
      triggerContext,
      store.domains,
      DomainOrchestrator.multipleServices(
        Seq(
          createTransferOutTrigger(splitwellCodegen.BalanceUpdate.COMPANION),
          createTransferInTrigger,
        ).map { createTrigger =>
          { case (domainAdded, perDomainLoggerFactory) =>
            val trigger = createTrigger(
              domainAdded,
              triggerContext.copy(loggerFactory = perDomainLoggerFactory),
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
