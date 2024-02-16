package com.daml.network.validator.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.{AssignedContract, DisclosedContracts}
import com.daml.network.validator.store.ValidatorStore
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
) extends PollingTrigger {

  private val validatorParty = validatorStore.key.validatorParty
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    // The ValidatorLicense is guaranteed to exist, but might take a while during init.
    validatorStore
      .lookupValidatorLicenseWithOffset()
      .map(_.value.flatMap(_.toAssignedContract))
      .flatMap {
        case None => Future.unit
        case Some(license) => attemptToReceiveCoupon(license)
      }
      .map(_ => false) // whatever the outcome, we don't want to immediately poll
  }

  private def attemptToReceiveCoupon(
      license: AssignedContract[ValidatorLicense.ContractId, ValidatorLicense]
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      (openRounds, _) <- scanConnection.getOpenAndIssuingMiningRounds()
      firstOpenNotClaimed = openRounds
        .filter(round =>
          license.payload.faucetState.toScala
            .forall(
              _.lastReceivedFor.number < round.payload.round.number
            ) && round.payload.opensAt <= context.clock.now.toInstant
        )
        .minByOption(_.contract.payload.opensAt)
      _ <- firstOpenNotClaimed match {
        case None =>
          logger.trace(
            "No open round for which a ValidatorFaucetCoupon hasn't been claimed already."
          )
          Future.unit
        case Some(unclaimedRound) =>
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
            .map { _ =>
              logger.debug(
                s"Received faucet coupon for Round ${unclaimedRound.payload.round.number}"
              )
            }
      }
    } yield ()
  }

}
