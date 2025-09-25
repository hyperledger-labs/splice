// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.TriggerContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment
import org.lfdecentralizedtrust.splice.environment.{PackageVersionSupport, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.SpliceUtil.ccToDollars
import org.lfdecentralizedtrust.splice.wallet.config.WalletSweepConfig
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class WalletTransferOfferSweepTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
    config: WalletSweepConfig,
    scanConnection: ScanConnection,
    packageVersionSupport: PackageVersionSupport,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
    mat: Materializer,
) extends WalletSweepTrigger(context, store, config, scanConnection, packageVersionSupport) {

  override protected def extraRetrieveTasksValidation()(implicit tc: TraceContext) = Future.unit

  override protected def getOutstandingBalanceUsd(
      amuletPrice: java.math.BigDecimal,
      currentAmuletConfig: AmuletConfig[USD],
  )(implicit tc: TraceContext): scala.concurrent.Future[BigDecimal] = {
    for {
      (filteredTransferOffers, filteredAcceptedTransferOffers) <- store
        .getOutstandingTransferOffers(
          None,
          Some(config.receiver),
        )
    } yield (filteredTransferOffers.map(_.payload.amount.amount) ++ filteredAcceptedTransferOffers
      .map(_.payload.amount.amount))
      .map(amount => {
        BigDecimal(ccToDollars(amount, amuletPrice)) + computeTransferFees(
          BigDecimal(amount),
          currentAmuletConfig.transferConfig.transferFee,
        ) + computeCreateFees(currentAmuletConfig)
      })
      .sum
  }

  override protected def outstandingDescription = "transfer offers"

  override protected def sweep(
      task: WalletSweepTrigger.Task,
      balanceUsd: BigDecimal,
      outstandingBalanceUsd: BigDecimal,
      amountToSendBeforeFeesUsd: BigDecimal,
      amountToSendAfterFeesUsd: BigDecimal,
      amountToSendAfterFeesCC: java.math.BigDecimal,
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      (filteredTransferOffers, acceptedFilteredTransferOffers) <- store
        .getOutstandingTransferOffers(
          None,
          Some(config.receiver),
        )
      transferOfferAlreadyExists = filteredTransferOffers.exists(
        _.payload.trackingId == task.trackingId
      ) || acceptedFilteredTransferOffers.exists(
        _.payload.trackingId == task.trackingId
      )
      install <- store.getInstall()
      _ <-
        if (transferOfferAlreadyExists) {
          Future.successful("Transfer offer already exists.")
        } else {
          logger.info(
            s"Creating a transfer offer with trackingId ${task.trackingId} for $amountToSendAfterFeesUsd USD ($amountToSendAfterFeesCC CC)."
          )
          logger.debug(
            s"Before fees amount: $amountToSendBeforeFeesUsd USD (balance of $balanceUsd, outstanding $outstandingDescription are $outstandingBalanceUsd USD, and configured min balance is ${config.minBalanceUsd.value})"
          )
          val cmd = install.exercise(
            _.exerciseWalletAppInstall_CreateTransferOffer(
              config.receiver.toProtoPrimitive,
              new payment.PaymentAmount(amountToSendAfterFeesCC, payment.Unit.AMULETUNIT),
              s"Sweeping wallet funds to receiver ${config.receiver}.",
              Instant.now().plus(10, ChronoUnit.MINUTES),
              task.trackingId,
            )
          )
          connection
            .submit(
              Seq(store.key.validatorParty),
              Seq(store.key.endUserParty),
              cmd,
            )
            .noDedup
            .yieldResult()
        }
    } yield ()
  }

}
