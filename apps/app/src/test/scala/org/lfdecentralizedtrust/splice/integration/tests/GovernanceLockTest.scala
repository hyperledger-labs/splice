package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  metadatav1,
  transferinstructionv1,
  transferinstructionv2,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.governancelock
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{ChoiceContextWithDisclosures, GovernanceLockUtil}
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

  type TransferLockData = (LockStatus, PartyId, BigDecimal, Option[RelTime], Option[Instant])

  case object TransferLock
      extends LockRequest[TransferLockData](
        "transfer-lock",
        { case (lockStatus, newLockOwner, transferAmount, vestingPeriod, vestingEndTime) =>
          List(
            lockStatusMetaKey -> lockStatus.status,
            "new-lock-owner" -> newLockOwner.toProtoPrimitive,
            "transfer-amount" -> transferAmount.toString,
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

  trait TransferValues[T] {
    def sender(transfer: T): String
    def receiver(transfer: T): String
    def amount(transfer: T): BigDecimal
    def meta(transfer: T): metadatav1.Metadata
  }

  object TransferValues {
    implicit val transferValuesV1: TransferValues[transferinstructionv1.TransferInstructionView] =
      new TransferValues[transferinstructionv1.TransferInstructionView] {
        def sender(transfer: transferinstructionv1.TransferInstructionView): String =
          transfer.transfer.sender
        def receiver(transfer: transferinstructionv1.TransferInstructionView): String =
          transfer.transfer.receiver
        def amount(transfer: transferinstructionv1.TransferInstructionView): BigDecimal =
          BigDecimal(transfer.transfer.amount)
        def meta(transfer: transferinstructionv1.TransferInstructionView): metadatav1.Metadata =
          transfer.transfer.meta
      }

    implicit val transferValuesV2: TransferValues[transferinstructionv2.TransferInstructionView] =
      new TransferValues[transferinstructionv2.TransferInstructionView] {
        def sender(transfer: transferinstructionv2.TransferInstructionView): String =
          transfer.transfer.sender.owner.toScala.value
        def receiver(transfer: transferinstructionv2.TransferInstructionView): String =
          transfer.transfer.receiver.owner.toScala.value
        def amount(transfer: transferinstructionv2.TransferInstructionView): BigDecimal =
          BigDecimal(transfer.transfer.amount)
        def meta(transfer: transferinstructionv2.TransferInstructionView): metadatav1.Metadata =
          transfer.transfer.meta
      }
  }

  sealed abstract class TokenStandardOps[CId, View](val version: Int)(implicit
      val transferValues: TransferValues[View]
  ) {
    def executeTransfer(
        participant: ParticipantClientReference,
        sender: RichPartyId,
        receiver: PartyId,
        amount: BigDecimal,
        description: Option[String],
        wait: Boolean = true,
    )(implicit env: SpliceTestConsoleEnvironment): Unit

    def acceptTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: CId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit

    def rejectTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: CId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit

    def withdrawTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: CId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit

    def listTransfers(
        participant: ParticipantClientReference,
        party: PartyId,
    ): Seq[(CId, View)]

    def getAcceptContext(cid: CId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures

    def getRejectContext(cid: CId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures

    def getWithdrawContext(cid: CId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures

    private def defaultIsValid(receiver: String, existingCids: Set[CId]): (CId, View) => Boolean =
      (cid, view) => transferValues.receiver(view) == receiver && !existingCids.contains(cid)

    private def transferAndList(
        actClue: String,
        checkClue: String,
        participant: ParticipantClientReference,
        owner: RichPartyId,
        amount: BigDecimal,
        description: String,
        wait: Boolean,
        isValid: (CId, View) => Boolean,
    )(implicit env: SpliceTestConsoleEnvironment): Seq[(CId, View)] =
      actAndCheck(
        actClue,
        executeTransfer(
          participant,
          owner,
          lockManagerParty,
          amount,
          description = Some(description),
          wait = wait,
        ),
      )(
        checkClue,
        _ => listTransfers(participant, owner.partyId).filter(isValid.tupled),
      )._2

    final def createGovernanceLock(
        participant: ParticipantClientReference,
        owner: RichPartyId,
        lockKind: LockKind,
        lockSubject: String,
        amount: BigDecimal,
    )(implicit env: SpliceTestConsoleEnvironment): CId = {
      // `owner` may already have pending instructions, so only look at new ones
      val existingCids = listTransfers(participant, owner.partyId).map(_._1).toSet

      transferAndList(
        "the owner creates the GovernanceLock",
        "a GovernanceLock is now the pending TransferInstruction",
        participant,
        owner,
        amount,
        makeInputLockMemo(CreateLock, lockKind, lockSubject)(()),
        wait = true,
        defaultIsValid(lockManagerParty.toProtoPrimitive, existingCids),
      ).loneElement._1
    }

    private def atMostOneVestingLock(locks: Seq[(CId, View)]): Option[(CId, View)] =
      locks match {
        case Seq() => None
        case Seq(cid) => Some(cid)
        case many => fail(s"Expected at most one vesting lock, got $many")
      }

    final def unlockGovernanceLock(
        participant: ParticipantClientReference,
        owner: RichPartyId,
        lockKind: LockKind,
        lockSubject: String,
        unlockAmount: BigDecimal,
        vestingStartTime: CantonTimestamp,
        isValid: View => Boolean = _ => true,
    )(implicit env: SpliceTestConsoleEnvironment): Option[(CId, View)] = {
      // `owner` may already have pending instructions, so only look at new ones
      val existingCids = listTransfers(participant, owner.partyId).map(_._1).toSet

      atMostOneVestingLock(
        transferAndList(
          "the owner unlocks the GovernanceLock",
          "a vesting lock is now the pending TransferInstruction",
          participant,
          owner,
          0,
          makeInputLockMemo(UnlockAndStartVesting, lockKind, lockSubject)(
            (unlockAmount, vestingStartTime)
          ),
          wait = false,
          (cid, view) =>
            defaultIsValid(lockManagerParty.toProtoPrimitive, existingCids)(cid, view) && isValid(
              view
            ),
        )
      )
    }

    final def createAndUnlockGovernanceLock(
        participant: ParticipantClientReference,
        owner: RichPartyId,
        lockKind: LockKind,
        lockSubject: String,
        lockAmount: BigDecimal,
        unlockAmount: BigDecimal,
        vestingStartTime: CantonTimestamp,
        isValid: CId => View => Boolean = _ => _ => true,
    )(implicit env: SpliceTestConsoleEnvironment): Unit = {
      val cid = createGovernanceLock(participant, owner, lockKind, lockSubject, lockAmount)
      unlockGovernanceLock(
        participant,
        owner,
        lockKind,
        lockSubject,
        unlockAmount,
        vestingStartTime,
        isValid(cid),
      )
    }

    final def withdrawVestingLock(
        participant: ParticipantClientReference,
        owner: RichPartyId,
        lockKind: LockKind,
        lockSubject: String,
        vestingLock: governancelock.GovernanceLock,
        vestedUntilTime: CantonTimestamp,
    )(implicit env: SpliceTestConsoleEnvironment): Option[(CId, View)] =
      atMostOneVestingLock(
        transferAndList(
          "the owner withdraws the vesting lock",
          "at most one new vesting lock is created",
          participant,
          owner,
          0,
          makeInputLockMemo(WithdrawVestedFunds, lockKind, lockSubject)(
            (
              vestedUntilTime,
              vestingLockSpecification(vestingLock).vestingPeriod,
              vestingLockSpecification(vestingLock).endTime,
            )
          ),
          wait = false,
          (_, _) => true,
        )
      )

    final class SubstituteLock(data: SubstituteLockData) {
      final def apply(
          participant: ParticipantClientReference,
          owner: RichPartyId,
          lockKind: LockKind,
          lockSubject: String,
          substitutionAmount: BigDecimal,
      )(implicit env: SpliceTestConsoleEnvironment): CId = {
        val (_, targetLockOwner, _, _) = data
        // `owner` may already have pending instructions, so only look at new ones
        val existingCids = listTransfers(participant, owner.partyId).map(_._1).toSet

        transferAndList(
          "the owner proposes the substitution",
          "the substitution is now a pending TransferInstruction to the owner of the target lock",
          participant,
          owner,
          substitutionAmount,
          makeInputLockMemo(SubstituteLock, lockKind, lockSubject)(data),
          // A substitution proposed by the owner of the target lock locks no funds,
          // so no input holding is archived
          wait = owner.partyId != targetLockOwner,
          defaultIsValid(targetLockOwner.toProtoPrimitive, existingCids),
        ).loneElement._1
      }
    }

    /** Propose a substitution of a GovernanceLock */
    def substituteGovernanceLock(lock: governancelock.GovernanceLock) =
      new SubstituteLock((Locked, PartyId.tryFromProtoPrimitive(lock.owner), None, None))

    /** Propose a substitution of a vesting GovernanceLock */
    def substituteVestingLock(lock: governancelock.GovernanceLock) =
      new SubstituteLock(
        (
          Vesting,
          PartyId.tryFromProtoPrimitive(lock.owner),
          Some(vestingLockSpecification(lock).vestingPeriod),
          Some(vestingLockSpecification(lock).endTime),
        )
      )

    final class TransferLock(makeData: (PartyId, BigDecimal) => TransferLockData) {
      final def apply(
          participant: ParticipantClientReference,
          owner: RichPartyId,
          lockKind: LockKind,
          lockSubject: String,
          newLockOwner: PartyId,
          transferAmount: BigDecimal,
      )(implicit env: SpliceTestConsoleEnvironment): CId = {
        // `owner` may already have pending instructions, so only look at new ones
        val existingCids = listTransfers(participant, owner.partyId).map(_._1).toSet

        transferAndList(
          "the owner proposes the lock transfer",
          "the lock transfer is now a pending TransferInstruction to the new owner",
          participant,
          owner,
          0,
          makeInputLockMemo(TransferLock, lockKind, lockSubject)(
            makeData(newLockOwner, transferAmount)
          ),
          // A lock transfer proposal moves no funds, so no input holding is archived
          wait = false,
          defaultIsValid(newLockOwner.toProtoPrimitive, existingCids),
        ).loneElement._1
      }
    }

    /** Propose a transfer of a vesting GovernanceLock */
    final def transferVestingLock(lock: governancelock.GovernanceLock) =
      new TransferLock((newLockOwner, transferAmount) =>
        (
          Vesting,
          newLockOwner,
          transferAmount,
          Some(vestingLockSpecification(lock).vestingPeriod),
          Some(vestingLockSpecification(lock).endTime),
        )
      )
  }

  object tsv1Operations
      extends TokenStandardOps[
        transferinstructionv1.TransferInstruction.ContractId,
        transferinstructionv1.TransferInstructionView,
      ](1) {
    def executeTransfer(
        participant: ParticipantClientReference,
        sender: RichPartyId,
        receiver: PartyId,
        amount: BigDecimal,
        description: Option[String],
        wait: Boolean = true,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      executeTransferViaTokenStandardV1(
        participant,
        sender,
        receiver,
        amount,
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
        description = description,
        wait = wait,
      )

    def acceptTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv1.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      acceptTransferInstructionV1(participant, receiver, cid)

    def rejectTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv1.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      rejectTransferInstructionV1(participant, receiver, cid)

    def withdrawTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv1.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      withdrawTransferInstructionV1(participant, receiver, cid)

    def listTransfers(participant: ParticipantClientReference, party: PartyId) =
      listTransferInstructions(participant, party)

    def getAcceptContext(cid: transferinstructionv1.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionAcceptContext(cid)

    def getRejectContext(cid: transferinstructionv1.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionRejectContext(cid)

    def getWithdrawContext(cid: transferinstructionv1.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionWithdrawContext(cid)
  }

  object tsv2Operations
      extends TokenStandardOps[
        transferinstructionv2.TransferInstruction.ContractId,
        transferinstructionv2.TransferInstructionView,
      ](2) {
    def executeTransfer(
        participant: ParticipantClientReference,
        sender: RichPartyId,
        receiver: PartyId,
        amount: BigDecimal,
        description: Option[String],
        wait: Boolean = true,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      executeTransferViaTokenStandardV2(
        participant,
        sender,
        receiver,
        amount,
        transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind.Offer,
        description = description,
        wait = wait,
      )

    def acceptTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv2.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      acceptTransferInstructionV2(participant, receiver, cid)

    def rejectTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv2.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      rejectTransferInstructionV2(participant, receiver, cid)

    def withdrawTransfer(
        participant: ParticipantClientReference,
        receiver: RichPartyId,
        cid: transferinstructionv2.TransferInstruction.ContractId,
    )(implicit env: SpliceTestConsoleEnvironment): Unit =
      withdrawTransferInstructionV2(participant, receiver, cid)

    def listTransfers(participant: ParticipantClientReference, party: PartyId) =
      listTransferInstructionsV2(participant, party)

    def getAcceptContext(cid: transferinstructionv2.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionAcceptContextV2(cid)

    def getRejectContext(cid: transferinstructionv2.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionRejectContextV2(cid)

    def getWithdrawContext(cid: transferinstructionv2.TransferInstruction.ContractId)(implicit
        env: SpliceTestConsoleEnvironment
    ): ChoiceContextWithDisclosures =
      sv1ScanBackend.getTransferInstructionWithdrawContextV2(cid)
  }
}
