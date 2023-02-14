package com.daml.network.history

import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Value}
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.{Coin, CoinRules, CoinRules_DevNet_Tap}
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
  override type Res = Coin.ContractId

  override val templateOrInterface = Left(CoinRules.COMPANION)
  override val choice = CoinRules.CHOICE_CoinRules_DevNet_Tap

  override val argDecoder = CoinRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: CoinRules_DevNet_Tap) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )
  override def resToValue(res: Coin.ContractId) = res.toValue
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
