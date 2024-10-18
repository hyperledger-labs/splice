// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.{AutomationServiceCompanion, SpliceAppAutomationService}
import AutomationServiceCompanion.TriggerClass
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.*
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

class ExternalPartyWalletAutomationService(
    store: ExternalPartyWalletStore,
    ledgerClient: SpliceLedgerClient,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    scanConnection: BftScanConnection,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      store,
      PackageIdResolver.inferFromAmuletRules(
        clock,
        scanConnection,
        loggerFactory,
      ),
      ledgerClient,
      retryProvider,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
    ) {
  override def companion = ExternalPartyWalletAutomationService
}

object ExternalPartyWalletAutomationService extends AutomationServiceCompanion {

  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] = Seq.empty
}
