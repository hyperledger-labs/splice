// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.util

import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.{
  AmuletDecentralizedSynchronizerConfig,
  RegisteredSynchronizer,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, ContractWithState, SpliceUtil}
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.CommandPriority

import scala.concurrent.{ExecutionContext, Future}

object TopupUtil {

  /** The registration's discount factor, or 1.0 where there is no registration, mirroring
    * `getDiscountFactor` in Daml. A registration always states its parameters.
    */
  def discountFactor(
      registration: Option[
        ContractWithState[RegisteredSynchronizer.ContractId, RegisteredSynchronizer]
      ]
  ): BigDecimal =
    registration
      .map(_.payload.governanceParameters)
      .fold(BigDecimal(1))(p => BigDecimal(p.discountFactor))

  def minWalletBalanceForTopup(
      scanConnection: ScanConnection,
      validatorTopupConfig: ValidatorTopupConfig,
      clock: Clock,
      registration: Option[
        ContractWithState[RegisteredSynchronizer.ContractId, RegisteredSynchronizer]
      ],
  )(implicit tc: TraceContext, ec: ExecutionContext, mat: Materializer): Future[BigDecimal] = for {
    amuletRules <- scanConnection.getAmuletRulesWithState()
    synchronizerFeesConfig = AmuletConfigSchedule(amuletRules)
      .getConfigAsOf(clock.now)
      .decentralizedSynchronizer
      .fees
    topupParameters = ExtraTrafficTopupParameters(
      validatorTopupConfig.targetThroughput,
      validatorTopupConfig.minTopupInterval,
      synchronizerFeesConfig.minTopupAmount,
      validatorTopupConfig.topupTriggerPollingInterval,
    )
    latestRound <- scanConnection.getLatestOpenMiningRound()
    amuletPrice = latestRound.payload.amuletPrice
    extraTrafficPrice = BigDecimal(synchronizerFeesConfig.extraTrafficPrice)
  } yield SpliceUtil
    .synchronizerFees(
      topupParameters.topupAmount,
      extraTrafficPrice,
      amuletPrice,
      discountFactor(registration),
    )
    ._2

  private def currentWalletBalance(scanConnection: ScanConnection, store: UserWalletStore)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[BigDecimal] = for {
    latestRound <- scanConnection.getLatestOpenMiningRound()
    roundNum = latestRound.payload.round.number
    walletBalance <- store
      .getAmuletBalanceWithHoldingFees(
        roundNum
      )
      .map(_._1)
  } yield walletBalance

  /** The balance the wallet can spend on traffic, or `None` where it does not bound the purchase. */
  def topupBudget(
      scanConnection: ScanConnection,
      validatorWalletStore: UserWalletStore,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Option[BigDecimal]] = {
    scanConnection.getAmuletRulesWithState().flatMap { amuletRules =>
      // Since we auto-tap CC for traffic purchases on DevNet, we always have sufficient funds
      // TODO(#851): Considering removing this once we remove auto-tapping in DevNet
      if (amuletRules.payload.isDevNet) Future.successful(None)
      else currentWalletBalance(scanConnection, validatorWalletStore).map(Some(_))
    }
  }

  def hasSufficientFundsForTopupOnGlobalSync(
      scanConnection: ScanConnection,
      validatorWalletStore: UserWalletStore,
      globalTopupConfig: ValidatorTopupConfig,
      clock: Clock,
  )(implicit tc: TraceContext, ec: ExecutionContext, mat: Materializer): Future[Boolean] = {
    topupBudget(scanConnection, validatorWalletStore).flatMap {
      case None => Future.successful(true)
      case Some(walletBalance) =>
        minWalletBalanceForTopup(scanConnection, globalTopupConfig, clock, None)
          .map(walletBalance >= _)
    }
  }

  def highPriorityIncreaseBalanceForTopup(
      scanConnection: ScanConnection,
      validatorWalletStore: UserWalletStore,
      validatorTopupConfig: ValidatorTopupConfig,
      clock: Clock,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[CommandPriority] =
    hasSufficientFundsForTopupOnGlobalSync(
      scanConnection,
      validatorWalletStore,
      validatorTopupConfig,
      clock,
    ).map(if (_) CommandPriority.Low else CommandPriority.High)

  /** The synchronizer a traffic purchase is for, as AmuletRules knows it. */
  sealed trait TrafficSynchronizer
  object TrafficSynchronizer {

    /** Listed in `requiredSynchronizers`: bought at the validator's migration id, without a
      * registration.
      */
    case object Required extends TrafficSynchronizer

    /** Authorized by a `RegisteredSynchronizer` disclosed with the purchase, at migration id 0. */
    final case class Registered(
        registration: ContractWithState[RegisteredSynchronizer.ContractId, RegisteredSynchronizer]
    ) extends TrafficSynchronizer

    /** Neither required nor registered: no purchase can succeed. */
    case object Unknown extends TrafficSynchronizer
  }

  def trafficSynchronizer(
      scanConnection: ScanConnection,
      decentralizedSynchronizerConfig: AmuletDecentralizedSynchronizerConfig,
      synchronizerId: String,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[TrafficSynchronizer] =
    if (decentralizedSynchronizerConfig.requiredSynchronizers.map.containsKey(synchronizerId))
      Future.successful(TrafficSynchronizer.Required)
    else
      scanConnection.lookupSynchronizerRegistration(synchronizerId).map {
        case Some(registration) => TrafficSynchronizer.Registered(registration)
        case None => TrafficSynchronizer.Unknown
      }

}
