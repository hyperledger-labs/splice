// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.fees.SteppedRate
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.{PaymentAmount, Unit}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import org.lfdecentralizedtrust.splice.util.SpliceUtil.{ccToDollars, dollarsToCC}
import org.lfdecentralizedtrust.splice.wallet.config.WalletSweepConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.health.admin.data.SequencerHealthStatus.implicitPrettyString
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class WalletSweepTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
    config: WalletSweepConfig,
    scanConnection: ScanConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[WalletSweepTrigger.Task] {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[WalletSweepTrigger.Task]] = {
    for {
      amuletRules <- scanConnection.getAmuletRules()
      round <- scanConnection.getLatestOpenMiningRound()
      amuletPrice = round.payload.amuletPrice
      currentAmuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(CantonTimestamp.now())
      balance <- store.getAmuletBalanceWithHoldingFees(round.payload.round.number).map(_._1)
      balanceUsd = BigDecimal(ccToDollars(balance.bigDecimal, amuletPrice))
      sumOfOutstandingTransfersOffersUsd <- getSumOfOutstandingTransfersOffersUsd(
        amuletPrice,
        currentAmuletConfig,
      )
    } yield {
      if (balanceUsd - sumOfOutstandingTransfersOffersUsd > config.maxBalanceUsd.value) {
        val trackingId = UUID.randomUUID.toString
        Seq(WalletSweepTrigger.Task(trackingId))
      } else {
        if (balanceUsd > config.maxBalanceUsd.value) {
          logger.info(
            s"Balance $balanceUsd is above max balance ${config.maxBalanceUsd.value} but there are outstanding transfer offers with a sum of ${sumOfOutstandingTransfersOffersUsd}, not creating additional transfer offers"
          )
        }
        Seq.empty
      }
    }
  }

  override protected def completeTask(
      task: WalletSweepTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      amuletRules <- scanConnection.getAmuletRules()
      round <- scanConnection
        .getLatestOpenMiningRound()
      amuletPrice = round.payload.amuletPrice
      currentAmuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(CantonTimestamp.now())
      balance <- store.getAmuletBalanceWithHoldingFees(round.payload.round.number).map(_._1)
      balanceUsd = BigDecimal(ccToDollars(balance.bigDecimal, amuletPrice))
      // We only consider transfer offers to the receiver since we want to err on the side of creating
      // transfer offers unless the receiver has not acted and not consider other activity.
      // If there are other offers things might just fail with out of fund.
      sumOfOutstandingTransfersOffersUsd <- getSumOfOutstandingTransfersOffersUsd(
        amuletPrice,
        currentAmuletConfig,
      )
      res <-
        if (balanceUsd - sumOfOutstandingTransfersOffersUsd > config.maxBalanceUsd.value) {
          val amountToSendBeforeFeesUsd =
            balanceUsd - sumOfOutstandingTransfersOffersUsd - config.minBalanceUsd.value
          val amountToSendAfterFeesUsd =
            getAmountToSendAfterFees(amountToSendBeforeFeesUsd, currentAmuletConfig)
              .setScale(10, scala.math.BigDecimal.RoundingMode.HALF_UP)
              .bigDecimal
          val amountToSendAfterFeesCC = dollarsToCC(amountToSendAfterFeesUsd, amuletPrice)
          for {
            filteredTransferOffers <- store.getOutstandingTransferOffers(
              None,
              Some(config.receiver),
            )
            transferOfferAlreadyExists = filteredTransferOffers.exists(
              _.payload.trackingId == task.trackingId
            )
            install <- store.getInstall()
            result <-
              if (transferOfferAlreadyExists) {
                Future.successful("Transfer offer already exists.")
              } else {
                logger.info(
                  s"Creating a transfer offer with trackingId ${task.trackingId} for $amountToSendAfterFeesUsd USD ($amountToSendAfterFeesCC CC)."
                )
                logger.debug(
                  s"Before fees amount: $amountToSendBeforeFeesUsd USD (balance of $balanceUsd, outstanding offers are $sumOfOutstandingTransfersOffersUsd USD, and configured min balance is ${config.minBalanceUsd.value})"
                )
                val cmd = install.exercise(
                  _.exerciseWalletAppInstall_CreateTransferOffer(
                    config.receiver.toProtoPrimitive,
                    new PaymentAmount(amountToSendAfterFeesCC, Unit.AMULETUNIT),
                    s"Sweeping wallet funds to receiver ${config.receiver}.",
                    Instant.now().plus(10, ChronoUnit.MINUTES),
                    task.trackingId,
                  )
                )
                connection
                  .submit(
                    Seq(store.key.validatorParty),
                    Seq(),
                    cmd,
                  )
                  .noDedup
                  .yieldResult()
              }
          } yield Some(result)
        } else {
          Future.successful(None)
        }
    } yield res match {
      case None =>
        TaskSuccess(
          s"Wallet funds not swept to receiver ${config.receiver} as balance is not high enough."
        )
      case Some(res) =>
        TaskSuccess(
          s"Sweeping wallet funds to receiver ${config.receiver} with result $res."
        )
    }
  }

  override protected def isStaleTask(task: WalletSweepTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      round <- scanConnection
        .getLatestOpenMiningRound()
      balance <- store.getAmuletBalanceWithHoldingFees(round.payload.round.number).map(_._1)
      amuletPrice = round.payload.amuletPrice
    } yield {
      balance < dollarsToCC(config.maxBalanceUsd.value.bigDecimal, amuletPrice)
    }
  }

  private def getSumOfOutstandingTransfersOffersUsd(
      amuletPrice: java.math.BigDecimal,
      currentAmuletConfig: AmuletConfig[USD],
  )(implicit tc: TraceContext) = {
    for {
      filteredTransferOffers <- store.getOutstandingTransferOffers(
        None,
        Some(config.receiver),
      )
    } yield filteredTransferOffers
      .map(c => {
        BigDecimal(ccToDollars(c.payload.amount.amount, amuletPrice)) + computeTransferFees(
          BigDecimal(c.payload.amount.amount),
          currentAmuletConfig.transferConfig.transferFee,
        )
          + computeCreateFees(currentAmuletConfig)
      })
      .sum
  }

  private def getAmountToSendAfterFees(
      amountToSendBeforeFeesUsd: BigDecimal,
      currentAmuletConfig: AmuletConfig[USD],
  ): BigDecimal = {
    val createFee = computeCreateFees(currentAmuletConfig)
    val transferFee = computeTransferFees(
      amountToSendBeforeFeesUsd,
      currentAmuletConfig.transferConfig.transferFee,
    )
    amountToSendBeforeFeesUsd - createFee - transferFee
  }

  private def computeCreateFees(
      currentAmuletConfig: AmuletConfig[USD]
  ): BigDecimal =
    BigDecimal(currentAmuletConfig.transferConfig.createFee.fee) * 2

  private def computeTransferFees(
      amountUsd: BigDecimal,
      transferFeeConfigUsd: SteppedRate,
  ): BigDecimal = {
    def go(
        remainder: BigDecimal,
        currentRate: BigDecimal,
        total: BigDecimal,
        steps: Seq[(java.math.BigDecimal, java.math.BigDecimal)],
    ): BigDecimal =
      steps match {
        case _ if remainder <= 0 => total
        case stepTuple +: steps =>
          val step = stepTuple._1
          val steppedRate = stepTuple._2
          val newRemainder = remainder - step
          val newTotal = total + remainder.min(step) * currentRate
          go(newRemainder, steppedRate, newTotal, steps)
        case _ => total + remainder * currentRate
      }

    // turn [(100.0, 0.001), (1000.0, 0.0001), (1000000, 0.00001)]
    // into [(100.0, 0.001), (900.0, 0.0001), (998900, 0.00001)]
    // i.e., the step is the remainder after applying all the
    // previous steps and not the absolute value.
    // Note that technically this depends on the Daml version since it was changed as part of
    // #12735. However, we don't care about being that exact in this script so
    // we use the new version in all cases which is correct as soon as all clusters upgrade
    // to splice-amulet-0.1.2.
    def getStepsDifferences(
        steps: Seq[Tuple2[java.math.BigDecimal, java.math.BigDecimal]]
    ): Seq[(java.math.BigDecimal, java.math.BigDecimal)] =
      steps
        .foldLeft(
          (BigDecimal(0).bigDecimal, Seq.empty[(java.math.BigDecimal, java.math.BigDecimal)])
        ) { case ((total, xs), stepTuple) =>
          val step = stepTuple._1
          val rate = stepTuple._2
          (total.add(step), (step.subtract(total), rate) +: xs)
        }
        ._2
        .reverse

    go(
      amountUsd,
      transferFeeConfigUsd.initialRate,
      0.0,
      getStepsDifferences(transferFeeConfigUsd.steps.asScala.toSeq),
    )
  }
}

object WalletSweepTrigger {
  final case class Task(
      trackingId: String
  ) extends PrettyPrinting {
    override def pretty: Pretty[Task] =
      prettyOfClass[Task](
        param("Tracking Id", _.trackingId)
      )
  }
}
