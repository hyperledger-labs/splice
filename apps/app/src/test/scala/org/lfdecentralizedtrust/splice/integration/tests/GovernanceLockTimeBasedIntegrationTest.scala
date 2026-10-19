// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen, governancelock}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.sv.config.InitialGovernanceLockConfig
import org.lfdecentralizedtrust.splice.util.{
  GovernanceLockUtil,
  TimeTestUtil,
  TokenStandardMetadata,
  WalletTestUtil,
}

import java.time.Duration
import java.time.temporal.ChronoUnit

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_25
class GovernanceLockTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with GovernanceLockTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            // Shorten the SV vesting period so we can test partial and full withdrawal while only
            // advancing the clock a couple minutes
            initialGovernanceLockConfig = Some(
              InitialGovernanceLockConfig(
                superValidatorLockVestingDuration = Some(NonNegativeFiniteDuration.ofMinutes(2))
              )
            )
          )
        )(config)
      )

  private def matchSVKind(kind: governancelock.GovernanceLockKind): Unit =
    kind match {
      case _: governancelock.governancelockkind.GLK_SuperValidatorRightsOwner =>
      case other => fail(s"Expected GLK_SuperValidatorRightsOwner kind, found: $other")
    }

  "SV GovernanceLock created via TSv1 compatibility interface can be unlocked into a vesting GovernanceLock" in {
    implicit env =>
      val lockAmount = BigDecimal(10000)
      val lockKind = SuperValidatorLock
      val lockSubject = "sv1"

      // Setup alice as the lock owner
      val ownerParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val owner = RichPartyId.local(ownerParty)

      aliceWalletClient.tap(lockAmount)

      // Create the governance lock via a transfer to the magic party
      val governanceLockCid = createGovernanceLockTSv1(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        amount = lockAmount,
      )

      clue("the GovernanceLock has the correct kind") {
        val governanceLock =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(governancelock.GovernanceLock.COMPANION)(
              ownerParty,
              predicate = _.id.contractId == governanceLockCid.contractId,
            )
            .loneElement
            .data
        matchSVKind(GovernanceLockUtil.getGovernanceLockKind(governanceLock))
      }

      clue("the GovernanceLock is as a pending TransferInstruction to the magic party") {
        val (cid, view) = listTransferInstructions(
          aliceValidatorBackend.participantClientWithAdminToken,
          ownerParty,
        ).loneElement
        cid.contractId shouldBe governanceLockCid.contractId
        view.transfer.sender shouldBe ownerParty.toProtoPrimitive
        view.transfer.receiver shouldBe lockManagerParty.toProtoPrimitive
        BigDecimal(view.transfer.amount) shouldBe lockAmount
        compareLockMemo(
          view.transfer.meta.values.get(TokenStandardMetadata.reasonMetaKey),
          makeOutputLockMemo(lockKind, lockSubject, Locked),
        )
      }

      clue("Scan serves a withdraw choice context for the GovernanceLock") {
        sv1ScanBackend
          .getTransferInstructionWithdrawContext(governanceLockCid)
          .disclosedContracts should not be empty
      }

      // Advance sim-time past the lock's requestedAt time
      advanceTimeAndWaitForRoundOpening

      // Withdraw the governance lock; it's relocked as a vesting GovernanceLock and funds remain locked
      val vestingStartTime = getLedgerTime.plusSeconds(1)
      val (vestingLockCid, vestingLockView) = unlockGovernanceLockTSv1(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        governanceLockCid,
        unlockAmount = lockAmount,
        vestingStartTime = vestingStartTime,
      ).value
      val vestingLock =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(governancelock.GovernanceLock.COMPANION)(
            ownerParty,
            predicate = _.id.contractId == vestingLockCid.contractId,
          )
          .loneElement
          .data
      vestingLockCid.contractId should not be governanceLockCid.contractId
      vestingLockView.transfer.sender shouldBe ownerParty.toProtoPrimitive
      vestingLockView.transfer.receiver shouldBe lockManagerParty.toProtoPrimitive
      matchSVKind(GovernanceLockUtil.getGovernanceLockKind(vestingLock))
      val vestingSpec = vestingLockSpecification(vestingLock)
      vestingSpec.endTime.minus(vestingSpec.vestingPeriod.microseconds, ChronoUnit.MICROS) shouldBe
        vestingStartTime.toInstant

      clue("Scan serves a withdraw choice context for the vesting lock") {
        sv1ScanBackend
          .getTransferInstructionWithdrawContext(vestingLockCid)
          .disclosedContracts should not be empty
      }

      clue("the amulet remains locked while vesting") {
        aliceWalletClient.balance().lockedQty should beAround(lockAmount)
      }

      // Advance part of the way into the 2 minute vesting period and withdraw with an explicit
      // withdraw time of 30 seconds into the vesting period
      advanceTime(Duration.ofSeconds(31))

      val (_, (remainingVestingLock, remainingVestingAmount)) = actAndCheck(
        "the owner withdraws the vesting lock mid-vesting",
        withdrawVestingLockTSv1(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          vestingLock,
          vestedUntilTime = vestingStartTime.plusSeconds(30),
        ),
      )(
        "a new vesting lock remains with half of the total vesting amount",
        _ => {
          val (cid, view) = listTransferInstructions(
            aliceValidatorBackend.participantClientWithAdminToken,
            ownerParty,
          ).loneElement
          val remainingVestingLock =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(governancelock.GovernanceLock.COMPANION)(
                ownerParty,
                predicate = _.id.contractId == cid.contractId,
              )
              .loneElement
              .data
          cid.contractId should not be vestingLockCid.contractId
          view.transfer.sender shouldBe ownerParty.toProtoPrimitive
          view.transfer.receiver shouldBe lockManagerParty.toProtoPrimitive
          matchSVKind(GovernanceLockUtil.getGovernanceLockKind(remainingVestingLock))
          val remainingVestingAmount = BigDecimal(remainingVestingLock.amount)
          // The explicit withdraw time vests exactly 1/4 of the total lock amount
          val expectedLockAmount = lockAmount / 4 * 3
          remainingVestingAmount shouldBe expectedLockAmount
          aliceWalletClient.balance().lockedQty shouldBe expectedLockAmount
          (remainingVestingLock, remainingVestingAmount)
        },
      )

      // Advance past the end of the vesting period and withdraw with an explicit withdraw time of
      // the exact end of the vesting period
      advanceTime(Duration.ofSeconds(90))

      actAndCheck(
        "the owner withdraws the fully-vested lock",
        // The remaining GovernanceLock has the same end time as the original, but a new vesting
        // period computed from the previous withdraw time
        withdrawVestingLockTSv1(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          remainingVestingLock,
          vestedUntilTime = vestingStartTime.plusSeconds(120),
        ),
      )(
        "the lock is archived and no pending TransferInstruction remains",
        _ => {
          listTransferInstructions(
            aliceValidatorBackend.participantClientWithAdminToken,
            ownerParty,
          ) shouldBe empty
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(governancelock.GovernanceLock.COMPANION)(
              ownerParty,
              c => isVestingLock(c.data),
            ) shouldBe empty
        },
      )

      clue("the only LockedAmulet of the owner is the expired LockedAmulet of the vesting lock") {
        val now = getLedgerTime.toInstant
        val remainingLock =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.LockedAmulet.COMPANION)(ownerParty, _ => true)
            .loneElement
            .data
        remainingLock.lock.expiresAt.isAfter(now) shouldBe false
        BigDecimal(remainingLock.amulet.amount.initialAmount) should beAround(
          remainingVestingAmount
        )
      }
  }
}
