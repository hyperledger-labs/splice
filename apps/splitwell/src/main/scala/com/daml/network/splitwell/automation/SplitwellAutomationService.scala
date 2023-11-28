package com.daml.network.splitwell.automation

import org.apache.pekko.stream.Materializer
import cats.syntax.apply.*
import com.daml.network.automation.{
  AssignTrigger,
  CNNodeAppAutomationService,
  TransferFollowTrigger,
  UnassignTrigger,
}
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CNLedgerClient, DarResources, PackageIdResolver, RetryProvider}
import com.daml.network.util.QualifiedName
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwell.store.SplitwellStore
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
)(implicit
    ec: ExecutionContextExecutor,
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
        SplitwellAutomationService.extraPackageIdResolver,
      ),
      ledgerClient,
      retryProvider,
    ) {

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
    new TerminatedAppPaymentTrigger(triggerContext, store, connection)
  )

  registerTrigger(
    new UnassignTrigger.Template(
      triggerContext,
      store,
      connection,
      scanConnection.getCoinRulesDomain,
      store.providerParty,
      splitwellCodegen.TransferInProgress.COMPANION,
    )
  )

  registerTrigger(
    new AssignTrigger(
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
      implicit tc =>
        (
          store.listLaggingBalanceUpdates(),
          store.listLaggingGroupInvites(),
          store.listLaggingAcceptedGroupInvites(),
        ).mapN(_ ++ _ ++ _),
    )
  )
}

object SplitwellAutomationService {
  private[automation] def extraPackageIdResolver(template: QualifiedName): Option[String] =
    Option.when(template.moduleName == "CN.Splitwell")(
      DarResources.splitwell.bootstrap.packageId
    )
}
