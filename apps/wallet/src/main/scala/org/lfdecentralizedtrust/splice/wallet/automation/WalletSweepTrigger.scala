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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule
import org.lfdecentralizedtrust.splice.util.SpliceUtil.{ccToDollars, dollarsToCC}
import org.lfdecentralizedtrust.splice.wallet.config.WalletSweepConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

abstract class WalletSweepTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    config: WalletSweepConfig,
    scanConnection: ScanConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends PollingParallelTaskExecutionTrigger[WalletSweepTrigger.Task] {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  /** additional validation called as part of retrieveTasks which can be used to catch configuration mistakes early
    */
  protected def extraRetrieveTasksValidation()(implicit tc: TraceContext): Future[Unit]

  /** Get the balance that has not yet been transferred but already should be deducted from the available balance
    */
  protected def getOutstandingBalanceUsd(
      amuletPrice: java.math.BigDecimal,
      amuletConfig: AmuletConfig[USD],
  )(implicit tc: TraceContext): Future[BigDecimal]

  protected def outstandingDescription: String

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[WalletSweepTrigger.Task]] = {
    for {
      _ <- extraRetrieveTasksValidation()
      amuletRules <- scanConnection.getAmuletRules()
      round <- scanConnection.getLatestOpenMiningRound()
      amuletPrice = round.payload.amuletPrice
      currentAmuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(CantonTimestamp.now())
      balance <- store
        .getAmuletBalanceWithHoldingFees(
          round.payload.round.number
        )
        .map(_._1)
      balanceUsd = BigDecimal(ccToDollars(balance.bigDecimal, amuletPrice))
      outstandingBalanceUsd <- getOutstandingBalanceUsd(
        amuletPrice,
        currentAmuletConfig,
      )
    } yield {
      if (balanceUsd - outstandingBalanceUsd > config.maxBalanceUsd.value) {
        val trackingId = UUID.randomUUID.toString
        Seq(WalletSweepTrigger.Task(trackingId))
      } else {
        if (balanceUsd > config.maxBalanceUsd.value) {
          logger.info(
            s"Balance $balanceUsd is above max balance ${config.maxBalanceUsd.value} but there are outstanding $outstandingDescription with a value of ${outstandingBalanceUsd}, not creating additional $outstandingDescription"
          )
        }
        Seq.empty
      }
    }
  }

  protected def sweep(
      task: WalletSweepTrigger.Task,
      balanceUsd: BigDecimal,
      outstandingBalanceUsd: BigDecimal,
      amountToSendBeforeFeesUsd: BigDecimal,
      amountToSendAfterFeesUsd: BigDecimal,
      amountToSendAfterFeesCC: java.math.BigDecimal,
  )(implicit tc: TraceContext): Future[Unit]

  override protected def completeTask(
      task: WalletSweepTrigger.Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      amuletRules <- scanConnection.getAmuletRules()
      round <- scanConnection
        .getLatestOpenMiningRound()
      amuletPrice = round.payload.amuletPrice
      currentAmuletConfig = AmuletConfigSchedule(amuletRules).getConfigAsOf(CantonTimestamp.now())
      balance <- store
        .getAmuletBalanceWithHoldingFees(
          round.payload.round.number
        )
        .map(_._1)
      balanceUsd = BigDecimal(ccToDollars(balance.bigDecimal, amuletPrice))
      outstandingBalanceUsd <- getOutstandingBalanceUsd(amuletPrice, currentAmuletConfig)
      res <-
        if (balanceUsd - outstandingBalanceUsd > config.maxBalanceUsd.value) {
          val amountToSendBeforeFeesUsd =
            balanceUsd - outstandingBalanceUsd - config.minBalanceUsd.value
          val amountToSendAfterFeesUsd =
            getAmountToSendAfterFees(amountToSendBeforeFeesUsd, currentAmuletConfig)
              .setScale(10, scala.math.BigDecimal.RoundingMode.HALF_UP)
              .bigDecimal
          val amountToSendAfterFeesCC = dollarsToCC(amountToSendAfterFeesUsd, amuletPrice)
          sweep(
            task = task,
            balanceUsd = balanceUsd,
            outstandingBalanceUsd = outstandingBalanceUsd,
            amountToSendBeforeFeesUsd = amountToSendBeforeFeesUsd,
            amountToSendAfterFeesUsd = amountToSendAfterFeesUsd,
            amountToSendAfterFeesCC = amountToSendAfterFeesCC,
          ).map(Some(_))
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
      balance <- store
        .getAmuletBalanceWithHoldingFees(
          round.payload.round.number
        )
        .map(_._1)
      amuletPrice = round.payload.amuletPrice
    } yield {
      balance < dollarsToCC(config.maxBalanceUsd.value.bigDecimal, amuletPrice)
    }
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

  protected def computeCreateFees(
      currentAmuletConfig: AmuletConfig[USD]
  ): BigDecimal =
    BigDecimal(currentAmuletConfig.transferConfig.createFee.fee) * 2

  protected def computeTransferFees(
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
        param("Tracking Id", _.trackingId.unquoted)
      )
  }
}
