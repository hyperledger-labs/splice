// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  HistoryMetrics,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.util.{HasHealth, SpliceCircuitBreaker, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.config.{
  AutoAcceptTransfersConfig,
  TreasuryConfig,
  WalletSweepConfig,
}
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.lifecycle.{CloseContext, FlagCloseable}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement

import scala.concurrent.ExecutionContext

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: SpliceLedgerClient,
    key: UserWalletStore.Key,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    treasuryConfig: TreasuryConfig,
    storage: DbStorage,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    walletSweep: Option[WalletSweepConfig],
    autoAcceptTransfers: Option[AutoAcceptTransfersConfig],
    dedupDuration: DedupDuration,
    txLogBackfillEnabled: Boolean,
    txLogBackfillingBatchSize: Int,
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

  private implicit val scheduler: Scheduler = mat.system.scheduler

  val store: UserWalletStore =
    UserWalletStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
      automationConfig.ingestion,
    )

  val updateHistory: UpdateHistory =
    new UpdateHistory(
      storage,
      domainMigrationInfo,
      store.storeName,
      participantId,
      store.acsContractFilter.ingestionFilter.primaryParty,
      BackfillingRequirement.BackfillingNotRequired,
      loggerFactory,
      enableissue12777Workaround = true,
      enableImportUpdateBackfill = false,
      HistoryMetrics(retryProvider.metricsFactory, domainMigrationInfo.currentMigrationId),
    )

  val treasury: TreasuryService = new TreasuryService(
    // The treasury gets its own connection, and is required to manage waiting for the store on its own.
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      SpliceCircuitBreaker(
        "treasury",
        params.circuitBreakers.mediumPriority,
        clock,
        loggerFactory,
      ),
    ),
    treasuryConfig,
    clock,
    store,
    walletManager,
    retryProvider,
    scanConnection,
    loggerFactory,
  )

  val automation = new UserWalletAutomationService(
    store,
    walletManager.store,
    updateHistory,
    treasury,
    ledgerClient,
    automationConfig,
    clock,
    domainTimeSync,
    domainUnpausedSync,
    scanConnection,
    retryProvider,
    packageVersionSupport,
    ingestFromParticipantBegin,
    ingestUpdateHistoryFromParticipantBegin,
    loggerFactory,
    validatorTopupConfigO,
    walletSweep,
    autoAcceptTransfers,
    dedupDuration,
    txLogBackfillEnabled = txLogBackfillEnabled,
    txLogBackfillingBatchSize = txLogBackfillingBatchSize,
    params,
  )

  /** The connection to use when submitting commands based on reads from the WalletStore.
    * The submission will wait for the store to ingest the effect of the command before completing the future.
    */
  val connection: SpliceLedgerConnection =
    automation.connection(SpliceLedgerConnectionPriority.Medium)

  override def isHealthy: Boolean =
    automation.isHealthy && treasury.isHealthy

  override def onClosed(): Unit = {
    // Close treasury early, that will result in it no longer accepting new requests
    // but in-flight requests can complete. If we close the automation first,
    // a task can get stuck forever waiting for store ingestion to complete.
    treasury.close()
    automation.close()
    store.close()
    super.onClosed()
  }
}
