package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.client.binding.{Primitive => P}
import com.daml.network.util.{Contract, Value}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.network.CC.Coin.{Coin, Coin_Unlock}
import com.digitalasset.network.CC.CoinRules.{
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
  TransferResult,
}
import com.digitalasset.network.CC.Round.IssuingMiningRound

/** Parent node of a Canton coin create or archive within the corresponding transaction tree. */
sealed trait ParentNode {
  def toProtoV0: v0.ParentNode
}
case class Transfer(argument: CoinRules_Transfer, result: P.List[TransferResult])
    extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode(
      v0.ParentNode.Type.Transfer(
        v0.ExerciseNode(Some(Value(argument).toProtoV0), Some(Value(result).toProtoV0))
      )
    )
}

object Transfer {
  def fromProtoV0(
      transferP: v0.ParentNode.Type.Transfer
  ): Either[ProtoDeserializationError, Transfer] = {
    for {
      argumentP <- ProtoConverter
        .required("Transfer.ExerciseNode.argument", transferP.value.argument)
      argument <- Value.fromProto[CoinRules_Transfer](argumentP)
      resultP <- ProtoConverter
        .required("Transfer.ExerciseNode.result", transferP.value.result)
      result <- Value
        .fromProto[P.List[com.digitalasset.network.CC.CoinRules.TransferResult]](resultP)
    } yield Transfer(argument.value, result.value)
  }
}

case class Tap(argument: CoinRules_Tap, result: P.ContractId[Coin]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode(
      v0.ParentNode.Type.Tap(
        v0.ExerciseNode(
          Some(Value(argument).toProtoV0),
          Some(Value(result).toProtoV0),
        )
      )
    )
}

object Tap {
  def fromProtoV0(tapP: v0.ParentNode.Type.Tap): Either[ProtoDeserializationError, Tap] = for {
    argumentP <- ProtoConverter
      .required("Tap.ExerciseNode.argument", tapP.value.argument)
    argument <- Value.fromProto[CoinRules_Tap](argumentP)
    resultP <- ProtoConverter
      .required(s"Tap.ExerciseNode.result", tapP.value.result)
    result <- Value.fromProto[P.ContractId[Coin]](resultP)
  } yield Tap(argument.value, result.value)
}

case class StartIssuing(
    argument: CoinRules_MiningRound_StartIssuing,
    result: P.ContractId[IssuingMiningRound],
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode(
      v0.ParentNode.Type.StartIssuing(
        v0.ExerciseNode(Some(Value(argument).toProtoV0), Some(Value(result).toProtoV0))
      )
    )
}

object StartIssuing {
  def fromProtoV0(
      issuingP: v0.ParentNode.Type.StartIssuing
  ): Either[ProtoDeserializationError, StartIssuing] = for {
    argumentP <- ProtoConverter
      .required("StartIssuing.ExerciseNode.argument", issuingP.value.argument)
    argument <- Value.fromProto[CoinRules_MiningRound_StartIssuing](argumentP)
    resultP <- ProtoConverter
      .required("StartIssuing.ExerciseNode.result", issuingP.value.result)
    result <- Value.fromProto[P.ContractId[IssuingMiningRound]](resultP)
  } yield StartIssuing(argument.value, result.value)
}

case class CoinUnlock(
    argument: Coin_Unlock,
    result: P.ContractId[Coin],
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode(
      v0.ParentNode.Type.StartIssuing(
        v0.ExerciseNode(Some(Value(argument).toProtoV0), Some(Value(result).toProtoV0))
      )
    )
}

object CoinUnlock {
  def fromProtoV0(
      issuingP: v0.ParentNode.Type.CoinUnlock
  ): Either[ProtoDeserializationError, CoinUnlock] = for {
    argumentP <- ProtoConverter
      .required("StartIssuing.ExerciseNode.argument", issuingP.value.argument)
    argument <- Value.fromProto[Coin_Unlock](argumentP)
    resultP <- ProtoConverter
      .required("StartIssuing.ExerciseNode.result", issuingP.value.result)
    result <- Value.fromProto[P.ContractId[Coin]](resultP)
  } yield CoinUnlock(argument.value, result.value)
}

object ParentNode {
  def fromProtoV0(nodeP: v0.ParentNode): Either[ProtoDeserializationError, ParentNode] = {
    nodeP.`type` match {
      case v0.ParentNode.Type.Empty =>
        Left(ProtoDeserializationError.FieldNotSet("ParentNode.type"))
      case tap: v0.ParentNode.Type.Tap => Tap.fromProtoV0(tap)
      case transfer: v0.ParentNode.Type.Transfer => Transfer.fromProtoV0(transfer)
      case issuing: v0.ParentNode.Type.StartIssuing => StartIssuing.fromProtoV0(issuing)
      case issuing: v0.ParentNode.Type.CoinUnlock => CoinUnlock.fromProtoV0(issuing)
    }
  }
}

sealed trait EventTypeAndCoin {
  def coin: Contract[Coin]
  def isArchive: Boolean
  def isCreate: Boolean = !isArchive
  def toProtoV0: v0.CCEvent.Coin
}

object EventTypeAndCoin {
  def fromProtoV0(proto: v0.CCEvent.Coin): Either[ProtoDeserializationError, EventTypeAndCoin] = {
    proto match {
      case v0.CCEvent.Coin.Empty => Left(ProtoDeserializationError.FieldNotSet("CCEvent.coin"))
      case v0.CCEvent.Coin.Create(create) => Contract.fromProto(Coin)(create).map(CoinCreate)
      case v0.CCEvent.Coin.Archive(archive) => Contract.fromProto(Coin)(archive).map(CoinArchive)
    }
  }
}

case class CoinCreate(coin: Contract[Coin]) extends EventTypeAndCoin {
  override def isArchive: Boolean = false
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Create(coin.toProtoV0)
}
case class CoinArchive(coin: Contract[Coin]) extends EventTypeAndCoin {
  override def isArchive: Boolean = true
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Archive(coin.toProtoV0)
}

case class CoinEvent(
    coin: EventTypeAndCoin,
    parentO: Option[ParentNode],
) {
  def toProtoV0: v0.CCEvent =
    v0.CCEvent(coin = coin.toProtoV0, parent = parentO.map(a => a.toProtoV0))
}

object CoinEvent {
  def fromProtoV0(eventP: v0.CCEvent): Either[ProtoDeserializationError, CoinEvent] = {
    for {
      coin <- EventTypeAndCoin.fromProtoV0(eventP.coin)
      ancestor <- eventP.parent.map(ParentNode.fromProtoV0).sequence
    } yield CoinEvent(coin, ancestor)
  }
}
