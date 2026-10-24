package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.GovernanceLockUtil
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import scala.jdk.OptionConverters.*

trait GovernanceLockTest extends TokenStandardTest {
  private val lockCipPrefix = "cip-127"
  private val lockMemoPrefix = s"$lockCipPrefix/memo:"

  // Same as `defaultGovernanceLockMinimumLockAmount` in Daml.
  protected val minimumLockAmount = BigDecimal(10000.0)

  val lockManagerParty = PartyId.tryFromProtoPrimitive(
    s"${lockCipPrefix}_lock-manager::1220000000000000000000000000000000000000000000000000000000000000abcd"
  )

  private val lockStatusMetaKey = "lock-status"
  private val lockVestingDurationMetaKey = "lock-vesting-duration-micros"
  private val lockVestingEndTimeMetaKey = "lock-vesting-end-time-micros"

  private def instantToMicros(i: Instant): Long = ChronoUnit.MICROS.between(Instant.EPOCH, i)

  private def formatTimestamp(t: CantonTimestamp): String =
    DateTimeFormatter.ISO_INSTANT.format(t.toInstant.truncatedTo(ChronoUnit.SECONDS))

  sealed abstract class LockKind(val kind: String)
  case object SuperValidatorLock extends LockKind("sv-lock")
  case object ProvisionalFeaturedAppLock extends LockKind("provisional-fa-lock")

  sealed abstract class LockStatus(val status: String)
  case object Locked extends LockStatus("locked")
  case object Vesting extends LockStatus("vesting")

  sealed abstract class LockRequest[A](
      val request: String,
      val memoEntries: A => List[(String, String)],
  )
  case object CreateLock extends LockRequest[Unit]("create-lock", _ => Nil)
  case object UnlockAndStartVesting
      extends LockRequest[(BigDecimal, CantonTimestamp)](
        "unlock-and-start-vesting",
        { case (unlockAmount, vestingStartTime) =>
          List(
            "unlock-amount" -> unlockAmount.toString,
            "vesting-start-time" -> formatTimestamp(vestingStartTime),
          )
        },
      )
  case object WithdrawVestedFunds
      extends LockRequest[(CantonTimestamp, RelTime, Instant)](
        "withdraw-vested-funds",
        { case (vestedUntilTime, vestingPeriod, vestingEndTime) =>
          List(
            "vested-until-time" -> formatTimestamp(vestedUntilTime),
            lockVestingDurationMetaKey -> vestingPeriod.microseconds.toString,
            lockVestingEndTimeMetaKey -> instantToMicros(vestingEndTime).toString,
          )
        },
      )

  type SubstituteLockData = (LockStatus, PartyId, Option[RelTime], Option[Instant])

  case object SubstituteLock
      extends LockRequest[SubstituteLockData](
        "substitute-lock",
        { case (lockStatus, targetLockOwner, vestingPeriod, vestingEndTime) =>
          List(
            lockStatusMetaKey -> lockStatus.status,
            "target-lock-owner" -> targetLockOwner.toProtoPrimitive,
          ) ++
            vestingPeriod.map(t => (lockVestingDurationMetaKey, t.microseconds.toString)) ++
            vestingEndTime.map(t => (lockVestingEndTimeMetaKey, instantToMicros(t).toString))
        },
      )

  private def makeLockMemo(entries: List[(String, String)]): String =
    lockMemoPrefix + entries.map { case (k, v) => s"$k=$v" }.mkString("&")

  private def commonLockMemoEntries(
      lockKind: LockKind,
      lockSubject: String,
  ): List[(String, String)] = List("lock-kind" -> lockKind.kind, "lock-subject" -> lockSubject)

  def makeInputLockMemo[A](
      lockRequest: LockRequest[A],
      lockKind: LockKind,
      lockSubject: String,
  )(data: A): String =
    makeLockMemo(
      List("request" -> lockRequest.request) ++
        commonLockMemoEntries(lockKind, lockSubject) ++
        lockRequest.memoEntries(data)
    )

  def makeOutputLockMemo(
      lockKind: LockKind,
      lockSubject: String,
      lockStatus: LockStatus,
  ): String =
    makeLockMemo(
      commonLockMemoEntries(lockKind, lockSubject) ++
        Map(lockStatusMetaKey -> lockStatus.status)
    )

  // Using a Map so order doesn't matter when comparing below
  private def extractLockMemoEntries(memo: String): Map[String, String] =
    memo
      .stripPrefix(lockMemoPrefix)
      .split("&")
      .flatMap(_.split("=", 2) match {
        case Array(k, v) => Some((k, v))
        case _ => None
      })
      .toMap

  def isVestingLock(lock: governancelock.GovernanceLock): Boolean =
    GovernanceLockUtil.getVestingLockSpecification(lock).isDefined

  def vestingLockSpecification(
      lock: governancelock.GovernanceLock
  ): governancelock.VestingLockSpecification =
    GovernanceLockUtil.getVestingLockSpecification(lock).value

  def compareLockMemo(actual: String, expected: String) = {
    val actualEntries = extractLockMemoEntries(actual)
    val expectedEntries = extractLockMemoEntries(expected)

    actualEntries shouldBe expectedEntries withClue s"actual: $actual, expected: $expected"
  }

  def createGovernanceLockTSv1(
      participant: ParticipantClientReference,
      owner: RichPartyId,
      lockKind: LockKind,
      lockSubject: String,
      amount: BigDecimal,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): transferinstructionv1.TransferInstruction.ContractId = {
    // `owner` may already have pending instructions to `lockManagerParty` (earlier
    // `GovernanceLock`s), so only look at the new one.
    val existingInstructionCids =
      listTransferInstructions(participant, owner.partyId).map(_._1).toSet

    actAndCheck(
      "the owner creates the GovernanceLock",
      executeTransferViaTokenStandard(
        participant,
        owner,
        lockManagerParty,
        amount,
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
        description = Some(makeInputLockMemo(CreateLock, lockKind, lockSubject)(())),
      ),
    )(
      "a GovernanceLock is now the pending TransferInstruction",
      _ =>
        listTransferInstructions(participant, owner.partyId).collect {
          case (cid, view)
              if view.transfer.receiver == lockManagerParty.toProtoPrimitive &&
                !existingInstructionCids.contains(cid) =>
            cid
        }.loneElement,
    )._2
  }

  /** Unlocks a governance lock by submitting an unlock request */
  def unlockGovernanceLockTSv1(
      participant: ParticipantClientReference,
      owner: RichPartyId,
      lockKind: LockKind,
      lockSubject: String,
      governanceLockCid: transferinstructionv1.TransferInstruction.ContractId,
      unlockAmount: BigDecimal,
      vestingStartTime: CantonTimestamp,
  )(implicit env: SpliceTestConsoleEnvironment): Option[
    (
        transferinstructionv1.TransferInstruction.ContractId,
        transferinstructionv1.TransferInstructionView,
    )
  ] =
    actAndCheck(
      "the owner unlocks the GovernanceLock",
      executeTransferViaTokenStandard(
        participant,
        owner,
        lockManagerParty,
        0,
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
        description = Some(
          makeInputLockMemo(UnlockAndStartVesting, lockKind, lockSubject)(
            (unlockAmount, vestingStartTime)
          )
        ),
        wait = false,
      ),
    )(
      "a GovernanceLock is now the pending TransferInstruction",
      _ =>
        listTransferInstructions(participant, owner.partyId).collect {
          case t @ (_, view)
              if view.originalInstructionCid.toScala.exists(
                _.contractId == governanceLockCid.contractId
              ) =>
            t
        } match {
          case Seq() => None
          case Seq(vestingLockCid) => Some(vestingLockCid)
          case many =>
            fail(s"Expected at most one GovernanceLock for $governanceLockCid, got $many")
        },
    )._2

  /** Withdraws the vested funds of a vesting lock by submitting a withdraw request */
  def withdrawVestingLockTSv1(
      participant: ParticipantClientReference,
      owner: RichPartyId,
      lockKind: LockKind,
      lockSubject: String,
      vestingLock: governancelock.GovernanceLock,
      vestedUntilTime: CantonTimestamp,
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    executeTransferViaTokenStandard(
      participant,
      owner,
      lockManagerParty,
      0,
      transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
      description = Some(
        makeInputLockMemo(WithdrawVestedFunds, lockKind, lockSubject)(
          (
            vestedUntilTime,
            vestingLockSpecification(vestingLock).vestingPeriod,
            vestingLockSpecification(vestingLock).endTime,
          )
        )
      ),
      wait = false,
    )

  final class SubstituteLockTSv1(data: SubstituteLockData) {
    final def apply(
        participant: ParticipantClientReference,
        owner: RichPartyId,
        lockKind: LockKind,
        lockSubject: String,
        substitutionAmount: BigDecimal,
    )(implicit
        env: SpliceTestConsoleEnvironment
    ): transferinstructionv1.TransferInstruction.ContractId = {
      val (_, targetLockOwner, _, _) = data
      // `owner` may already have pending instructions, so only look at the new one.
      val existingInstructionCids =
        listTransferInstructions(participant, owner.partyId).map(_._1).toSet

      actAndCheck(
        "the owner proposes the substitution",
        executeTransferViaTokenStandard(
          participant,
          owner,
          lockManagerParty,
          substitutionAmount,
          transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
          description = Some(makeInputLockMemo(SubstituteLock, lockKind, lockSubject)(data)),
          // A substitution proposed by the owner of the target lock locks no funds,
          // so no input holding is archived
          wait = owner.partyId != targetLockOwner,
        ),
      )(
        "the substitution is now a pending TransferInstruction to the owner of the target lock",
        _ =>
          listTransferInstructions(participant, owner.partyId).collect {
            case (cid, view)
                if view.transfer.receiver == targetLockOwner.toProtoPrimitive &&
                  !existingInstructionCids.contains(cid) =>
              cid
          }.loneElement,
      )._2
    }
  }

  /** Propose a substitution of a GovernanceLock */
  def substituteGovernanceLockTSv1(lock: governancelock.GovernanceLock) =
    new SubstituteLockTSv1((Locked, PartyId.tryFromProtoPrimitive(lock.owner), None, None))

  /** Propose a substitution of a vesting GovernanceLock */
  def substituteVestingLockTSv1(lock: governancelock.GovernanceLock) =
    new SubstituteLockTSv1(
      (
        Vesting,
        PartyId.tryFromProtoPrimitive(lock.owner),
        Some(vestingLockSpecification(lock).vestingPeriod),
        Some(vestingLockSpecification(lock).endTime),
      )
    )
}
