// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, SpliceUtil}
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

object TopupUtil {
  def minWalletBalanceForTopup(
      scanConnection: ScanConnection,
      validatorTopupConfig: ValidatorTopupConfig,
      clock: Clock,
  )(implicit tc: TraceContext, ec: ExecutionContext, mat: Materializer): Future[BigDecimal] = for {
    amuletRules <- scanConnection.getAmuletRulesWithState()
    synchronizerFeesConfig = AmuletConfigSchedule(amuletRules)
      .getConfigAsOf(clock.now)
      .decentralizedSynchronizer
      .fees
    topupParameters = ExtraTrafficTopupParameters(
      validatorTopupConfig.targetThroughput,
      validatorTopupConfig.minTopupInterval,
      synchronizerFeesConfig.minTopupAmount,
      validatorTopupConfig.topupTriggerPollingInterval,
    )
    latestRound <- scanConnection.getLatestOpenMiningRound()
    amuletPrice = latestRound.payload.amuletPrice
    extraTrafficPrice = BigDecimal(synchronizerFeesConfig.extraTrafficPrice)
  } yield SpliceUtil
    .synchronizerFees(topupParameters.topupAmount, extraTrafficPrice, amuletPrice)
    ._2

  private def currentWalletBalance(scanConnection: ScanConnection, store: UserWalletStore)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[BigDecimal] = for {
    latestRound <- scanConnection.getLatestOpenMiningRound()
    roundNum = latestRound.payload.round.number
    walletBalance <- store
      .getAmuletBalanceWithHoldingFees(
        roundNum
      )
      .map(_._1)
  } yield walletBalance

  def hasSufficientFundsForTopup(
      scanConnection: ScanConnection,
      validatorWalletStore: UserWalletStore,
      validatorTopupConfig: ValidatorTopupConfig,
      clock: Clock,
  )(implicit tc: TraceContext, ec: ExecutionContext, mat: Materializer) = {
    scanConnection.getAmuletRulesWithState().flatMap { amuletRules =>
      // Since we auto-tap CC for traffic purchases on DevNet, we always have sufficient funds
      // TODO(#851): Considering removing this once we remove auto-tapping in DevNet
      if (amuletRules.payload.isDevNet) Future.successful(true)
      else
        for {
          walletBalance <- currentWalletBalance(scanConnection, validatorWalletStore)
          minBalanceForTopup <- minWalletBalanceForTopup(
            scanConnection,
            validatorTopupConfig,
            clock,
          )
        } yield walletBalance >= minBalanceForTopup
    }
  }

}
