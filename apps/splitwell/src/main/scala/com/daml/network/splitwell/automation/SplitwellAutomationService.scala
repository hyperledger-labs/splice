package com.daml.network.splitwell.automation

import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import akka.stream.Materializer
import com.daml.network.automation.{
  CNNodeAppAutomationService,
  TransferFollowTrigger,
  TransferInTrigger,
}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
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
    clock: Clock,
    store: SplitwellStore,
    ledgerClient: CNLedgerClient,
    scanConnection: ScanConnection,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
    processingTimeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      automationConfig,
      clock,
      Map(store.providerParty -> store),
      ledgerClient,
      retryProvider,
    ) {

  override protected def timeouts: ProcessingTimeout = processingTimeouts

  registerTrigger(
    new AcceptedAppPaymentRequestsTrigger(
      triggerContext,
      store,
      connection,
      scanConnection,
    )
  )

  registerTrigger(
    new SplitwellInstallRequestTrigger(
      triggerContext,
      store,
      connection,
    )
  )

  registerTrigger(
    new UpgradeGroupTrigger(triggerContext, store, connection)
  )

  registerTrigger(
    new GroupRequestTrigger(triggerContext, store, connection)
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
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection,
      store.providerParty,
      splitwellCodegen.Group.COMPANION,
      splitwellCodegen.BalanceUpdate.COMPANION,
      () => store.listLaggingBalanceUpdates(),
    )
  )

  registerTrigger(
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection,
      store.providerParty,
      splitwellCodegen.Group.COMPANION,
      splitwellCodegen.GroupInvite.COMPANION,
      () => store.listLaggingGroupInvites(),
    )
  )

  registerTrigger(
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection,
      store.providerParty,
      splitwellCodegen.Group.COMPANION,
      splitwellCodegen.AcceptedGroupInvite.COMPANION,
      () => store.listLaggingAcceptedGroupInvites(),
    )
  )
}
