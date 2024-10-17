// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.history

import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  OpenMiningRound,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ExerciseNode,
  ExerciseNodeCompanion,
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

object AmuletExpire
    extends ExerciseNodeCompanion.Mk(
      template = amuletCodegen.Amulet.COMPANION,
      choice = amuletCodegen.Amulet.CHOICE_Amulet_Expire,
    )

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
