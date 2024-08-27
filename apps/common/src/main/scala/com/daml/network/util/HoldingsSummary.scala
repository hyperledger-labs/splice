// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.network.codegen.java.splice.amulet.{Amulet, LockedAmulet}

// TODO(#14156) Remove the duplication with AcsSnapshotStore once we rebase the
// cc custody branch on main
case class HoldingsSummary(
    totalUnlockedCoin: BigDecimal,
    totalLockedCoin: BigDecimal,
    totalCoinHoldings: BigDecimal,
    accumulatedHoldingFeesUnlocked: BigDecimal,
    accumulatedHoldingFeesLocked: BigDecimal,
    accumulatedHoldingFeesTotal: BigDecimal,
    totalAvailableCoin: BigDecimal,
) {
  def addAmulet(amulet: Amulet, asOfRound: Long): HoldingsSummary = {
    val holdingFee = SpliceUtil.holdingFee(amulet, asOfRound)
    HoldingsSummary(
      totalUnlockedCoin = totalUnlockedCoin + amulet.amount.initialAmount,
      totalCoinHoldings = totalCoinHoldings + amulet.amount.initialAmount,
      accumulatedHoldingFeesUnlocked = accumulatedHoldingFeesUnlocked + holdingFee,
      accumulatedHoldingFeesTotal = accumulatedHoldingFeesTotal + holdingFee,
      totalAvailableCoin =
        (totalUnlockedCoin + amulet.amount.initialAmount) - (accumulatedHoldingFeesUnlocked + holdingFee),
      // unchanged
      totalLockedCoin = totalLockedCoin,
      accumulatedHoldingFeesLocked = accumulatedHoldingFeesLocked,
    )
  }
  def addLockedAmulet(amulet: LockedAmulet, asOfRound: Long): HoldingsSummary = {
    val holdingFee = SpliceUtil.holdingFee(amulet.amulet, asOfRound)
    HoldingsSummary(
      totalLockedCoin = totalLockedCoin + amulet.amulet.amount.initialAmount,
      totalCoinHoldings = totalCoinHoldings + amulet.amulet.amount.initialAmount,
      accumulatedHoldingFeesLocked = accumulatedHoldingFeesLocked + holdingFee,
      accumulatedHoldingFeesTotal = accumulatedHoldingFeesTotal + holdingFee,
      // unchanged
      totalUnlockedCoin = totalUnlockedCoin,
      accumulatedHoldingFeesUnlocked = accumulatedHoldingFeesUnlocked,
      totalAvailableCoin = totalAvailableCoin,
    )
  }

}

object HoldingsSummary {
  def Empty = HoldingsSummary(0, 0, 0, 0, 0, 0, 0)
}
