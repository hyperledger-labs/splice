// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.history

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import com.digitalasset.canton.logging.ErrorLoggingContext
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  ans as ansCodegen,
  externalpartyamuletrules as externalPartyAmuletRulesCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ExerciseNode,
  ExerciseNodeCompanion,
  InterfaceExerciseNodeCompanion,
  QualifiedName,
}

case class Transfer(
    node: ExerciseNode[
      splice.amuletrules.AmuletRules_Transfer,
      splice.amuletrules.TransferResult,
    ]
)

object Transfer
    extends ExerciseNodeCompanion.Mk(
      template = splice.amuletrules.AmuletRules.COMPANION,
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Transfer,
    )

final case class CreateTokenStandardTransferInstruction(
    node: ExerciseNode[
      splice.api.token.transferinstructionv1.TransferFactory_Transfer,
      splice.api.token.transferinstructionv1.TransferInstructionResult,
    ]
)

final object CreateTokenStandardTransferInstruction
    extends InterfaceExerciseNodeCompanion.Mk(
      interface = splice.api.token.transferinstructionv1.TransferFactory.INTERFACE,
      template = splice.externalpartyamuletrules.ExternalPartyAmuletRules.COMPANION,
      choice =
        splice.api.token.transferinstructionv1.TransferFactory.CHOICE_TransferFactory_Transfer,
    ) {
  @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
  override def unapply(
      event: ExercisedEvent
  )(implicit lc: ErrorLoggingContext): Option[ExerciseNode[
    splice.api.token.transferinstructionv1.TransferFactory_Transfer,
    splice.api.token.transferinstructionv1.TransferInstructionResult,
  ]] = {
    super
      .unapply(event)
      .flatMap(node =>
        // We only parse transfer instructions. Direct transfers are just parsed as the underlying transfer.
        Option.when(
          node.result.value.output.isInstanceOf[
            splice.api.token.transferinstructionv1.transferinstructionresult_output.TransferInstructionResult_Pending
          ]
        )(node)
      )
  }
}

final case class TransferInstruction_Accept(
    node: ExerciseNode[
      splice.api.token.transferinstructionv1.TransferInstruction_Accept,
      splice.api.token.transferinstructionv1.TransferInstructionResult,
    ]
)

final object TransferInstruction_Accept
    extends InterfaceExerciseNodeCompanion.Mk(
      interface = splice.api.token.transferinstructionv1.TransferInstruction.INTERFACE,
      template = splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
      choice =
        splice.api.token.transferinstructionv1.TransferInstruction.CHOICE_TransferInstruction_Accept,
    )

final case class TransferInstruction_Reject(
    node: ExerciseNode[
      splice.api.token.transferinstructionv1.TransferInstruction_Reject,
      splice.api.token.transferinstructionv1.TransferInstructionResult,
    ]
)

final object TransferInstruction_Reject
    extends InterfaceExerciseNodeCompanion.Mk(
      interface = splice.api.token.transferinstructionv1.TransferInstruction.INTERFACE,
      template = splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
      choice =
        splice.api.token.transferinstructionv1.TransferInstruction.CHOICE_TransferInstruction_Reject,
    )

final case class TransferInstruction_Withdraw(
    node: ExerciseNode[
      splice.api.token.transferinstructionv1.TransferInstruction_Withdraw,
      splice.api.token.transferinstructionv1.TransferInstructionResult,
    ]
)

final object TransferInstruction_Withdraw
    extends InterfaceExerciseNodeCompanion.Mk(
      interface = splice.api.token.transferinstructionv1.TransferInstruction.INTERFACE,
      template = splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION,
      choice =
        splice.api.token.transferinstructionv1.TransferInstruction.CHOICE_TransferInstruction_Withdraw,
    )

case class Tap(
    node: ExerciseNode[splice.amuletrules.AmuletRules_DevNet_Tap, amuletCodegen.Amulet.ContractId]
)

object Tap
    extends ExerciseNodeCompanion.Mk(
      template = splice.amuletrules.AmuletRules.COMPANION,
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_DevNet_Tap,
    )

object Mint
    extends ExerciseNodeCompanion.Mk(
      template = splice.amuletrules.AmuletRules.COMPANION,
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_Mint,
    )

object LockedAmuletUnlock
    extends ExerciseNodeCompanion.Mk(
      template = splice.amulet.LockedAmulet.COMPANION,
      choice = splice.amulet.LockedAmulet.CHOICE_LockedAmulet_Unlock,
    )

object LockedAmuletOwnerExpireLock
    extends ExerciseNodeCompanion.Mk(
      template = splice.amulet.LockedAmulet.COMPANION,
      choice = splice.amulet.LockedAmulet.CHOICE_LockedAmulet_OwnerExpireLock,
    )

object LockedAmuletExpireAmulet
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.LockedAmulet.COMPANION,
      choice = amuletCodegen.LockedAmulet.CHOICE_LockedAmulet_ExpireAmulet,
    )

object AmuletRules_BuyMemberTraffic
    extends ExerciseNodeCompanion.Mk(
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_BuyMemberTraffic,
      template = splice.amuletrules.AmuletRules.COMPANION,
    )

object AmuletRules_CreateExternalPartySetupProposal
    extends ExerciseNodeCompanion.Mk(
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_CreateExternalPartySetupProposal,
      template = splice.amuletrules.AmuletRules.COMPANION,
    )

object AmuletRules_CreateTransferPreapproval
    extends ExerciseNodeCompanion.Mk(
      choice = splice.amuletrules.AmuletRules.CHOICE_AmuletRules_CreateTransferPreapproval,
      template = splice.amuletrules.AmuletRules.COMPANION,
    )

object TransferPreapproval_Renew
    extends ExerciseNodeCompanion.Mk(
      choice = splice.amuletrules.TransferPreapproval.CHOICE_TransferPreapproval_Renew,
      template = splice.amuletrules.TransferPreapproval.COMPANION,
    )

object TransferPreapproval_Send
    extends ExerciseNodeCompanion.Mk(
      choice = splice.amuletrules.TransferPreapproval.CHOICE_TransferPreapproval_Send,
      template = splice.amuletrules.TransferPreapproval.COMPANION,
    )

object AnsRules_CollectInitialEntryPayment
    extends ExerciseNodeCompanion.Mk(
      choice = ansCodegen.AnsRules.CHOICE_AnsRules_CollectInitialEntryPayment,
      template = ansCodegen.AnsRules.COMPANION,
    )

object AnsRules_CollectEntryRenewalPayment
    extends ExerciseNodeCompanion.Mk(
      choice = ansCodegen.AnsRules.CHOICE_AnsRules_CollectEntryRenewalPayment,
      template = ansCodegen.AnsRules.COMPANION,
    )

object ExternalPartyAmuletRules_CreateTransferCommand
    extends ExerciseNodeCompanion.Mk(
      choice =
        externalPartyAmuletRulesCodegen.ExternalPartyAmuletRules.CHOICE_ExternalPartyAmuletRules_CreateTransferCommand,
      template = externalPartyAmuletRulesCodegen.ExternalPartyAmuletRules.COMPANION,
    )

object TransferCommand_Send
    extends ExerciseNodeCompanion.Mk(
      choice = externalPartyAmuletRulesCodegen.TransferCommand.CHOICE_TransferCommand_Send,
      template = externalPartyAmuletRulesCodegen.TransferCommand.COMPANION,
    )

object TransferCommand_Withdraw
    extends ExerciseNodeCompanion.Mk(
      choice = externalPartyAmuletRulesCodegen.TransferCommand.CHOICE_TransferCommand_Withdraw,
      template = externalPartyAmuletRulesCodegen.TransferCommand.COMPANION,
    )

object TransferCommand_Expire
    extends ExerciseNodeCompanion.Mk(
      choice = externalPartyAmuletRulesCodegen.TransferCommand.CHOICE_TransferCommand_Expire,
      template = externalPartyAmuletRulesCodegen.TransferCommand.COMPANION,
    )

object AmuletArchive {
  // Matches on any consuming exercise on a amulet
  def unapply(event: ExercisedEvent): Option[ExercisedEvent] =
    if (
      QualifiedName(event.getTemplateId) == QualifiedName(
        amuletCodegen.Amulet.COMPANION.getTemplateIdWithPackageId
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

object TransferInstructionCreate {
  type TCid = splice.amulettransferinstruction.AmuletTransferInstruction.ContractId
  type T = splice.amulettransferinstruction.AmuletTransferInstruction
  type ContractType = Contract[TCid, T]
  val companion = splice.amulettransferinstruction.AmuletTransferInstruction.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

object AmuletExpire
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.Amulet.COMPANION,
      choice = amuletCodegen.Amulet.CHOICE_Amulet_Expire,
    )

// Note: We use the DevelopmentFundCoupon create event instead of AmuletRules_AllocateDevelopmentFundCoupon because
// the allocation choice is only visible to the fundManager, while the coupon creation
// is visible to all coupon stakeholders, allowing the beneficiary to track its lifecycle.
object DevelopmentFundCouponCreate {
  type TCid = amuletCodegen.DevelopmentFundCoupon.ContractId
  type T = amuletCodegen.DevelopmentFundCoupon
  type ContractType = Contract[TCid, T]
  val companion = amuletCodegen.DevelopmentFundCoupon.COMPANION

  def unapply(
      event: CreatedEvent
  ): Option[ContractType] = {
    Contract.fromCreatedEvent(companion)(event)
  }
}

case object DevelopmentFundCoupon_Withdraw
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.DevelopmentFundCoupon.COMPANION,
      choice = amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_Withdraw,
    )

case object DevelopmentFundCoupon_Expire
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.DevelopmentFundCoupon.COMPANION,
      choice = amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_DsoExpire,
    )

case object DevelopmentFundCoupon_Reject
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.DevelopmentFundCoupon.COMPANION,
      choice = amuletCodegen.DevelopmentFundCoupon.CHOICE_DevelopmentFundCoupon_Reject,
    )

// Note: We use the DevelopmentFundCoupon Archive exercise instead of AmuletRules_Transfer because
// AmuletRules_Transfer is not visible to the fundManager. The coupon Archive exercise is visible to
// coupon stakeholders (fundManager and beneficiary are observers), so both stores can derive the
// corresponding ArchivedTxLogEntry.
// This Archive event corresponds to the coupon being collected via AmuletRules_Transfer.
case object DevelopmentFundCoupon_Archive
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.DevelopmentFundCoupon.COMPANION,
      choice = amuletCodegen.DevelopmentFundCoupon.CHOICE_Archive,
    )

// TODO(DACH-NY/canton-network-node#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
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

// TODO(DACH-NY/canton-network-node#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
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

// TODO(DACH-NY/canton-network-node#2930): This is not really a Amulet event - consider either renaming the file, or splitting it into different ones based on event "types"
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
