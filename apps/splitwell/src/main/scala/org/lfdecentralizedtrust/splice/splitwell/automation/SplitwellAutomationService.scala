// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.automation

import org.apache.pekko.stream.Materializer
import cats.syntax.apply.*
import org.lfdecentralizedtrust.splice.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  SqlIndexInitializationTrigger,
  TransferFollowTrigger,
  UnassignTrigger,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.ExecutionContextExecutor

/** Manages background automation that runs on an splitwell app. */
class SplitwellAutomationService(
    automationConfig: AutomationConfig,
    clock: Clock,
    store: SplitwellStore,
    storage: DbStorage,
    ledgerClient: SpliceLedgerClient,
    scanConnection: ScanConnection,
    retryProvider: RetryProvider,
    params: SpliceParametersConfig,
    protected val loggerFactory: NamedLoggerFactory,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      // splitwell does not have an admin connection to query the domain time and params,
      // and we care less about it behaving weirdly.
      DomainTimeSynchronization.Noop,
      store,
      ledgerClient,
      retryProvider,
      params,
      packageVersionSupport,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.splitwell.automation.SplitwellAutomationService.type =
    SplitwellAutomationService

  registerTrigger(
    new AcceptedAppPaymentRequestsTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
      scanConnection,
    )
  )

  registerTrigger(
    new SplitwellInstallRequestTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )

  registerTrigger(
    new UpgradeGroupTrigger(triggerContext, store, connection(SpliceLedgerConnectionPriority.Low))
  )

  registerTrigger(
    new GroupRequestTrigger(triggerContext, store, connection(SpliceLedgerConnectionPriority.Low))
  )

  registerTrigger(
    new TerminatedAppPaymentTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )

  registerTrigger(
    new UnassignTrigger.Template(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
      scanConnection.getAmuletRulesDomain,
      store.key.providerParty,
      splitwellCodegen.TransferInProgress.COMPANION,
    )
  )

  registerTrigger(
    new AssignTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
      store.key.providerParty,
    )
  )

  registerTrigger(
    new TransferFollowTrigger(
      triggerContext,
      store,
      connection(SpliceLedgerConnectionPriority.Low),
      store.key.providerParty,
      implicit tc =>
        (
          store.listLaggingBalanceUpdates(),
          store.listLaggingGroupInvites(),
          store.listLaggingAcceptedGroupInvites(),
        ).mapN(_ ++ _ ++ _),
    )
  )

  registerTrigger(
    SqlIndexInitializationTrigger(
      storage,
      triggerContext,
    )
  )
}

object SplitwellAutomationService extends AutomationServiceCompanion {

  override protected[this] def expectedTriggerClasses: Seq[Nothing] =
    Seq.empty

}
