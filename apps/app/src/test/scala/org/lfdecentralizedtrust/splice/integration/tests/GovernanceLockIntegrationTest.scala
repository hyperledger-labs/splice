// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{GovernanceLockUtil, WalletTestUtil}

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class GovernanceLockIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with GovernanceLockTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  private val lockKind = SuperValidatorLock
  private val lockSubject = "sv1"

  private val targetLockAmount = BigDecimal(20000)
  private val partialSubstitutionAmount = BigDecimal(10000)

  private def aliceParticipant(implicit env: SpliceTestConsoleEnvironment) =
    aliceValidatorBackend.participantClientWithAdminToken

  private def bobParticipant(implicit env: SpliceTestConsoleEnvironment) =
    bobValidatorBackend.participantClientWithAdminToken

  private def governanceLocksFor(participant: ParticipantClientReference, owner: PartyId) =
    participant.ledger_api_extensions.acs
      .filterJava(governancelock.GovernanceLock.COMPANION)(
        owner,
        predicate = c => c.data.owner == owner.toProtoPrimitive && !isVestingLock(c.data),
      )
      .map(_.data)

  private def vestingLocksFor(participant: ParticipantClientReference, owner: PartyId) =
    participant.ledger_api_extensions.acs
      .filterJava(governancelock.GovernanceLock.COMPANION)(
        owner,
        predicate = c => c.data.owner == owner.toProtoPrimitive && isVestingLock(c.data),
      )
      .map(_.data)

  // alice locks `targetLockAmount`, and bob has `bobFunds` of unlocked funds
  private def setup(bobFunds: BigDecimal)(implicit env: SpliceTestConsoleEnvironment) = {
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    val alice = RichPartyId.local(aliceParty)
    val bob = RichPartyId.local(bobParty)

    aliceWalletClient.tap(targetLockAmount)
    bobWalletClient.tap(bobFunds)

    val targetLockCid = createGovernanceLockTSv1(
      aliceParticipant,
      alice,
      lockKind,
      lockSubject,
      amount = targetLockAmount,
    )
    val targetLock = governanceLocksFor(aliceParticipant, aliceParty).loneElement

    (alice, bob, targetLockCid, targetLock)
  }

  private def checkSubstitutionView(
      participant: ParticipantClientReference,
      proposer: PartyId,
      substitutionCid: transferinstructionv1.TransferInstruction.ContractId,
      targetOwner: PartyId,
      amount: BigDecimal,
  ) = {
    val (_, view) = listTransferInstructions(participant, proposer)
      .filter(_._1.contractId == substitutionCid.contractId)
      .loneElement
    view.transfer.sender shouldBe proposer.toProtoPrimitive
    view.transfer.receiver shouldBe targetOwner.toProtoPrimitive
    BigDecimal(view.transfer.amount) shouldBe amount
  }

  "A GovernanceLock substitution created via TSv1 can be accepted by the owner of the target lock" in {
    implicit env =>
      val (alice, bob, _, targetLock) = setup(bobFunds = partialSubstitutionAmount)

      val substitutionCid = substituteGovernanceLockTSv1(targetLock)(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        partialSubstitutionAmount,
      )

      clue("the substitution is a pending TransferInstruction from bob to alice") {
        checkSubstitutionView(
          bobParticipant,
          bob.partyId,
          substitutionCid,
          alice.partyId,
          partialSubstitutionAmount,
        )
      }

      clue("Scan serves accept and withdraw choice contexts for the substitution") {
        sv1ScanBackend
          .getTransferInstructionAcceptContext(substitutionCid)
          .disclosedContracts should not be empty
        sv1ScanBackend
          .getTransferInstructionWithdrawContext(substitutionCid)
          .disclosedContracts should not be empty
      }

      val aliceUnlockedBefore = aliceWalletClient.balance().unlockedQty

      actAndCheck(
        "alice accepts the substitution",
        acceptTransferInstruction(aliceParticipant, alice, substitutionCid),
      )(
        "bob owns the substituted lock and alice keeps the remainder",
        _ => {
          // The substitution is gone; bob's only instruction is his new lock
          listTransferInstructions(bobParticipant, bob.partyId).map(
            _._2.transfer.receiver
          ) shouldBe Seq(lockManagerParty.toProtoPrimitive)

          val bobLock = governanceLocksFor(bobParticipant, bob.partyId).loneElement
          BigDecimal(bobLock.amount) shouldBe partialSubstitutionAmount
          GovernanceLockUtil.getGovernanceLockKind(bobLock) shouldBe
            GovernanceLockUtil.getGovernanceLockKind(targetLock)

          val aliceLock = governanceLocksFor(aliceParticipant, alice.partyId).loneElement
          BigDecimal(aliceLock.amount) shouldBe targetLockAmount - partialSubstitutionAmount

          aliceWalletClient
            .balance()
            .unlockedQty shouldBe aliceUnlockedBefore + partialSubstitutionAmount
        },
      )
  }

  "A GovernanceLock substitution created via TSv1 can be withdrawn by the proposer" in {
    implicit env =>
      val (alice, bob, _, targetLock) = setup(bobFunds = partialSubstitutionAmount)
      val bobUnlockedBefore = bobWalletClient.balance().unlockedQty

      val substitutionCid = substituteGovernanceLockTSv1(targetLock)(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        partialSubstitutionAmount,
      )

      clue("bob's funds are locked in the substitution") {
        bobWalletClient.balance().lockedQty shouldBe partialSubstitutionAmount
      }

      actAndCheck(
        "bob withdraws the substitution",
        withdrawTransferInstruction(bobParticipant, bob, substitutionCid),
      )(
        "the substitution is gone, bob's funds are unlocked and alice's lock is unchanged",
        _ => {
          listTransferInstructions(bobParticipant, bob.partyId) shouldBe empty
          bobWalletClient.balance().unlockedQty shouldBe bobUnlockedBefore
          governanceLocksFor(bobParticipant, bob.partyId) shouldBe empty
          BigDecimal(
            governanceLocksFor(aliceParticipant, alice.partyId).loneElement.amount
          ) shouldBe targetLockAmount
        },
      )
  }

  "A GovernanceLock substitution created via TSv1 can be rejected by the owner of the target lock" in {
    implicit env =>
      val (alice, bob, _, targetLock) = setup(bobFunds = partialSubstitutionAmount)
      val bobUnlockedBefore = bobWalletClient.balance().unlockedQty

      val substitutionCid = substituteGovernanceLockTSv1(targetLock)(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        partialSubstitutionAmount,
      )

      clue("Scan serves a reject choice context for the substitution") {
        sv1ScanBackend
          .getTransferInstructionRejectContext(substitutionCid)
          .disclosedContracts should not be empty
      }

      actAndCheck(
        "alice rejects the substitution",
        rejectTransferInstruction(aliceParticipant, alice, substitutionCid),
      )(
        "the substitution is gone, bob's funds are unlocked and alice's lock is unchanged",
        _ => {
          listTransferInstructions(bobParticipant, bob.partyId) shouldBe empty
          bobWalletClient.balance().unlockedQty shouldBe bobUnlockedBefore
          governanceLocksFor(bobParticipant, bob.partyId) shouldBe empty
          BigDecimal(
            governanceLocksFor(aliceParticipant, alice.partyId).loneElement.amount
          ) shouldBe targetLockAmount
        },
      )
  }

  "A vesting GovernanceLock substitution created via TSv1 can be accepted by the owner of the target lock" in {
    implicit env =>
      val (alice, bob, targetLockCid, _) = setup(bobFunds = targetLockAmount)

      // The default vesting period of SV locks is about a year, so the lock stays vesting for the
      // rest of the test.
      val (vestingLockCid, _) = unlockGovernanceLockTSv1(
        aliceParticipant,
        alice,
        lockKind,
        lockSubject,
        targetLockCid,
        unlockAmount = targetLockAmount,
        vestingStartTime = env.environment.clock.now.plusSeconds(30),
      ).value
      val vestingLock = vestingLocksFor(aliceParticipant, alice.partyId).loneElement

      val substitutionCid = substituteVestingLockTSv1(vestingLock)(
        bobParticipant,
        bob,
        lockKind,
        lockSubject,
        BigDecimal(vestingLock.amount),
      )

      clue("the substitution is a pending TransferInstruction from bob to alice") {
        substitutionCid.contractId should not be vestingLockCid.contractId
        checkSubstitutionView(
          bobParticipant,
          bob.partyId,
          substitutionCid,
          alice.partyId,
          BigDecimal(vestingLock.amount),
        )
      }

      actAndCheck(
        "alice accepts the substitution",
        acceptTransferInstruction(aliceParticipant, alice, substitutionCid),
      )(
        "bob owns a GovernanceLock with the same vesting schedule and alice has none",
        _ => {
          // The substitution is gone; bob's only instruction is his new lock
          listTransferInstructions(bobParticipant, bob.partyId).map(
            _._2.transfer.receiver
          ) shouldBe Seq(lockManagerParty.toProtoPrimitive)

          val bobLock = vestingLocksFor(bobParticipant, bob.partyId).loneElement
          BigDecimal(bobLock.amount) shouldBe BigDecimal(vestingLock.amount)
          vestingLockSpecification(bobLock).vestingPeriod shouldBe
            vestingLockSpecification(vestingLock).vestingPeriod
          vestingLockSpecification(bobLock).endTime shouldBe
            vestingLockSpecification(vestingLock).endTime
          GovernanceLockUtil.getGovernanceLockKind(bobLock) shouldBe
            GovernanceLockUtil.getGovernanceLockKind(vestingLock)

          vestingLocksFor(aliceParticipant, alice.partyId) shouldBe empty
        },
      )
  }
}
