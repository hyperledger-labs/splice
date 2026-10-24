// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import com.digitalasset.canton.lifecycle.{FlagCloseable, LifeCycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.wallet.automation.ExternalPartyWalletAutomationService
import org.lfdecentralizedtrust.splice.wallet.config.RewardSharingConfig
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

/** A service managing the treasury, automation, and store for an external party's wallet. */
class ExternalPartyWalletService(
    ledgerClient: SpliceLedgerClient,
    val store: ExternalPartyWalletStore,
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    override protected[this] val retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    params: SpliceParametersConfig,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
    rewardSharingConfig: RewardSharingConfig,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends RetryProvider.Has
    with FlagCloseable
    with NamedLogging {

  val automation =
    try {
      new ExternalPartyWalletAutomationService(
        store,
        ledgerClient,
        automationConfig,
        clock,
        domainTimeSync,
        retryProvider,
        params,
        scanConnection,
        loggerFactory,
        packageVersionSupport,
        rewardSharingConfig,
      )
    } catch {
      // a failed construction never reaches onClosed, so close the store here
      case NonFatal(e) =>
        store.close()
        throw e
    }

  override def onClosed(): Unit = {
    // LifeCycle.close closes both in order even if the first close fails.
    LifeCycle.close(automation, store)(logger)
    super.onClosed()
  }
}
