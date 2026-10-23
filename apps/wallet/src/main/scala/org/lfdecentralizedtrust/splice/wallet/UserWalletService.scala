// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.environment.ledger.api.DedupDuration
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.util.{HasHealth, SpliceCircuitBreaker}
import org.lfdecentralizedtrust.splice.wallet.automation.UserWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.config.{
  AutoAcceptTransfersConfig,
  RewardSharingConfig,
  TreasuryConfig,
  WalletSweepConfig,
}
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import org.lfdecentralizedtrust.splice.wallet.util.ValidatorTopupConfig
import com.digitalasset.canton.lifecycle.{FlagCloseable, LifeCycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.Scheduler
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/** A service managing the treasury, automation, and store for an end-user's wallet. */
class UserWalletService(
    ledgerClient: SpliceLedgerClient,
    val store: UserWalletStore,
    walletManager: UserWalletManager,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    treasuryConfig: TreasuryConfig,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
    validatorTopupConfigO: Option[ValidatorTopupConfig],
    walletSweep: Option[WalletSweepConfig],
    autoAcceptTransfers: Option[AutoAcceptTransfersConfig],
    rewardSharingConfig: RewardSharingConfig,
    dedupDuration: DedupDuration,
    params: SpliceParametersConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging
    with HasHealth {

  private implicit val scheduler: Scheduler = mat.system.scheduler

  val treasury: TreasuryService =
    try {
      new TreasuryService(
        // The treasury gets its own connection, and is required to manage waiting for the store on its own.
        ledgerClient.connection(
          this.getClass.getSimpleName,
          loggerFactory,
          SpliceCircuitBreaker(
            "treasury",
            params.circuitBreakers.mediumPriority,
            clock,
            store.dsoPartyId,
            loggerFactory,
          ),
        ),
        treasuryConfig,
        clock,
        store,
        walletManager,
        retryProvider,
        scanConnection,
        mintUnassignedRewardCouponsV2 = rewardSharingConfig.mintUnassignedCoupons,
        loggerFactory,
      )
    } catch {
      // a failed construction never reaches onClosed, so close the store here
      case NonFatal(e) =>
        store.close()
        throw e
    }

  val automation: UserWalletAutomationService =
    try {
      new UserWalletAutomationService(
        store,
        treasury,
        ledgerClient,
        automationConfig,
        clock,
        domainTimeSync,
        scanConnection,
        retryProvider,
        packageVersionSupport,
        loggerFactory,
        validatorTopupConfigO,
        walletSweep,
        autoAcceptTransfers,
        rewardSharingConfig,
        dedupDuration,
        params,
      )
    } catch {
      case NonFatal(e) =>
        LifeCycle.close(treasury, store)(logger)
        throw e
    }

  /** The connection to use when submitting commands based on reads from the WalletStore.
    * The submission will wait for the store to ingest the effect of the command before completing the future.
    */
  val connection: SpliceLedgerConnection =
    automation.connection(SpliceLedgerConnectionPriority.Medium)

  override def isHealthy: Boolean = treasury.isHealthy

  override def onClosed(): Unit = {
    // Close treasury early, that will result in it no longer accepting new requests
    // but in-flight requests can complete. If we close the automation first,
    // a task can get stuck forever waiting for store ingestion to complete.
    // LifeCycle.close closes all of them in order even if one of them fails.
    LifeCycle.close(treasury, automation, store)(logger)
    super.onClosed()
  }
}
