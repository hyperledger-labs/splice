package com.daml.network.splitwell.automation

import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.store.DomainStore
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.ContractId
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
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwell app. */
class SplitwellAutomationService(
    automationConfig: AutomationConfig,
    domainConfig: SplitwellDomainConfig,
    clock: Clock,
    store: SplitwellStore,
    ledgerClient: CoinLedgerClient,
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

  // TODO(#3285) Consider not even starting this on non-splitwell
  // domains.
  def createAcceptedAppPaymentRequestTrigger(
      domainAdded: DomainStore.DomainAdded,
      triggerContext: TriggerContext,
  ) =
    new AcceptedAppPaymentRequestsTrigger(
      triggerContext,
      store,
      connection,
      domainAdded.domainAlias,
      scanConnection,
    )

  def createSplitwellInstallRequestTrigger(
      domainAdded: DomainStore.DomainAdded,
      triggerContext: TriggerContext,
  ) =
    new SplitwellInstallRequestTrigger(
      triggerContext,
      store,
      connection,
      domainAdded.domainAlias,
    )

  def createGroupRequestTrigger(
      domainAdded: DomainStore.DomainAdded,
      triggerContext: TriggerContext,
  ) =
    new GroupRequestTrigger(triggerContext, store, connection, domainAdded.domainAlias)

  def createTransferOutTrigger[TCid <: ContractId[T], T <: Template](
      companion: TemplateCompanion[TCid, T]
  )(domainAdded: DomainStore.DomainAdded, triggerContext: TriggerContext): Trigger =
    new TransferOutTrigger.Template(
      triggerContext,
      store,
      connection,
      domainConfig.splitwell.preferred,
      domainAdded.domainId,
      store.providerParty,
      companion,
    )

  registerTrigger(
    new TransferInTrigger(
      triggerContext,
      store,
      connection,
      store.providerParty,
    )
  )

  registerTrigger(
    DomainOrchestrator(
      triggerContext,
      store.domains,
      DomainOrchestrator.multipleServices(
        Seq(
          createTransferOutTrigger(splitwellCodegen.BalanceUpdate.COMPANION),
          createAcceptedAppPaymentRequestTrigger,
          createSplitwellInstallRequestTrigger,
          createGroupRequestTrigger,
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
