package com.daml.network.history

import com.daml.ledger.javaapi.data.{CreatedEvent, DamlOptional, ExercisedEvent, Value}
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion}

import java.util.Optional

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

case class Tap(node: ExerciseNode[coinCodegen.CoinRules_DevNet_Tap, coinCodegen.Coin.ContractId])

object Tap extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.CoinRules
  override type Arg = coinCodegen.CoinRules_DevNet_Tap
  override type Res = v1.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]

  override val templateOrInterface = Left(coinCodegen.CoinRules.COMPANION)
  override val choice = coinCodegen.CoinRules.CHOICE_CoinRules_DevNet_Tap

  override val argDecoder = coinCodegen.CoinRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: coinCodegen.CoinRules_DevNet_Tap) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object Mint extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.CoinRules
  override type Arg = coinCodegen.CoinRules_Mint
  override type Res = v1.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]

  override val templateOrInterface = Left(coinCodegen.CoinRules.COMPANION)
  override val choice = coinCodegen.CoinRules.CHOICE_CoinRules_Mint

  override val argDecoder = coinCodegen.CoinRules_Mint.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object ImportCrate_Receive extends ExerciseNodeCompanion {
  override type Tpl = cc.coinimport.ImportCrate
  override type Arg = cc.coinimport.ImportCrate_Receive
  override type Res = v1.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]

  override val templateOrInterface = Left(cc.coinimport.ImportCrate.COMPANION)
  override val choice = cc.coinimport.ImportCrate.CHOICE_ImportCrate_Receive

  override val argDecoder = cc.coinimport.ImportCrate_Receive.valueDecoder()

  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
    )

  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinUnlock extends ExerciseNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_Unlock
  override type Res = v1.coin.CoinCreateSummary[v1.coin.Coin.ContractId]

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_Unlock

  override val argDecoder = v1.coin.LockedCoin_Unlock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new v1.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinOwnerExpireLock extends ExerciseNodeCompanion {
  override type Tpl = v1.coin.LockedCoin
  override type Arg = v1.coin.LockedCoin_OwnerExpireLock
  override type Res = v1.coin.CoinCreateSummary[v1.coin.Coin.ContractId]

  override val templateOrInterface = Right(v1.coin.LockedCoin.INTERFACE)
  override val choice = v1.coin.LockedCoin.CHOICE_LockedCoin_OwnerExpireLock

  override val argDecoder = v1.coin.LockedCoin_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    v1.coin.CoinCreateSummary.valueDecoder(cid =>
      new v1.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinExpireCoin extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.LockedCoin
  override type Arg = coinCodegen.LockedCoin_ExpireCoin
  override type Res = v1.coin.CoinExpireSummary

  override val templateOrInterface = Left(coinCodegen.LockedCoin.COMPANION)
  override val choice = coinCodegen.LockedCoin.CHOICE_LockedCoin_ExpireCoin

  override val argDecoder = coinCodegen.LockedCoin_ExpireCoin.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = v1.coin.CoinExpireSummary.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object CoinRules_BuyExtraTraffic extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.CoinRules
  override type Arg = coinCodegen.CoinRules_BuyExtraTraffic
  override type Res =
    Tuple2[ValidatorTraffic.ContractId, Optional[v1.coin.Coin.ContractId]]
  override val choice = coinCodegen.CoinRules.CHOICE_CoinRules_BuyExtraTraffic
  override val templateOrInterface = Left(coinCodegen.CoinRules.COMPANION)
  override val argDecoder = coinCodegen.CoinRules_BuyExtraTraffic.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = Tuple2.valueDecoder(
    cid => new ValidatorTraffic.ContractId(cid.asContractId().get().getValue),
    PrimitiveValueDecoders.fromOptional(cid =>
      new v1.coin.Coin.ContractId(cid.asContractId().get().getValue)
    ),
  )

  override def resToValue(res: Res): Value = res.toValue(
    trafficCid => trafficCid.toValue,
    optionalCoin => DamlOptional.of(optionalCoin.map(_.toValue): Optional[Value]),
  )
}

object CoinArchive {
  // Matches on any consuming exercise on a coin
  def unapply(event: ExercisedEvent): Option[ExercisedEvent] =
    if (event.getTemplateId == coinCodegen.Coin.COMPANION.TEMPLATE_ID && event.isConsuming) {
      Some(event)
    } else None
}

object CoinCreate {
  type TCid = coinCodegen.Coin.ContractId
  type T = coinCodegen.Coin
  type ContractType = Contract[TCid, T]
  val companion = coinCodegen.Coin.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object CoinExpire extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.Coin
  override type Arg = coinCodegen.Coin_Expire
  override type Res = v1.coin.CoinExpireSummary

  override val templateOrInterface = Left(coinCodegen.Coin.COMPANION)
  override val choice = coinCodegen.Coin.CHOICE_Coin_Expire

  override val argDecoder = coinCodegen.Coin_Expire.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = v1.coin.CoinExpireSummary.valueDecoder()
  override def resToValue(res: Res) = res.toValue
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
