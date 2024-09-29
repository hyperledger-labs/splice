// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.wallet.metrics.AmuletMetrics
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class AmuletMetricsTrigger(
    override protected val context: TriggerContext,
    userWalletStore: UserWalletStore,
    scanConnection: ScanConnection,
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
      round <- scanConnection.getLatestOpenMiningRound().map(_.payload.round.number)
      (unlockedBalance, _) <- userWalletStore.getAmuletBalanceWithHoldingFees(round)
      lockedBalance <- userWalletStore.getLockedAmuletBalance(round)
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
