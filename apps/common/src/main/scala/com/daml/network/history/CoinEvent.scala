package com.daml.network.history

import com.daml.ledger.javaapi.data.{CreatedEvent, DamlOptional, ExercisedEvent, Value}
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.coinimport
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions.SubscriptionIdleState
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion, QualifiedName}
import com.digitalasset.canton.logging.ErrorLoggingContext

import java.util.Optional
import scala.jdk.OptionConverters.*

case class Transfer(
    node: ExerciseNode[
      cc.coinrules.CoinRules_Transfer,
      cc.coinrules.TransferResult,
    ]
)

object Transfer extends ExerciseNodeCompanion {
  override type Tpl = cc.coinrules.CoinRules
  override type Arg = cc.coinrules.CoinRules_Transfer
  override type Res = cc.coinrules.TransferResult

  override val template = cc.coinrules.CoinRules.COMPANION
  override val choice = cc.coinrules.CoinRules.CHOICE_CoinRules_Transfer

  override val argDecoder = cc.coinrules.CoinRules_Transfer.valueDecoder()
  override def argToValue(arg: cc.coinrules.CoinRules_Transfer) = arg.toValue

  override val resDecoder = cc.coinrules.TransferResult.valueDecoder
  override def resToValue(res: cc.coinrules.TransferResult) = res.toValue
}

case class Tap(node: ExerciseNode[cc.coinrules.CoinRules_DevNet_Tap, coinCodegen.Coin.ContractId])

object Tap extends ExerciseNodeCompanion {
  override type Tpl = cc.coinrules.CoinRules
  override type Arg = cc.coinrules.CoinRules_DevNet_Tap
  override type Res = cc.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]

  override val template = cc.coinrules.CoinRules.COMPANION
  override val choice = cc.coinrules.CoinRules.CHOICE_CoinRules_DevNet_Tap

  override val argDecoder = cc.coinrules.CoinRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: cc.coinrules.CoinRules_DevNet_Tap) = arg.toValue

  override val resDecoder =
    cc.coin.CoinCreateSummary.valueDecoder(cid =>
      new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object Mint extends ExerciseNodeCompanion {
  override type Tpl = cc.coinrules.CoinRules
  override type Arg = cc.coinrules.CoinRules_Mint
  override type Res = cc.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]

  override val template = cc.coinrules.CoinRules.COMPANION
  override val choice = cc.coinrules.CoinRules.CHOICE_CoinRules_Mint

  override val argDecoder = cc.coinrules.CoinRules_Mint.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.coin.CoinCreateSummary.valueDecoder(cid =>
      new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object ImportCrate_Receive extends ExerciseNodeCompanion {
  override type Tpl = cc.coinimport.ImportCrate
  override type Arg = cc.coinimport.ImportCrate_Receive
  override type Res = Optional[cc.coin.CoinCreateSummary[coinCodegen.Coin.ContractId]]

  override val template = cc.coinimport.ImportCrate.COMPANION
  override val choice = cc.coinimport.ImportCrate.CHOICE_ImportCrate_Receive

  override val argDecoder = cc.coinimport.ImportCrate_Receive.valueDecoder()

  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = {
    PrimitiveValueDecoders.fromOptional(
      cc.coin.CoinCreateSummary.valueDecoder(cid =>
        new coinCodegen.Coin.ContractId(cid.asContractId().get().getValue)
      )
    )
  }

  override def resToValue(res: Res) = {
    val optValue: Optional[Value] = res.map(_.toValue(_.toValue))
    DamlOptional.of(optValue)
  }
}

object ImportCrate_ReceiveCoin {
  def unapply(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[
    ExerciseNode[cc.coinimport.ImportCrate_Receive, cc.coin.CoinCreateSummary[
      coinCodegen.Coin.ContractId
    ]]
  ] =
    ImportCrate_Receive
      .unapply(event)
      .flatMap(node =>
        node.result.value.toScala.map(summary =>
          node.copy(result = new com.daml.network.util.Value(summary))
        )
      )
}

object LockedCoinUnlock extends ExerciseNodeCompanion {
  override type Tpl = cc.coin.LockedCoin
  override type Arg = cc.coin.LockedCoin_Unlock
  override type Res = cc.coin.CoinCreateSummary[cc.coin.Coin.ContractId]

  override val template = cc.coin.LockedCoin.COMPANION
  override val choice = cc.coin.LockedCoin.CHOICE_LockedCoin_Unlock

  override val argDecoder = cc.coin.LockedCoin_Unlock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.coin.CoinCreateSummary.valueDecoder(cid =>
      new cc.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinOwnerExpireLock extends ExerciseNodeCompanion {
  override type Tpl = cc.coin.LockedCoin
  override type Arg = cc.coin.LockedCoin_OwnerExpireLock
  override type Res = cc.coin.CoinCreateSummary[cc.coin.Coin.ContractId]

  override val template = cc.coin.LockedCoin.COMPANION
  override val choice = cc.coin.LockedCoin.CHOICE_LockedCoin_OwnerExpireLock

  override val argDecoder = cc.coin.LockedCoin_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.coin.CoinCreateSummary.valueDecoder(cid =>
      new cc.coin.Coin.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedCoinExpireCoin extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.LockedCoin
  override type Arg = coinCodegen.LockedCoin_ExpireCoin
  override type Res = cc.coin.CoinExpireSummary

  override val template = coinCodegen.LockedCoin.COMPANION
  override val choice = coinCodegen.LockedCoin.CHOICE_LockedCoin_ExpireCoin

  override val argDecoder = coinCodegen.LockedCoin_ExpireCoin.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = cc.coin.CoinExpireSummary.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object CoinRules_BuyMemberTraffic extends ExerciseNodeCompanion {
  override type Tpl = cc.coinrules.CoinRules
  override type Arg = cc.coinrules.CoinRules_BuyMemberTraffic
  override type Res =
    Tuple2[MemberTraffic.ContractId, Optional[cc.coin.Coin.ContractId]]
  override val choice = cc.coinrules.CoinRules.CHOICE_CoinRules_BuyMemberTraffic
  override val template = cc.coinrules.CoinRules.COMPANION
  override val argDecoder = cc.coinrules.CoinRules_BuyMemberTraffic.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = Tuple2.valueDecoder(
    cid => new MemberTraffic.ContractId(cid.asContractId().get().getValue),
    PrimitiveValueDecoders.fromOptional(cid =>
      new cc.coin.Coin.ContractId(cid.asContractId().get().getValue)
    ),
  )

  override def resToValue(res: Res): Value = res.toValue(
    trafficCid => trafficCid.toValue,
    optionalCoin => DamlOptional.of(optionalCoin.map(_.toValue): Optional[Value]),
  )
}

object CnsRules_CollectInitialEntryPayment extends ExerciseNodeCompanion {
  override type Tpl = cnsCodegen.CnsRules
  override type Arg = cnsCodegen.CnsRules_CollectInitialEntryPayment
  override type Res =
    Tuple2[cnsCodegen.CnsEntry.ContractId, SubscriptionIdleState.ContractId]
  override val choice = cnsCodegen.CnsRules.CHOICE_CnsRules_CollectInitialEntryPayment
  override val template = cnsCodegen.CnsRules.COMPANION
  override val argDecoder = cnsCodegen.CnsRules_CollectInitialEntryPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = Tuple2.valueDecoder(
    cid => new cnsCodegen.CnsEntry.ContractId(cid.asContractId().get().getValue),
    cid => new SubscriptionIdleState.ContractId(cid.asContractId().get().getValue),
  )

  override def resToValue(res: Res): Value = res.toValue(_.toValue, _.toValue)
}

object CnsRules_CollectEntryRenewalPayment extends ExerciseNodeCompanion {
  override type Tpl = cnsCodegen.CnsRules
  override type Arg = cnsCodegen.CnsRules_CollectEntryRenewalPayment
  override type Res =
    Tuple2[cnsCodegen.CnsEntry.ContractId, SubscriptionIdleState.ContractId]
  override val choice = cnsCodegen.CnsRules.CHOICE_CnsRules_CollectEntryRenewalPayment
  override val template = cnsCodegen.CnsRules.COMPANION
  override val argDecoder = cnsCodegen.CnsRules_CollectEntryRenewalPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = Tuple2.valueDecoder(
    cid => new cnsCodegen.CnsEntry.ContractId(cid.asContractId().get().getValue),
    cid => new SubscriptionIdleState.ContractId(cid.asContractId().get().getValue),
  )

  override def resToValue(res: Res): Value = res.toValue(_.toValue, _.toValue)
}

object CoinArchive {
  // Matches on any consuming exercise on a coin
  def unapply(event: ExercisedEvent): Option[ExercisedEvent] =
    if (
      QualifiedName(event.getTemplateId) == QualifiedName(
        coinCodegen.Coin.COMPANION.TEMPLATE_ID
      ) && event.isConsuming
    ) {
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

object ImportCrate {
  type TCid = coinimport.ImportCrate.ContractId
  type T = coinimport.ImportCrate
  type ContractType = Contract[TCid, T]
  val companion = coinimport.ImportCrate.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object CoinImportCrate {
  def unapply(
      event: CreatedEvent
  ): Option[coinCodegen.Coin] =
    ImportCrate
      .unapply(event)
      .flatMap(crate =>
        crate.payload.payload match {
          case coinPayload: coinimport.importpayload.IP_Coin =>
            Some(coinPayload.coinValue)
          case _ => None
        }
      )
}

object LockedCoinCreate {
  type TCid = coinCodegen.LockedCoin.ContractId
  type T = coinCodegen.LockedCoin
  type ContractType = Contract[TCid, T]
  val companion = coinCodegen.LockedCoin.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object CoinExpire extends ExerciseNodeCompanion {
  override type Tpl = coinCodegen.Coin
  override type Arg = coinCodegen.Coin_Expire
  override type Res = cc.coin.CoinExpireSummary

  override val template = coinCodegen.Coin.COMPANION
  override val choice = coinCodegen.Coin.CHOICE_Coin_Expire

  override val argDecoder = coinCodegen.Coin_Expire.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = cc.coin.CoinExpireSummary.valueDecoder()
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
