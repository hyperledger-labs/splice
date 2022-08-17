package com.daml.network.history

import com.daml.ledger.client.binding.{Contract, Primitive => P}
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.{
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
}

/** Parent of a a CC create or archive within the corresponding transaction tree. */
sealed trait OriginEvent
case class Transfer(transfer: CoinRules_Transfer) extends OriginEvent
case class Tap(tap: CoinRules_Tap) extends OriginEvent
case class StartIssuing(startIssuing: CoinRules_MiningRound_StartIssuing) extends OriginEvent
case class UnknownExerciseParent() extends OriginEvent
case class BareCreateOrArchival() extends OriginEvent

sealed trait CCEventTypeAndCoin {
  def isArchive: Boolean
  def isCreate: Boolean = !isArchive
}
case class CCCreate(coin: Contract[Coin]) extends CCEventTypeAndCoin {
  override def isArchive: Boolean = false
}
case class CCArchive(coinCid: P.ContractId[Coin]) extends CCEventTypeAndCoin {
  override def isArchive: Boolean = true
}

case class CCEvent(
    coin: CCEventTypeAndCoin,
    context: OriginEvent,
)
