package com.daml.network.history

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.{Coin, CoinRules, CoinRules_DevNet_Tap, CoinRules_Mint}
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, OpenMiningRound}
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion}

case class Transfer(
    node: ExerciseNode[
      v1.coin.CoinRules_Transfer,
      v1.coin.TransferResult,
    ]
)

object Transfer extends ExerciseNodeCompanion {
  override type Tpl = v1.coin.CoinRules
  override type Arg = v1.coin.CoinRules_Transfer
  override type Res = v1.coin.TransferResult

  override val templateOrInterface = Right(v1.coin.CoinRules.INTERFACE)
  override val choice = v1.coin.CoinRules.CHOICE_CoinRules_Transfer

  override val argDecoder = v1.coin.CoinRules_Transfer.valueDecoder()
  override def argToValue(arg: v1.coin.CoinRules_Transfer) = arg.toValue

  override val resDecoder = v1.coin.TransferResult.valueDecoder
  override def resToValue(res: v1.coin.TransferResult) = res.toValue
}

case class Tap(node: ExerciseNode[CoinRules_DevNet_Tap, Coin.ContractId])

object Tap extends ExerciseNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_DevNet_Tap
  override type Res = v1.coin.MintSummary[Coin.ContractId]

  override val templateOrInterface = Left(CoinRules.COMPANION)
  override val choice = CoinRules.CHOICE_CoinRules_DevNet_Tap

  override val argDecoder = CoinRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: CoinRules_DevNet_Tap) = arg.toValue

  override val resDecoder =
    v1.coin.MintSummary.valueDecoder(cid => new Coin.ContractId(cid.asContractId().get().getValue))
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object Mint extends ExerciseNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_Mint
  override type Res = v1.coin.MintSummary[Coin.ContractId]

  override val templateOrInterface = Left(CoinRules.COMPANION)
  override val choice = CoinRules.CHOICE_CoinRules_Mint

  override val argDecoder = CoinRules_Mint.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.MintSummary.valueDecoder(cid => new Coin.ContractId(cid.asContractId().get().getValue))
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinUnlock extends ExerciseNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_Unlock
  override type Res = v1.coin.MintSummary[v1.coin.Coin.ContractId]

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_Unlock

  override val argDecoder = v1.coin.LockedCoin_Unlock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.MintSummary.valueDecoder(cid =>
      new v1.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinOwnerExpireLock extends ExerciseNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_OwnerExpireLock
  override type Res = v1.coin.MintSummary[v1.coin.Coin.ContractId]

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_OwnerExpireLock

  override val argDecoder = v1.coin.LockedCoin_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.MintSummary.valueDecoder(cid =>
      new v1.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object CoinArchive {
  // Matches on any consuming exercise on a coin
  def unapply(event: ExercisedEvent): Option[ExercisedEvent] =
    if (event.getTemplateId == Coin.COMPANION.TEMPLATE_ID && event.isConsuming) {
      Some(event)
    } else None
}

object CoinCreate {
  type TCid = Coin.ContractId
  type T = Coin
  type ContractType = Contract[TCid, T]
  val companion = Coin.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

// TODO(#2930): This is not really a Coin event - consider either renaming the file, or splitting it into different ones based on event "types"
object OpenMiningRoundCreate {
  type TCid = OpenMiningRound.ContractId
  type T = OpenMiningRound
  type ContractType = Contract[TCid, T]
  val companion = OpenMiningRound.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object ClosedMiningRoundCreate {
  type TCid = ClosedMiningRound.ContractId
  type T = ClosedMiningRound
  type ContractType = Contract[TCid, T]
  val companion = ClosedMiningRound.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}
