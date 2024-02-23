package com.daml.network.validator.automation

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.{AssignedContract, ContractWithState, DisclosedContracts}
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import math.Ordering.Implicits.*

class ReceiveFaucetCouponTrigger(
    override protected val context: TriggerContext,
    scanConnection: BftScanConnection,
    validatorStore: ValidatorStore,
    cnLedgerConnection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    materializer: Materializer,
) extends PollingParallelTaskExecutionTrigger[ReceiveFaucetCouponTrigger.Task] {

  private val validatorParty = validatorStore.key.validatorParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ReceiveFaucetCouponTrigger.Task]] = {
    retrieveNextRoundToClaim().value.map(_.toList)
  }

  private def retrieveNextRoundToClaim()(implicit
      tc: TraceContext
  ): OptionT[Future, ReceiveFaucetCouponTrigger.Task] = {
    for {
      // The ValidatorLicense is guaranteed to exist, but might take a while during init.
      license <- OptionT(
        validatorStore
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
    cnLedgerConnection
      .submit(
        actAs = Seq(validatorParty),
        readAs = Seq(validatorParty),
        license
          .exercise(_.exerciseValidatorLicense_ReceiveFaucetCoupon(unclaimedRound.contractId)),
      )
      .noDedup
      .withDisclosedContracts(DisclosedContracts(unclaimedRound))
      .yieldUnit()
      .map(_ =>
        TaskSuccess(s"Received faucet coupon for Round ${unclaimedRound.payload.round.number}")
      )
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
  ) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("license", _.license),
        param("round", _.round),
      )
  }

}
