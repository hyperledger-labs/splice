// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.wallet.metrics.AmuletMetrics
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport

import scala.concurrent.{ExecutionContext, Future}

class AmuletMetricsTrigger(
    override protected val context: TriggerContext,
    userWalletStore: UserWalletStore,
    scanConnection: ScanConnection,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingTrigger {

  override protected def extraMetricLabels = Seq(
    "party" -> userWalletStore.key.endUserParty.toString
  )

  val amuletMetrics = new AmuletMetrics(userWalletStore.key.endUserParty, context.metricsFactory)

  override def performWorkIfAvailable()(implicit
      tc: TraceContext
  ): Future[Boolean] =
    for {
      noHoldingFeesOnTransfers <- packageVersionSupport.noHoldingFeesOnTransfers(
        userWalletStore.key.dsoParty,
        context.clock.now,
      )
      deductHoldingFees = !noHoldingFeesOnTransfers.supported
      round <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
      (unlockedBalance, _) <- userWalletStore.getAmuletBalanceWithHoldingFees(
        round,
        deductHoldingFees = deductHoldingFees,
      )
      lockedBalance <- userWalletStore.getLockedAmuletBalance(
        round,
        deductHoldingFees = deductHoldingFees,
      )
      _ = amuletMetrics.unlockedAmuletGauge.updateValue(unlockedBalance.doubleValue)
      _ = amuletMetrics.lockedAmuletGauge.updateValue(lockedBalance.doubleValue)
    } yield false

  override def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable(
          "wallet metrics",
          amuletMetrics.close(),
        )
      )
}
