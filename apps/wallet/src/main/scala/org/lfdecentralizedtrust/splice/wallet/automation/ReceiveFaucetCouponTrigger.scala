// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import cats.data.OptionT
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.RoundBasedRewardTrigger.RoundBasedTask
import org.lfdecentralizedtrust.splice.automation.{
  RoundBasedRewardTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.util.{AssignedContract, ContractWithState}
import org.lfdecentralizedtrust.splice.wallet.store.{
   FetchCommandPriority,
   ValidatorLicenseStore,
}

import java.time.temporal.ChronoUnit
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.math.Ordering.Implicits.*

class ReceiveFaucetCouponTrigger(
    override protected val context: TriggerContext,
    scanConnection: BftScanConnection,
    licenseStore: ValidatorLicenseStore,
    spliceLedgerConnection: SpliceLedgerConnection,
    licensedParty: PartyId,
    priorityFetcher: FetchCommandPriority,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    materializer: Materializer,
) extends RoundBasedRewardTrigger[ReceiveFaucetCouponTrigger.Task] {

  override protected def retrieveAvailableTasksForRound()(implicit
      tc: TraceContext
  ): Future[Seq[ReceiveFaucetCouponTrigger.Task]] =
    retrieveNextRoundToClaim().value.map(_.toList)

  private def retrieveNextRoundToClaim()(implicit
      tc: TraceContext
  ): OptionT[Future, ReceiveFaucetCouponTrigger.Task] = {
    for {
      // The ValidatorLicense is guaranteed to exist, but might take a while during init.
      license <- OptionT(
        licenseStore
          .lookupValidatorLicenseWithOffset()
          .map(_.value.flatMap(_.toAssignedContract))
      )
      (openRounds, _) <- OptionT.liftF(scanConnection.getOpenAndIssuingMiningRounds())
      firstOpenNotClaimed <- OptionT.fromOption[Future](
        openRounds
          .filter(round =>
            license.payload.faucetState.toScala
              .forall(
                _.lastReceivedFor.number < round.payload.round.number
              ) && round.payload.opensAt <= context.clock.now.toInstant
          )
          .minByOption(_.contract.payload.opensAt)
      )
    } yield ReceiveFaucetCouponTrigger.Task(license, firstOpenNotClaimed)
  }

  override protected def completeTask(task: ReceiveFaucetCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val ReceiveFaucetCouponTrigger.Task(license, unclaimedRound) = task
    license.payload.weight.toScala match {
      case Some(x) if x == 0 =>
        // weight is zero; don't exercise RecordValidatorLivenessActivity
        Future.successful(TaskSuccess("weight is 0, skipping the recording of liveness activity"))
      case _ =>
        // Weight is unspecified or non-zero; exercise RecordValidatorLivenessActivity
        recordLivenessActivity(task)
    }
  }

  private def recordLivenessActivity(task: ReceiveFaucetCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val ReceiveFaucetCouponTrigger.Task(license, unclaimedRound) = task
    license.payload.faucetState.toScala
      .map(_.lastReceivedFor.number.longValue()) match {
      case None =>
        logger.info(
          s"Validator never received faucet coupons, it will start now at round ${unclaimedRound.payload.round.number}."
        )
      case Some(lastReceivedFor) =>
        val skippedCount = unclaimedRound.payload.round.number - lastReceivedFor - 1
        if (skippedCount > 0)
          logger.warn(
            s"Skipped $skippedCount faucet coupons from last claimed round $lastReceivedFor to current round ${unclaimedRound.payload.round.number}. " +
              s"This is expected in case of validator inactivity."
          )
    }
    for {
      commandPriority <- priorityFetcher.getCommandPriority()
      outcome <- spliceLedgerConnection
        .submit(
          actAs = Seq(licensedParty),
          readAs = Seq(licensedParty),
          license.exercise(
            _.exerciseValidatorLicense_RecordValidatorLivenessActivity(
              unclaimedRound.contractId
            )
          ),
          priority = commandPriority,
        )
        .noDedup
        .withDisclosedContracts(spliceLedgerConnection.disclosedContracts(unclaimedRound))
        .yieldUnit()
        .map(_ =>
          TaskSuccess(s"Received faucet coupon for Round ${unclaimedRound.payload.round.number}")
        )
    } yield outcome
  }

  override protected def isStaleTask(task: ReceiveFaucetCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    val nextRound = retrieveNextRoundToClaim()
    nextRound.forall(_ != task)
  }

}

object ReceiveFaucetCouponTrigger {

  case class Task(
      license: AssignedContract[ValidatorLicense.ContractId, ValidatorLicense],
      round: ContractWithState[OpenMiningRound.ContractId, OpenMiningRound],
  ) extends PrettyPrinting
      with RoundBasedTask {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("license", _.license),
        param("round", _.round),
      )

    override def scheduleAtMaxTargetTime: Instant =
      opensAt.plus(round.payload.tickDuration.microseconds, ChronoUnit.MICROS)

    def closesAt: Instant = round.payload.targetClosesAt

    def roundNumber: Long = Long.unbox(round.payload.round.number)

    def opensAt: Instant = round.payload.opensAt
  }

}
