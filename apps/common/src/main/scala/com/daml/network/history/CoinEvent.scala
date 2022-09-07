package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.client.binding.{Primitive => P}
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError
import com.daml.network.codegen.CC.Coin.{Coin, Coin_Unlock}
import com.daml.network.codegen.CC.CoinRules.{
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
  TransferResult,
}
import com.daml.network.codegen.CC.Round.IssuingMiningRound

/** Parent node of a Canton coin create or archive within the corresponding transaction tree. */
sealed trait ParentNode {
  def toProtoV0: v0.ParentNode
}
case class Transfer(node: ExerciseNode[CoinRules_Transfer, TransferResult]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTransfer(node.toProtoV0)
}

object Transfer extends ExerciseNodeCompanion {
  override type Arg = CoinRules_Transfer
  override type Res = TransferResult

  def fromProtoV0(
      transferP: v0.ParentNode.Type.Transfer
  ): Either[ProtoDeserializationError, Transfer] = {
    for {
      node <- ExerciseNode.fromProto(Transfer)(transferP.value)
    } yield Transfer(node)
  }
}

case class Tap(node: ExerciseNode[CoinRules_Tap, P.ContractId[Coin]]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTap(node.toProtoV0)
}

object Tap extends ExerciseNodeCompanion {
  override type Arg = CoinRules_Tap
  override type Res = P.ContractId[Coin]

  def fromProtoV0(tapP: v0.ParentNode.Type.Tap): Either[ProtoDeserializationError, Tap] = for {
    node <- ExerciseNode.fromProto(Tap)(tapP.value)
  } yield Tap(node)
}

case class StartIssuing(
    node: ExerciseNode[CoinRules_MiningRound_StartIssuing, P.ContractId[IssuingMiningRound]]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode()
      .withStartIssuing(node.toProtoV0)
}

object StartIssuing extends ExerciseNodeCompanion {
  override type Arg = CoinRules_MiningRound_StartIssuing
  override type Res = P.ContractId[IssuingMiningRound]

  def fromProtoV0(
      issuingP: v0.ParentNode.Type.StartIssuing
  ): Either[ProtoDeserializationError, StartIssuing] = for {
    node <- ExerciseNode.fromProto(StartIssuing)(issuingP.value)
  } yield StartIssuing(node)
}

case class CoinUnlock(
    node: ExerciseNode[Coin_Unlock, P.ContractId[Coin]]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withCoinUnlock(node.toProtoV0)
}

object CoinUnlock extends ExerciseNodeCompanion {
  override type Arg = Coin_Unlock
  override type Res = P.ContractId[Coin]

  def fromProtoV0(
      unlockP: v0.ParentNode.Type.CoinUnlock
  ): Either[ProtoDeserializationError, CoinUnlock] = for {
    node <- ExerciseNode.fromProto(CoinUnlock)(unlockP.value)
  } yield CoinUnlock(node)
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
