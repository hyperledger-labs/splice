package com.daml.network.history

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Value}
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.codegen.java.splice.round.{ClosedMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.splice.ans as ansCodegen
import com.daml.network.util.{Contract, ExerciseNode, ExerciseNodeCompanion, QualifiedName}

case class Transfer(
    node: ExerciseNode[
      splice.amuletrules.AmuletRules_Transfer,
      splice.amuletrules.TransferResult,
    ]
)

object Transfer extends ExerciseNodeCompanion {
  override type Tpl = splice.amuletrules.AmuletRules
  override type Arg = splice.amuletrules.AmuletRules_Transfer
  override type Res = splice.amuletrules.TransferResult

  override val template = splice.amuletrules.AmuletRules.COMPANION
  override val choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Transfer

  override val argDecoder = splice.amuletrules.AmuletRules_Transfer.valueDecoder()
  override def argToValue(arg: splice.amuletrules.AmuletRules_Transfer) = arg.toValue

  override val resDecoder = splice.amuletrules.TransferResult.valueDecoder
  override def resToValue(res: splice.amuletrules.TransferResult) = res.toValue
}

case class Tap(
    node: ExerciseNode[splice.amuletrules.AmuletRules_DevNet_Tap, amuletCodegen.Amulet.ContractId]
)

object Tap extends ExerciseNodeCompanion {
  override type Tpl = splice.amuletrules.AmuletRules
  override type Arg = splice.amuletrules.AmuletRules_DevNet_Tap
  override type Res = splice.amuletrules.AmuletRules_DevNet_TapResult

  override val template = splice.amuletrules.AmuletRules.COMPANION
  override val choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_DevNet_Tap

  override val argDecoder = splice.amuletrules.AmuletRules_DevNet_Tap.valueDecoder()
  override def argToValue(arg: splice.amuletrules.AmuletRules_DevNet_Tap) = arg.toValue

  override val resDecoder = splice.amuletrules.AmuletRules_DevNet_TapResult.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object Mint extends ExerciseNodeCompanion {
  override type Tpl = splice.amuletrules.AmuletRules
  override type Arg = splice.amuletrules.AmuletRules_Mint
  override type Res = splice.amuletrules.AmuletRules_MintResult

  override val template = splice.amuletrules.AmuletRules.COMPANION
  override val choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Mint

  override val argDecoder = splice.amuletrules.AmuletRules_Mint.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = splice.amuletrules.AmuletRules_MintResult.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object LockedAmuletUnlock extends ExerciseNodeCompanion {
  override type Tpl = splice.amulet.LockedAmulet
  override type Arg = splice.amulet.LockedAmulet_Unlock
  override type Res = splice.amulet.LockedAmulet_UnlockResult

  override val template = splice.amulet.LockedAmulet.COMPANION
  override val choice = splice.amulet.LockedAmulet.CHOICE_LockedAmulet_Unlock

  override val argDecoder = splice.amulet.LockedAmulet_Unlock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = splice.amulet.LockedAmulet_UnlockResult.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object LockedAmuletOwnerExpireLock extends ExerciseNodeCompanion {
  override type Tpl = splice.amulet.LockedAmulet
  override type Arg = splice.amulet.LockedAmulet_OwnerExpireLock
  override type Res = splice.amulet.LockedAmulet_OwnerExpireLockResult

  override val template = splice.amulet.LockedAmulet.COMPANION
  override val choice = splice.amulet.LockedAmulet.CHOICE_LockedAmulet_OwnerExpireLock

  override val argDecoder = splice.amulet.LockedAmulet_OwnerExpireLock.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = splice.amulet.LockedAmulet_OwnerExpireLockResult.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object LockedAmuletExpireAmulet extends ExerciseNodeCompanion {
  override type Tpl = amuletCodegen.LockedAmulet
  override type Arg = amuletCodegen.LockedAmulet_ExpireAmulet
  override type Res = amuletCodegen.LockedAmulet_ExpireAmuletResult

  override val template = amuletCodegen.LockedAmulet.COMPANION
  override val choice = amuletCodegen.LockedAmulet.CHOICE_LockedAmulet_ExpireAmulet

  override val argDecoder = amuletCodegen.LockedAmulet_ExpireAmulet.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = amuletCodegen.LockedAmulet_ExpireAmuletResult.valueDecoder()
  override def resToValue(res: Res) = res.toValue
}

object AmuletRules_BuyMemberTraffic extends ExerciseNodeCompanion {
  override type Tpl = splice.amuletrules.AmuletRules
  override type Arg = splice.amuletrules.AmuletRules_BuyMemberTraffic
  override type Res = splice.amuletrules.AmuletRules_BuyMemberTrafficResult
  override val choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic
  override val template = splice.amuletrules.AmuletRules.COMPANION
  override val argDecoder = splice.amuletrules.AmuletRules_BuyMemberTraffic.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = splice.amuletrules.AmuletRules_BuyMemberTrafficResult.valueDecoder

  override def resToValue(res: Res): Value = res.toValue
}

object AnsRules_CollectInitialEntryPayment extends ExerciseNodeCompanion {
  override type Tpl = ansCodegen.AnsRules
  override type Arg = ansCodegen.AnsRules_CollectInitialEntryPayment
  override type Res = ansCodegen.AnsRules_CollectInitialEntryPaymentResult
  override val choice = ansCodegen.AnsRules.CHOICE_AnsRules_CollectInitialEntryPayment
  override val template = ansCodegen.AnsRules.COMPANION
  override val argDecoder = ansCodegen.AnsRules_CollectInitialEntryPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = ansCodegen.AnsRules_CollectInitialEntryPaymentResult.valueDecoder

  override def resToValue(res: Res): Value = res.toValue
}

object AnsRules_CollectEntryRenewalPayment extends ExerciseNodeCompanion {
  override type Tpl = ansCodegen.AnsRules
  override type Arg = ansCodegen.AnsRules_CollectEntryRenewalPayment
  override type Res = ansCodegen.AnsRules_CollectEntryRenewalPaymentResult
  override val choice = ansCodegen.AnsRules.CHOICE_AnsRules_CollectEntryRenewalPayment
  override val template = ansCodegen.AnsRules.COMPANION
  override val argDecoder = ansCodegen.AnsRules_CollectEntryRenewalPayment.valueDecoder()

  override def argToValue(arg: Arg): Value = arg.toValue

  override val resDecoder = ansCodegen.AnsRules_CollectEntryRenewalPaymentResult.valueDecoder

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
  override type Res = amuletCodegen.Amulet_ExpireResult

  override val template = amuletCodegen.Amulet.COMPANION
  override val choice = amuletCodegen.Amulet.CHOICE_Amulet_Expire

  override val argDecoder = amuletCodegen.Amulet_Expire.valueDecoder()
  override def argToValue(arg: Arg) = arg.toValue

  override val resDecoder = splice.amulet.Amulet_ExpireResult.valueDecoder()
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
