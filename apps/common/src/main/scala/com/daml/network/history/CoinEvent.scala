package com.daml.network.history

import cats.syntax.traverse._
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.ledger.javaapi.data.{Text, Value}
import com.daml.network.codegen.java.cc.coin.{
  Coin,
  LockedCoin,
  LockedCoin_OwnerExpireLock,
  LockedCoin_SvcExpireLock,
  LockedCoin_Unlock,
}
import com.daml.network.codegen.java.cc.coinrules.{
  CoinRules,
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Tap,
  CoinRules_Transfer,
  TransferResult,
}
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.da
import com.daml.network.util.{ExerciseNode, ExerciseNodeCompanion, JavaContract => Contract}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError

/** Parent node of a Canton coin create or archive within the corresponding transaction tree. */
sealed trait ParentNode {
  def toProtoV0: v0.ParentNode
}
case class Transfer(node: ExerciseNode[CoinRules_Transfer, da.types.Either[String, TransferResult]])
    extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTransfer(node.toProtoV0)
}

trait ParentNodeCompanion extends ExerciseNodeCompanion {
  def toParentNode(exercise: ExerciseNode[Arg, Res]): ParentNode
}

object Transfer extends ParentNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_Transfer
  override type Res = da.types.Either[String, TransferResult]

  override val template = CoinRules.COMPANION
  override val choice = CoinRules.CHOICE_CoinRules_Transfer

  override val argDecoder = CoinRules_Transfer.valueDecoder()
  override def argToValue(arg: CoinRules_Transfer) = arg.toValue

  override val resDecoder = da.types.Either.valueDecoder[String, TransferResult](
    PrimitiveValueDecoders.fromText,
    TransferResult.valueDecoder,
  )
  override def resToValue(res: da.types.Either[String, TransferResult]) =
    res.toValue(t => new Text(t), _.toValue)

  override def toParentNode(node: ExerciseNode[Arg, Res]) = Transfer(node)

  def fromProtoV0(
      transferP: v0.ParentNode.Type.Transfer
  ): Either[ProtoDeserializationError, Transfer] = {
    for {
      node <- ExerciseNode.fromProto(Transfer)(transferP.value)
    } yield Transfer(node)
  }
}

case class Tap(node: ExerciseNode[CoinRules_Tap, Coin.ContractId]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTap(node.toProtoV0)
}

object Tap extends ParentNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_Tap
  override type Res = Coin.ContractId

  override val template = CoinRules.COMPANION
  override val choice = CoinRules.CHOICE_CoinRules_Tap

  override val argDecoder = CoinRules_Tap.valueDecoder()
  override def argToValue(arg: CoinRules_Tap) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )
  override def resToValue(res: Coin.ContractId) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = Tap(node)

  def fromProtoV0(tapP: v0.ParentNode.Type.Tap): Either[ProtoDeserializationError, Tap] = for {
    node <- ExerciseNode.fromProto(Tap)(tapP.value)
  } yield Tap(node)
}

case class StartIssuing(
    node: ExerciseNode[CoinRules_MiningRound_StartIssuing, IssuingMiningRound.ContractId]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode()
      .withStartIssuing(node.toProtoV0)
}

object StartIssuing extends ParentNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_MiningRound_StartIssuing
  override type Res = IssuingMiningRound.ContractId

  override val template = CoinRules.COMPANION
  override val choice = CoinRules.CHOICE_CoinRules_MiningRound_StartIssuing

  override val argDecoder = CoinRules_MiningRound_StartIssuing.valueDecoder()
  override def argToValue(arg: CoinRules_MiningRound_StartIssuing) = arg.toValue

  override val resDecoder = (cid: Value) =>
    IssuingMiningRound.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(IssuingMiningRound.valueDecoder).decode(cid)
    )
  override def resToValue(res: IssuingMiningRound.ContractId) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = StartIssuing(node)

  def fromProtoV0(
      issuingP: v0.ParentNode.Type.StartIssuing
  ): Either[ProtoDeserializationError, StartIssuing] = for {
    node <- ExerciseNode.fromProto(StartIssuing)(issuingP.value)
  } yield StartIssuing(node)
}

case class OwnerExpireLock(node: ExerciseNode[LockedCoin_OwnerExpireLock, Coin.ContractId])
    extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withOwnerExpireLock(node.toProtoV0)
}

object OwnerExpireLock extends ParentNodeCompanion {
  override type Tpl = LockedCoin
  override type Arg = LockedCoin_OwnerExpireLock
  override type Res = Coin.ContractId

  override val template = LockedCoin.COMPANION
  override val choice = LockedCoin.CHOICE_LockedCoin_OwnerExpireLock

  override val argDecoder = LockedCoin_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: LockedCoin_OwnerExpireLock) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )
  override def resToValue(res: Coin.ContractId) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = OwnerExpireLock(node)

  def fromProtoV0(
      expireP: v0.ParentNode.Type.OwnerExpireLock
  ): Either[ProtoDeserializationError, OwnerExpireLock] = for {
    node <- ExerciseNode.fromProto(OwnerExpireLock)(expireP.value)
  } yield OwnerExpireLock(node)
}

case class SvcExpireLock(node: ExerciseNode[LockedCoin_SvcExpireLock, Coin.ContractId])
    extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withSvcExpireLock(node.toProtoV0)
}

object SvcExpireLock extends ParentNodeCompanion {
  override type Tpl = LockedCoin
  override type Arg = LockedCoin_SvcExpireLock
  override type Res = Coin.ContractId

  override val template = LockedCoin.COMPANION
  override val choice = LockedCoin.CHOICE_LockedCoin_SvcExpireLock

  override val argDecoder = LockedCoin_SvcExpireLock.valueDecoder()
  override def argToValue(arg: LockedCoin_SvcExpireLock) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )

  override def toParentNode(node: ExerciseNode[Arg, Res]) = SvcExpireLock(node)
  override def resToValue(res: Coin.ContractId) = res.toValue

  def fromProtoV0(
      expireP: v0.ParentNode.Type.SvcExpireLock
  ): Either[ProtoDeserializationError, SvcExpireLock] = for {
    node <- ExerciseNode.fromProto(SvcExpireLock)(expireP.value)
  } yield SvcExpireLock(node)
}

case class CoinUnlock(
    node: ExerciseNode[LockedCoin_Unlock, Coin.ContractId]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withCoinUnlock(node.toProtoV0)
}

object CoinUnlock extends ParentNodeCompanion {
  override type Tpl = LockedCoin
  override type Arg = LockedCoin_Unlock
  override type Res = Coin.ContractId

  override val template = LockedCoin.COMPANION
  override val choice = LockedCoin.CHOICE_LockedCoin_Unlock

  override val argDecoder = LockedCoin_Unlock.valueDecoder()
  override def argToValue(arg: LockedCoin_Unlock) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )

  override def toParentNode(node: ExerciseNode[Arg, Res]) = CoinUnlock(node)
  override def resToValue(res: Coin.ContractId) = res.toValue

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
      case unlock: v0.ParentNode.Type.CoinUnlock => CoinUnlock.fromProtoV0(unlock)
      case ownerLock: v0.ParentNode.Type.OwnerExpireLock => OwnerExpireLock.fromProtoV0(ownerLock)
      case svcLock: v0.ParentNode.Type.SvcExpireLock => SvcExpireLock.fromProtoV0(svcLock)
    }
  }
}

/** Trait that represents union types of Coin/LockedCoin Contract's
  * Note that we don't add a type variable on purpose, as otherwise `CoinTransaction` would require that all
  * events in a transaction would contain exclusively either `Coin`s or `LockedCoin`s (but not a mix of them)
  */
sealed trait CoinOrLockedCoinContract {
  def toProtoV0: v0.Contract
}

case class CoinContract(contract: Contract[Coin.ContractId, Coin])
    extends CoinOrLockedCoinContract {
  override def toProtoV0: v0.Contract = contract.toProtoV0
}

case class LockedCoinContract(contract: Contract[LockedCoin.ContractId, LockedCoin])
    extends CoinOrLockedCoinContract {
  override def toProtoV0: v0.Contract = contract.toProtoV0
}

sealed trait EventTypeAndCoin {
  def coin: CoinOrLockedCoinContract
  def isArchive: Boolean
  def isCreate: Boolean = !isArchive
  def toProtoV0: v0.CCEvent.Coin
}

object EventTypeAndCoin {
  def fromProtoV0(
      proto: v0.CCEvent.Coin
  ): Either[ProtoDeserializationError, EventTypeAndCoin] = {
    proto match {
      case v0.CCEvent.Coin.Empty => Left(ProtoDeserializationError.FieldNotSet("CCEvent.coin"))
      case v0.CCEvent.Coin.Create(create) =>
        Contract.fromProto(Coin.COMPANION)(create).map(c => CoinCreate(CoinContract(c)))
      case v0.CCEvent.Coin.Archive(archive) =>
        Contract.fromProto(Coin.COMPANION)(archive).map(c => CoinArchive(CoinContract(c)))
      case v0.CCEvent.Coin.LockedCreate(create) =>
        Contract.fromProto(LockedCoin.COMPANION)(create).map(c => CoinCreate(LockedCoinContract(c)))
      case v0.CCEvent.Coin.LockedArchive(archive) =>
        Contract
          .fromProto(LockedCoin.COMPANION)(archive)
          .map(c => CoinArchive(LockedCoinContract(c)))
    }
  }
}

case class CoinCreate(coin: CoinOrLockedCoinContract) extends EventTypeAndCoin {
  override def isArchive: Boolean = false
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Create(coin.toProtoV0)
}
case class CoinArchive(coin: CoinOrLockedCoinContract) extends EventTypeAndCoin {
  override def isArchive: Boolean = true
  def toProtoV0: v0.CCEvent.Coin = v0.CCEvent.Coin.Archive(coin.toProtoV0)
}

/** Representation of a Coin event. A CoinEvent is the create or archive of a Coin or LockedCoin and additionally
  * saves the respective parent node from the TransactionTree.
  */
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
