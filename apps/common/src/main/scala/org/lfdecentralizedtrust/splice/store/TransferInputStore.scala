// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.InputAmulet
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait TransferInputStore extends AppStore with LimitHelpers {

  /** List all non-expired amulets owned by a user in descending order according to their amount. */
  def listSortedAmuletsAndQuantity(
      limit: Limit = Limit.DefaultLimit
  )(implicit
      tc: TraceContext
  ): Future[Seq[(BigDecimal, InputAmulet)]] = for {
    amulets <- multiDomainAcsStore.listContracts(Amulet.COMPANION)
  } yield amulets
    .map(c =>
      (
        c.payload.amount.initialAmount,
        c,
      )
    )
    .sortBy(quantityAndAmulet =>
      // negating because largest values should come first.
      quantityAndAmulet._1.negate()
    )
    .take(limit.limit)
    .map(quantityAndAmulet =>
      (
        quantityAndAmulet._1,
        new InputAmulet(
          quantityAndAmulet._2.contractId
        ),
      )
    )

  /** Returns the validator reward coupon sorted by their round in ascending order.
    * Optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[ValidatorRewardCoupon.ContractId, ValidatorRewardCoupon]
  ]] =
    for {
      rewards <- multiDomainAcsStore.listContracts(
        ValidatorRewardCoupon.COMPANION
      )
    } yield applyLimit(
      "listSortedValidatorRewards",
      limit,
      // TODO(DACH-NY/canton-network-node#6119) Perform filter, sort, and limit in the database query
      rewards.view
        .filter(rw =>
          activeIssuingRoundsO match {
            case Some(rounds) => rounds.contains(rw.payload.round.number)
            case None => true
          }
        )
        .map(_.contract)
        .toSeq
        .sortBy(_.payload.round.number),
    )

  /** Returns the app reward coupon sorted by their round in ascending order and their value in descending order.
    * All rewards are from the given `activeIssuingRounds`.
    */
  def listSortedAppRewards(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (Contract[AppRewardCoupon.ContractId, AppRewardCoupon], BigDecimal)
  ]] =
    for {
      rewards <- multiDomainAcsStore.listContracts(
        AppRewardCoupon.COMPANION
      )
    } yield applyLimit(
      "listSortedAppRewards",
      limit,
      rewards
        // TODO(DACH-NY/canton-network-node#6119) Perform filter, sort, and limit in the database query
        .flatMap { rw =>
          val issuingO = issuingRoundsMap.get(rw.payload.round)
          issuingO
            .map(i => {
              val quantity =
                if (rw.payload.featured)
                  rw.payload.amount.multiply(i.issuancePerFeaturedAppRewardCoupon)
                else
                  rw.payload.amount.multiply(i.issuancePerUnfeaturedAppRewardCoupon)
              (rw.contract, BigDecimal(quantity))
            })
        }
        .sorted(
          Ordering[(Long, BigDecimal)].on(
            (x: (
                Contract.Has[AppRewardCoupon.ContractId, AppRewardCoupon],
                BigDecimal,
            )) => (x._1.payload.round.number, -x._2)
          )
        ),
    )
}
