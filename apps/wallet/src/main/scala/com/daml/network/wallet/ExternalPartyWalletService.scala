// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet

import com.daml.network.config.AutomationConfig
import com.daml.network.environment.*
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.util.{HasHealth, TemplateJsonDecoder}
import com.daml.network.wallet.automation.ExternalPartyWalletAutomationService
import com.daml.network.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an external party's wallet. */
class ExternalPartyWalletService(
    ledgerClient: SpliceLedgerClient,
    key: ExternalPartyWalletStore.Key,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    storage: Storage,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    scanConnection: BftScanConnection,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    close: CloseContext,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging
    with HasHealth {

  val store: ExternalPartyWalletStore =
    ExternalPartyWalletStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
    )

  val automation = new ExternalPartyWalletAutomationService(
    store,
    ledgerClient,
    automationConfig,
    clock,
    domainTimeSync,
    domainUnpausedSync,
    scanConnection,
    retryProvider,
    ingestFromParticipantBegin,
    ingestUpdateHistoryFromParticipantBegin,
    loggerFactory,
  )

  val connection: SpliceLedgerConnection = automation.connection

  override def isHealthy: Boolean =
    automation.isHealthy

  override def onClosed(): Unit = {
    automation.close()
    store.close()
    super.onClosed()
  }
}
