// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen, governancelock}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.ExpireVestingLockTrigger
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

  private val triggerBatchSize = 2
  private val vestingDuration = Duration.ofMinutes(2)

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            // Shorten the SV vesting period so we can test partial and full withdrawal while only
            // advancing the clock a couple minutes. We do this so the test logic can run within a
            // single round, avoiding potential flakes caused by test logic racing against round
            // automation working through its backlog. See relevant issues: #7223, #7206
            initialGovernanceLockConfig = Some(
              InitialGovernanceLockConfig(
                superValidatorLockVestingDuration =
                  Some(NonNegativeFiniteDuration.tryFromJavaDuration(vestingDuration))
              )
            )
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            delegatelessAutomationExpiredVestingLockBatchSize = triggerBatchSize
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
          _.withPausedTrigger[ExpireVestingLockTrigger]
        )(config)
      )

  private val lockAmount = BigDecimal(10000)
  private val lockKind = SuperValidatorLock
  private val lockSubject = "sv1"

  private def matchSVKind(kind: governancelock.GovernanceLockKind): Unit =
    kind match {
      case _: governancelock.governancelockkind.GLK_SuperValidatorRightsOwner =>
      case other => fail(s"Expected GLK_SuperValidatorRightsOwner kind, found: $other")
    }

  "SV GovernanceLock created via TSv1 compatibility interface can be unlocked into a vesting GovernanceLock" in {
    implicit env =>
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

  "ExpireVestingLockTrigger archives fully vested VestingLocks in batches" in { implicit env =>
    val numLocks = 3
    val trigger = sv1Backend.dsoDelegateBasedAutomation.trigger[ExpireVestingLockTrigger]

    val sv1Party = sv1Backend.getDsoInfo().svParty
    val owner = RichPartyId.local(sv1Party)
    val participant = sv1Backend.participantClientWithAdminToken

    val vestingStartTime = getLedgerTime.plusSeconds(1)
    val endTime = vestingStartTime.toInstant.plus(vestingDuration)

    // `createGovernanceLockTSv1` feeds all of the owner's unlocked
    // holdings into the `TransferFactory_Transfer` choice. Thus, the tap has to
    // have landed before the first lock is submitted.
    actAndCheck(
      s"Tap ${numLocks * minimumLockAmount} CC for sv1",
      sv1WalletClient.tap(walletAmuletToUsd(numLocks * minimumLockAmount)),
    )(
      "the tapped amulet is visible",
      _ => listHoldings(participant, sv1Party).filter(_._2.lock.isEmpty) should have length 1,
    )

    actAndCheck(
      s"Lock and unlock $numLocks times, vesting until $endTime", {
        (1 to numLocks).map { _ =>
          val governanceLock = createGovernanceLockTSv1(
            participant,
            owner,
            lockKind,
            lockSubject = lockSubject,
            amount = lockAmount,
          )
          unlockGovernanceLockTSv1(
            participant,
            owner,
            lockKind,
            lockSubject,
            governanceLock,
            unlockAmount = lockAmount,
            vestingStartTime = vestingStartTime,
          )
        }
      },
    )(
      "SvDsoStore ingests all VestingLocks",
      _ => listVestingLocks should have length numLocks.toLong,
    )

    clue("The locks are not yet expired, so the trigger has no work") {
      trigger.runOnce().futureValue shouldBe false
      listVestingLocks should have length numLocks.toLong
    }

    // `listExpiredFromPayloadExpiry` uses a strict `expires_at < now`, and the trigger reads `now`
    // 5s behind the clock (`clockSkewAutomationDelay`), so step well past `endTime`.
    advanceTime(vestingDuration.plusSeconds(10))

    clue("The locks stay put while the trigger is paused") {
      listVestingLocks should have length numLocks.toLong
    }

    clue(s"First run archives one full batch of $triggerBatchSize") {
      eventually()(trigger.runOnce().futureValue shouldBe true)
      listVestingLocks should have length (numLocks - triggerBatchSize).toLong
    }

    clue("Second run archives the remaining partial batch") {
      trigger.runOnce().futureValue shouldBe true
      listVestingLocks shouldBe empty
    }
  }

  private def listVestingLocks(implicit env: SpliceTestConsoleEnvironment) =
    sv1Backend.appState.dsoStore.multiDomainAcsStore
      .listContracts(governancelock.GovernanceLock.COMPANION)
      .futureValue
      .filter(lock => GovernanceLockUtil.getVestingLockSpecification(lock.payload).isDefined)
}
