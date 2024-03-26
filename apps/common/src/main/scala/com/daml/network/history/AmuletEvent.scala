package com.daml.network.history

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Value}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.amulet as amuletCodegen
import com.daml.network.codegen.java.cc.round.{ClosedMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion, QualifiedName}

case class Transfer(
    node: ExerciseNode[
      cc.amuletrules.AmuletRules_Transfer,
      cc.amuletrules.TransferResult,
    ]
)

object Transfer extends ExerciseNodeCompanion {
  override type Tpl = cc.amuletrules.AmuletRules
  override type Arg = cc.amuletrules.AmuletRules_Transfer
  override type Res = cc.amuletrules.TransferResult

  override val template = cc.amuletrules.AmuletRules.COMPANION
  override val choice = cc.amuletrules.AmuletRules.CHOICE_AmuletRules_Transfer

  override val argDecoder = cc.amuletrules.AmuletRules_Transfer.valueDecoder()
  override def argToValue(arg: cc.amuletrules.AmuletRules_Transfer) = arg.toValue

  override val resDecoder = cc.amuletrules.TransferResult.valueDecoder
  override def resToValue(res: cc.amuletrules.TransferResult) = res.toValue
}

case class Tap(
    node: ExerciseNode[cc.amuletrules.AmuletRules_DevNet_Tap, amuletCodegen.Amulet.ContractId]
)

object Tap extends ExerciseNodeCompanion {
  override type Tpl = cc.amuletrules.AmuletRules
  override type Arg = cc.amuletrules.AmuletRules_DevNet_Tap
  override type Res = cc.amulet.AmuletCreateSummary[amuletCodegen.Amulet.ContractId]

  override val template = cc.amuletrules.AmuletRules.COMPANION
  override val choice = cc.amuletrules.AmuletRules.CHOICE_AmuletRules_DevNet_Tap

  override val argDecoder = cc.amuletrules.AmuletRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: cc.amuletrules.AmuletRules_DevNet_Tap) = arg.toValue

  override val resDecoder =
    cc.amulet.AmuletCreateSummary.valueDecoder(cid =>
      new amuletCodegen.Amulet.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object Mint extends ExerciseNodeCompanion {
  override type Tpl = cc.amuletrules.AmuletRules
  override type Arg = cc.amuletrules.AmuletRules_Mint
  override type Res = cc.amulet.AmuletCreateSummary[amuletCodegen.Amulet.ContractId]

  override val template = cc.amuletrules.AmuletRules.COMPANION
  override val choice = cc.amuletrules.AmuletRules.CHOICE_AmuletRules_Mint

  override val argDecoder = cc.amuletrules.AmuletRules_Mint.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.amulet.AmuletCreateSummary.valueDecoder(cid =>
      new amuletCodegen.Amulet.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedAmuletUnlock extends ExerciseNodeCompanion {
  override type Tpl = cc.amulet.LockedAmulet
  override type Arg = cc.amulet.LockedAmulet_Unlock
  override type Res = cc.amulet.AmuletCreateSummary[cc.amulet.Amulet.ContractId]

  override val template = cc.amulet.LockedAmulet.COMPANION
  override val choice = cc.amulet.LockedAmulet.CHOICE_LockedAmulet_Unlock

  override val argDecoder = cc.amulet.LockedAmulet_Unlock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.amulet.AmuletCreateSummary.valueDecoder(cid =>
      new cc.amulet.Amulet.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedAmuletOwnerExpireLock extends ExerciseNodeCompanion {
  override type Tpl = cc.amulet.LockedAmulet
  override type Arg = cc.amulet.LockedAmulet_OwnerExpireLock
  override type Res = cc.amulet.AmuletCreateSummary[cc.amulet.Amulet.ContractId]

  override val template = cc.amulet.LockedAmulet.COMPANION
  override val choice = cc.amulet.LockedAmulet.CHOICE_LockedAmulet_OwnerExpireLock

  override val argDecoder = cc.amulet.LockedAmulet_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder =
    cc.amulet.AmuletCreateSummary.valueDecoder(cid =>
      new cc.amulet.Amulet.ContractId(cid.asContractId().get().getValue)
    )
  override def resToValue(res: Res) = res.toValue(_.toValue)
}

object LockedAmuletExpireAmulet extends ExerciseNodeCompanion {
  override type Tpl = amuletCodegen.LockedAmulet
  override type Arg = amuletCodegen.LockedAmulet_ExpireAmulet
  override type Res = cc.amulet.AmuletExpireSummary

  override val template = amuletCodegen.LockedAmulet.COMPANION
  override val choice = amuletCodegen.LockedAmulet.CHOICE_LockedAmulet_ExpireAmulet

  override val argDecoder = amuletCodegen.LockedAmulet_ExpireAmulet.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = cc.amulet.AmuletExpireSummary.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object AmuletRules_BuyMemberTraffic extends ExerciseNodeCompanion {
  override type Tpl = cc.amuletrules.AmuletRules
  override type Arg = cc.amuletrules.AmuletRules_BuyMemberTraffic
  override type Res = cc.amuletrules.BuyMemberTrafficResult
  override val choice = cc.amuletrules.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic
  override val template = cc.amuletrules.AmuletRules.COMPANION
  override val argDecoder = cc.amuletrules.AmuletRules_BuyMemberTraffic.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = cc.amuletrules.BuyMemberTrafficResult.valueDecoder

  override def resToValue(res: Res): Value = res.toValue
}

object CnsRules_CollectInitialEntryPayment extends ExerciseNodeCompanion {
  override type Tpl = cnsCodegen.CnsRules
  override type Arg = cnsCodegen.CnsRules_CollectInitialEntryPayment
  override type Res = cnsCodegen.CnsRules_CollectInitialEntryPaymentResult
  override val choice = cnsCodegen.CnsRules.CHOICE_CnsRules_CollectInitialEntryPayment
  override val template = cnsCodegen.CnsRules.COMPANION
  override val argDecoder = cnsCodegen.CnsRules_CollectInitialEntryPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = cnsCodegen.CnsRules_CollectInitialEntryPaymentResult.valueDecoder

  override def resToValue(res: Res): Value = res.toValue
}

object CnsRules_CollectEntryRenewalPayment extends ExerciseNodeCompanion {
  override type Tpl = cnsCodegen.CnsRules
  override type Arg = cnsCodegen.CnsRules_CollectEntryRenewalPayment
  override type Res = cnsCodegen.CnsRules_CollectEntryRenewalPaymentResult
  override val choice = cnsCodegen.CnsRules.CHOICE_CnsRules_CollectEntryRenewalPayment
  override val template = cnsCodegen.CnsRules.COMPANION
  override val argDecoder = cnsCodegen.CnsRules_CollectEntryRenewalPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = cnsCodegen.CnsRules_CollectEntryRenewalPaymentResult.valueDecoder

  override def resToValue(res: Res): Value = res.toValue
}

object AmuletArchive {
  // Matches on any consuming exercise on a amulet
  def unapply(event: ExercisedEvent): Option[ExercisedEvent] =
    if (
      QualifiedName(event.getTemplateId) == QualifiedName(
        amuletCodegen.Amulet.COMPANION.TEMPLATE_ID
      ) && event.isConsuming
    ) {
      Some(event)
    } else None
}

object AmuletCreate {
  type TCid = amuletCodegen.Amulet.ContractId
  type T = amuletCodegen.Amulet
  type ContractType = Contract[TCid, T]
  val companion = amuletCodegen.Amulet.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object LockedAmuletCreate {
  type TCid = amuletCodegen.LockedAmulet.ContractId
  type T = amuletCodegen.LockedAmulet
  type ContractType = Contract[TCid, T]
  val companion = amuletCodegen.LockedAmulet.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object AmuletExpire extends ExerciseNodeCompanion {
  override type Tpl = amuletCodegen.Amulet
  override type Arg = amuletCodegen.Amulet_Expire
  override type Res = cc.amulet.AmuletExpireSummary

  override val template = amuletCodegen.Amulet.COMPANION
  override val choice = amuletCodegen.Amulet.CHOICE_Amulet_Expire

  override val argDecoder = amuletCodegen.Amulet_Expire.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = cc.amulet.AmuletExpireSummary.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

// TODO(#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
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

// TODO(#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
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

// TODO(#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
object AppRewardCreate {
  type TCid = amuletCodegen.AppRewardCoupon.ContractId
  type T = amuletCodegen.AppRewardCoupon
  type ContractType = Contract[TCid, T]
  val companion = amuletCodegen.AppRewardCoupon.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}
