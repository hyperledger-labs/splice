package com.daml.network.history

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data.Value
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.{
  Coin,
  CoinRules,
  CoinRules_MiningRound_StartIssuing,
  CoinRules_Mint,
  CoinRules_DevNet_Tap,
  LockedCoin,
}
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.util.{ExerciseNode, ExerciseNodeCompanion, Contract}
import com.daml.network.v0
import com.digitalasset.canton.ProtoDeserializationError

/** Parent node of a Canton coin create or archive within the corresponding transaction tree. */
sealed trait ParentNode {
  def toProtoV0: v0.ParentNode
}
case class Transfer(
    node: ExerciseNode[
      v1.coin.CoinRules_Transfer,
      v1.coin.TransferResult,
    ]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTransfer(node.toProtoV0)
}

trait ParentNodeCompanion extends ExerciseNodeCompanion {
  def toParentNode(exercise: ExerciseNode[Arg, Res]): ParentNode
}

object Transfer extends ParentNodeCompanion {
  override type Tpl = v1.coin.CoinRules
  override type Arg = v1.coin.CoinRules_Transfer
  override type Res = v1.coin.TransferResult

  override val templateOrInterface = Right(v1.coin.CoinRules.INTERFACE)
  override val choice = v1.coin.CoinRules.CHOICE_CoinRules_Transfer

  override val argDecoder = v1.coin.CoinRules_Transfer.valueDecoder()
  override def argToValue(arg: v1.coin.CoinRules_Transfer) = arg.toValue

  override val resDecoder = v1.coin.TransferResult.valueDecoder
  override def resToValue(res: v1.coin.TransferResult) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = Transfer(node)

  def fromProtoV0(
      transferP: v0.ParentNode.Type.Transfer
  ): Either[ProtoDeserializationError, Transfer] = {
    for {
      node <- ExerciseNode.fromProto(Transfer)(transferP.value)
    } yield Transfer(node)
  }
}

case class Mint(node: ExerciseNode[CoinRules_Mint, Coin.ContractId]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withMint(node.toProtoV0)
}

object Mint extends ParentNodeCompanion {
  override type Tpl = CoinRules
  override type Arg = CoinRules_Mint
  override type Res = Coin.ContractId

  override val templateOrInterface = Left(CoinRules.COMPANION)
  override val choice = CoinRules.CHOICE_CoinRules_Mint

  override val argDecoder = CoinRules_Mint.valueDecoder()
  override def argToValue(arg: CoinRules_Mint) = arg.toValue

  override val resDecoder = (cid: Value) =>
    Coin.ContractId.fromContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid)
    )
  override def resToValue(res: Coin.ContractId) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = Mint(node)

  def fromProtoV0(mintP: v0.ParentNode.Type.Mint): Either[ProtoDeserializationError, Mint] = for {
    node <- ExerciseNode.fromProto(Mint)(mintP.value)
  } yield Mint(node)
}

case class Tap(node: ExerciseNode[CoinRules_DevNet_Tap, Coin.ContractId]) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withTap(node.toProtoV0)
}

object Tap extends ParentNodeCompanion {
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

  override val templateOrInterface = Left(CoinRules.COMPANION)
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

case class OwnerExpireLock(
    node: ExerciseNode[v1.coin.LockedCoin_OwnerExpireLock, v1.coin.Coin.ContractId]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withOwnerExpireLock(node.toProtoV0)
}

object OwnerExpireLock extends ParentNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_OwnerExpireLock
  override type Res = v1.coin.Coin.ContractId

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_OwnerExpireLock

  override val argDecoder = v1.coin.LockedCoin_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: v1.coin.LockedCoin_OwnerExpireLock) = arg.toValue

  override val resDecoder = (cid: Value) =>
    new v1.coin.Coin.ContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid).contractId
    )
  override def resToValue(res: v1.coin.Coin.ContractId) = res.toValue

  override def toParentNode(node: ExerciseNode[Arg, Res]) = OwnerExpireLock(node)

  def fromProtoV0(
      expireP: v0.ParentNode.Type.OwnerExpireLock
  ): Either[ProtoDeserializationError, OwnerExpireLock] = for {
    node <- ExerciseNode.fromProto(OwnerExpireLock)(expireP.value)
  } yield OwnerExpireLock(node)
}

case class CoinUnlock(
    node: ExerciseNode[v1.coin.LockedCoin_Unlock, v1.coin.Coin.ContractId]
) extends ParentNode {
  def toProtoV0: v0.ParentNode =
    v0.ParentNode().withCoinUnlock(node.toProtoV0)
}

object CoinUnlock extends ParentNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_Unlock
  override type Res = v1.coin.Coin.ContractId

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_Unlock

  override val argDecoder = v1.coin.LockedCoin_Unlock.valueDecoder()
  override def argToValue(arg: v1.coin.LockedCoin_Unlock) = arg.toValue

  override val resDecoder = (cid: Value) =>
    new v1.coin.Coin.ContractId(
      PrimitiveValueDecoders.fromContractId(Coin.valueDecoder).decode(cid).contractId
    )

  override def toParentNode(node: ExerciseNode[Arg, Res]) = CoinUnlock(node)
  override def resToValue(res: v1.coin.Coin.ContractId) = res.toValue

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
      case mint: v0.ParentNode.Type.Mint => Mint.fromProtoV0(mint)
      case transfer: v0.ParentNode.Type.Transfer => Transfer.fromProtoV0(transfer)
      case issuing: v0.ParentNode.Type.StartIssuing => StartIssuing.fromProtoV0(issuing)
      case unlock: v0.ParentNode.Type.CoinUnlock => CoinUnlock.fromProtoV0(unlock)
      case ownerLock: v0.ParentNode.Type.OwnerExpireLock => OwnerExpireLock.fromProtoV0(ownerLock)
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
