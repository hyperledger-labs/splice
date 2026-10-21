// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.codegen.ContractId
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
import org.scalatest.Assertion

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

  private val lockAmount = BigDecimal(10000)
  private val expectedRemainingLockAmount = lockAmount / 4 * 3
  private val lockKind = SuperValidatorLock
  private val lockSubject = "sv1"

  private def checkLockKind(kind: governancelock.GovernanceLockKind): Assertion =
    kind shouldBe a[governancelock.governancelockkind.GLK_SuperValidatorRightsOwner]

  private def testTokenStandardCompat[CId <: ContractId[?], View](
      ops: TokenStandardOps[CId, View]
  ) =
    s"GovernanceLock TSv${ops.version} compatibility" in { implicit env =>
      // Setup alice as the lock owner
      val ownerParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val owner = RichPartyId.local(ownerParty)

      aliceWalletClient.tap(lockAmount)

      // Create the governance lock via a transfer to the magic party
      val governanceLockCid = ops.createGovernanceLock(
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
        checkLockKind(GovernanceLockUtil.getGovernanceLockKind(governanceLock))
      }

      clue("the GovernanceLock is as a pending TransferInstruction to the magic party") {
        val (cid, view) = ops
          .listTransfers(
            aliceValidatorBackend.participantClientWithAdminToken,
            ownerParty,
          )
          .loneElement
        cid.contractId shouldBe governanceLockCid.contractId
        ops.transferValues.sender(view) shouldBe ownerParty.toProtoPrimitive
        ops.transferValues.receiver(view) shouldBe lockManagerParty.toProtoPrimitive
        ops.transferValues.amount(view) shouldBe lockAmount
        compareLockMemo(
          ops.transferValues.meta(view).values.get(TokenStandardMetadata.reasonMetaKey),
          makeOutputLockMemo(lockKind, lockSubject, Locked),
        )
      }

      clue("Scan serves a withdraw choice context for the GovernanceLock") {
        ops
          .getWithdrawContext(governanceLockCid)
          .disclosedContracts should not be empty
      }

      // Advance sim-time past the lock's requestedAt time
      advanceTimeAndWaitForRoundOpening

      // Withdraw the governance lock; it's relocked as a vesting GovernanceLock and funds remain locked
      val vestingStartTime = getLedgerTime.plusSeconds(1)
      val (vestingLockCid, vestingLockView) = ops
        .unlockGovernanceLock(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          unlockAmount = lockAmount,
          vestingStartTime = vestingStartTime,
        )
        .value
      val vestingLock =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(governancelock.GovernanceLock.COMPANION)(
            ownerParty,
            predicate = _.id.contractId == vestingLockCid.contractId,
          )
          .loneElement
          .data
      vestingLockCid.contractId should not be governanceLockCid.contractId
      ops.transferValues.sender(vestingLockView) shouldBe ownerParty.toProtoPrimitive
      ops.transferValues.receiver(vestingLockView) shouldBe lockManagerParty.toProtoPrimitive
      ops.transferValues.amount(vestingLockView) shouldBe lockAmount
      checkLockKind(GovernanceLockUtil.getGovernanceLockKind(vestingLock))
      val vestingSpec = vestingLockSpecification(vestingLock)
      vestingSpec.endTime.minus(vestingSpec.vestingPeriod.microseconds, ChronoUnit.MICROS) shouldBe
        vestingStartTime.toInstant

      clue("Scan serves a withdraw choice context for the vesting lock") {
        ops
          .getWithdrawContext(vestingLockCid)
          .disclosedContracts should not be empty
      }

      clue("the amulet remains locked while vesting") {
        aliceWalletClient.balance().lockedQty shouldBe lockAmount
      }

      // Advance part of the way into the 2 minute vesting period and withdraw with an explicit
      // withdraw time of 30 seconds into the vesting period
      advanceTime(Duration.ofSeconds(31))

      val (remainingVestingLockCid, remainingVestingLockView) = ops
        .withdrawVestingLock(
          aliceValidatorBackend.participantClientWithAdminToken,
          owner,
          lockKind,
          lockSubject,
          vestingLock,
          vestedUntilTime = vestingStartTime.plusSeconds(30),
        )
        .value
      val remainingVestingLock =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(governancelock.GovernanceLock.COMPANION)(
            ownerParty,
            predicate = _.id.contractId == remainingVestingLockCid.contractId,
          )
          .loneElement
          .data
      remainingVestingLockCid.contractId should not be vestingLockCid.contractId
      ops.transferValues.sender(remainingVestingLockView) shouldBe ownerParty.toProtoPrimitive
      ops.transferValues.receiver(
        remainingVestingLockView
      ) shouldBe lockManagerParty.toProtoPrimitive
      ops.transferValues.amount(remainingVestingLockView) shouldBe expectedRemainingLockAmount
      checkLockKind(GovernanceLockUtil.getGovernanceLockKind(remainingVestingLock))
      val remainingVestingAmount = BigDecimal(remainingVestingLock.amount)
      remainingVestingAmount shouldBe expectedRemainingLockAmount
      aliceWalletClient.balance().lockedQty shouldBe expectedRemainingLockAmount

      // Advance past the end of the vesting period and withdraw with an explicit withdraw time of
      // the exact end of the vesting period
      advanceTime(Duration.ofSeconds(90))

      ops.withdrawVestingLock(
        aliceValidatorBackend.participantClientWithAdminToken,
        owner,
        lockKind,
        lockSubject,
        remainingVestingLock,
        vestedUntilTime = vestingStartTime.plusSeconds(120),
      )
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(governancelock.GovernanceLock.COMPANION)(
          ownerParty,
          c => isVestingLock(c.data),
        ) shouldBe empty

      clue("the only LockedAmulet of the owner is the expired LockedAmulet of the vesting lock") {
        val now = getLedgerTime.toInstant
        val remainingLock =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.LockedAmulet.COMPANION)(ownerParty, _ => true)
            .loneElement
            .data
        remainingLock.lock.expiresAt.isAfter(now) shouldBe false
        BigDecimal(remainingLock.amulet.amount.initialAmount) shouldBe remainingVestingAmount
      }
    }

  testTokenStandardCompat(tsv1Operations)
  testTokenStandardCompat(tsv2Operations)
}
