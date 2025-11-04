// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.util.{HasHealth, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.automation.ExternalPartyWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
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
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    params: SpliceParametersConfig,
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
      automationConfig.ingestion,
    )

  val automation = new ExternalPartyWalletAutomationService(
    store,
    ledgerClient,
    automationConfig,
    clock,
    domainTimeSync,
    domainUnpausedSync,
    retryProvider,
    ingestFromParticipantBegin,
    ingestUpdateHistoryFromParticipantBegin,
    params,
    loggerFactory,
  )

  override def isHealthy: Boolean =
    automation.isHealthy

  override def onClosed(): Unit = {
    automation.close()
    store.close()
    super.onClosed()
  }
}
