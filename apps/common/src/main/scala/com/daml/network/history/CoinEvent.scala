package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.client.binding.{Primitive => P}
import com.daml.network.util.{Contract, Proto, Value}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.{
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
}

/** Parent of a a CC create or archive within the corresponding transaction tree. */
sealed trait AncestorEvent {
  def toProtoV0: v0.ExerciseNode.Ancestor
}
case class Transfer(value: CoinRules_Transfer) extends AncestorEvent {
  def toProtoV0: v0.ExerciseNode.Ancestor.Transfer =
    v0.ExerciseNode.Ancestor.Transfer(Value(value).toProtoV0)
}

case class Tap(value: CoinRules_Tap) extends AncestorEvent {
  def toProtoV0: v0.ExerciseNode.Ancestor.Tap = v0.ExerciseNode.Ancestor.Tap(Value(value).toProtoV0)
}

case class StartIssuing(value: CoinRules_MiningRound_StartIssuing) extends AncestorEvent {
  def toProtoV0: v0.ExerciseNode.Ancestor.StartIssuing =
    v0.ExerciseNode.Ancestor.StartIssuing(Value(value).toProtoV0)
}

object AncestorEvent {
  def fromProtoV0(eventP: v0.ExerciseNode): Either[ProtoDeserializationError, AncestorEvent] = {
    eventP.ancestor match {
      case v0.ExerciseNode.Ancestor.Empty =>
        Left(ProtoDeserializationError.FieldNotSet("ExerciseNode.ancestor"))
      case tap: v0.ExerciseNode.Ancestor.Tap =>
        Value.fromProto[CoinRules_Tap](tap.value).map(x => Tap(x.value))
      case transfer: v0.ExerciseNode.Ancestor.Transfer =>
        Value.fromProto[CoinRules_Transfer](transfer.value).map(x => Transfer(x.value))
      case issuing: v0.ExerciseNode.Ancestor.StartIssuing =>
        Value
          .fromProto[CoinRules_MiningRound_StartIssuing](issuing.value)
          .map(x => StartIssuing(x.value))
    }
  }
}

sealed trait EventTypeAndCoin {
  def isArchive: Boolean
  def isCreate: Boolean = !isArchive
  def toProtoV0: v0.CCEvent.Coin
}

object EventTypeAndCoin {
  def fromProtoV0(proto: v0.CCEvent.Coin): Either[ProtoDeserializationError, EventTypeAndCoin] = {
    proto match {
      case v0.CCEvent.Coin.Empty => Left(ProtoDeserializationError.FieldNotSet("CCEvent.coin"))
      case v0.CCEvent.Coin.Create(create) =>
        val coinE = ProtoConverter
          .required("CoinCreate.contract", create.contract)
          .flatMap(Contract.fromProto[Coin](Coin))
        coinE.map(CoinCreate)
      case v0.CCEvent.Coin.Archive(archive) =>
        Proto.decodeContractIdDeserialization(archive.contractId).map(CoinArchive)
    }
  }
}

case class CoinCreate(coin: Contract[Coin]) extends EventTypeAndCoin {
  override def isArchive: Boolean = false
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Create(v0.CoinCreate(Some(coin.toProtoV0)))
}
case class CoinArchive(coinCid: P.ContractId[Coin]) extends EventTypeAndCoin {
  override def isArchive: Boolean = true
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Archive(v0.CoinArchive(Proto.encode(coinCid)))
}

case class CoinEvent(
    coin: EventTypeAndCoin,
    ancestorO: Option[AncestorEvent],
) {
  def toProtoV0: v0.CCEvent =
    v0.CCEvent(coin = coin.toProtoV0, ancestor = ancestorO.map(a => v0.ExerciseNode(a.toProtoV0)))
}

object CoinEvent {
  def fromProtoV0(eventP: v0.CCEvent): Either[ProtoDeserializationError, CoinEvent] = {
    for {
      coin <- EventTypeAndCoin.fromProtoV0(eventP.coin)
      ancestor <- eventP.ancestor.map(AncestorEvent.fromProtoV0).sequence
    } yield CoinEvent(coin, ancestor)
  }
}
