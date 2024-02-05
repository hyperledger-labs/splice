package com.daml.network.scan.store.db

import com.daml.network.store.db.AcsJdbcTypes
import com.daml.network.scan.store.TxLogEntry
import com.daml.network.util.QualifiedName
import com.daml.ledger.javaapi.data.codegen.ContractId

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.PostgresProfile
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import ScanAggregator.*

final class ScanAggregator(
    storage: DbStorage,
    val storeId: Int,
    val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    closeContext: CloseContext,
) extends NamedLogging
    with AcsJdbcTypes {
  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  /** Aggregates RoundTotals and RoundPartyTotals in batches and reports the most recently aggregated RoundTotals. */
  def aggregate()(implicit traceContext: TraceContext): Future[Option[RoundTotals]] = {
    for {
      previousRoundTotals <- getLastAggregatedRoundTotals()
      lastClosedRoundO <- getLastCompletelyClosedRoundAfter(previousRoundTotals.map(_.closedRound))
      _ <- lastClosedRoundO match {
        case Some(lastClosedRound) =>
          for {
            _ <- appendRoundTotals(previousRoundTotals, lastClosedRound)
            _ <- appendRoundPartyTotals(lastClosedRound)
          } yield ()
        case None =>
          previousRoundTotals
            .map { lastAggregated =>
              logger.debug(
                s"Skipped aggregation of round totals and round party totals, no new completely closed rounds to aggregate yet, previously aggregated round: ${lastAggregated.closedRound}"
              )
            }
            .getOrElse(
              logger.debug(
                "Skipped aggregation of round totals and round party totals, no completely closed rounds to aggregate yet"
              )
            )
          Future.successful(())
      }
      lastAggregated <- getLastAggregatedRoundTotals()
    } yield lastAggregated
  }

  def getLastAggregatedRoundTotals()(implicit
      traceContext: TraceContext
  ): Future[Option[RoundTotals]] = {
    val q = sql"""
      select #$roundTotalsColumns
      from   round_totals
      where  store_id = $storeId
      and    closed_round = (select coalesce(max(closed_round), 0) from round_totals)
    """
    storage
      .querySingle(
        q.as[RoundTotals].headOption,
        "getLastAggregatedRoundTotals",
      )
      .value
  }

  def getRoundTotals(round: Long)(implicit
      traceContext: TraceContext
  ): Future[Option[RoundTotals]] = {
    val q = sql"""
      select #$roundTotalsColumns
      from   round_totals
      where  store_id = $storeId
      and    closed_round = $round
    """
    storage
      .querySingle(
        q.as[RoundTotals].headOption,
        "getRoundTotals",
      )
      .value
  }

  def getRoundPartyTotals(round: Long)(implicit
      traceContext: TraceContext
  ): Future[Vector[RoundPartyTotals]] = {
    val q = sql"""
      select   #$roundPartyTotalsColumns
      from     round_party_totals
      where    store_id = $storeId
      and      closed_round = $round
      order by party
    """
    storage
      .query(
        q.as[RoundPartyTotals],
        "getRoundPartyTotals",
      )
  }

  /** Returns the last completely closed round for which no more events can occur, after the previously aggregated round.
    * (no incomplete reassignments, no open, issuing or summarizing rounds)
    * and all previous rounds are closed.
    */
  def getLastCompletelyClosedRoundAfter(lastAggregatedRoundO: Option[Long])(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val lastAggregatedRound = lastAggregatedRoundO.getOrElse(-1L)
    for {
      rounds <- getClosedMiningRoundsSinceLastAggregated(lastAggregatedRound)
      _ = if (rounds.nonEmpty) {
        // Just in case the rounds_total table is truncated.
        val roundsReported =
          if (rounds.size < 100) rounds.mkString(", ")
          else rounds.take(100).mkString(", ") + "..."
        logger.debug(s"Closed mining rounds since last aggregated: $roundsReported")
      }
      roundsIncomplete <- getIncompleteRoundsByContract(
        rounds.collect { case (round, Some(cId)) =>
          (round, cId)
        }
      )
      _ = if (roundsIncomplete.nonEmpty)
        logger.debug("Incomplete assignments for rounds: " + roundsIncomplete.mkString(", "))
      roundsOpenIssuingOrSummarizing <- getOpenIssuingOrSummarizingForClosedRounds(
        rounds.map(_._1)
      )
      _ = if (roundsOpenIssuingOrSummarizing.nonEmpty)
        logger.debug(
          "Open issuing or summarizing rounds for closed rounds: " + roundsOpenIssuingOrSummarizing
            .mkString(", ")
        )
    } yield {
      val lastRound =
        (rounds
          .map(_._1)
          .toSet -- (roundsIncomplete ++ roundsOpenIssuingOrSummarizing).toSet).toList.sorted
          .foldLeft(lastAggregatedRound) { (lastClosedRound, round) =>
            if (round == lastClosedRound + 1) round else lastClosedRound
          }

      if (lastRound == lastAggregatedRound) {
        None
      } else if (lastRound < lastAggregatedRound) {
        logger.error(
          s"The last completely closed round: ${lastRound} is smaller than the previously aggregated round: ${lastAggregatedRound}"
        )
        None
      } else Some(lastRound)
    }
  }

  private def getClosedMiningRoundsSinceLastAggregated(lastAggregatedRound: Long)(implicit
      traceContext: TraceContext
  ) =
    storage
      .query(
        sql"""
            select   round,
                     acs_contract_id
            from     scan_txlog_store
            where    store_id = $storeId
            and      entry_type = ${TxLogEntry.ClosedMiningRoundLogEntry.dbType}
            and      round > $lastAggregatedRound
            order by round desc
          """.as[(Long, Option[ContractId[Any]])],
        "getClosedMiningRoundsSinceLastAggregated",
      )

  private def getIncompleteRoundsByContract(
      roundContractIds: Vector[(Long, ContractId[Any])]
  )(implicit
      traceContext: TraceContext
  ): Future[Vector[Long]] = {
    val results: Vector[Future[Option[Long]]] = roundContractIds.map { case (round, contractId) =>
      for {
        count <- storage
          .querySingle(
            sql"""
                select   count(1)
                from     incomplete_reassignments
                where    store_id = $storeId
                and      contract_id = $contractId
              """.as[Int].headOption,
            "getIncompleteRoundsByContract",
          )
          .value
      } yield {
        if (count.getOrElse(0) > 0) Some(round) else None
      }
    }
    Future
      .sequence(results)
      .map(_.flatten)
  }

  private def getOpenIssuingOrSummarizingForClosedRounds(
      closedRounds: Vector[Long]
  )(implicit
      traceContext: TraceContext
  ): Future[Vector[Long]] = {
    val results: Vector[Future[Option[Long]]] = closedRounds.map { round =>
      for {
        roundsOpenIssuingOrSummarizing <- storage
          .querySingle(
            sql"""
              select   count(1)
              from     scan_acs_store
              where    store_id = $storeId
              and      round = $round
              and
              (
                template_id_qualified_name = ${QualifiedName(
                OpenMiningRound.COMPANION.TEMPLATE_ID
              )} or
                template_id_qualified_name = ${QualifiedName(
                IssuingMiningRound.COMPANION.TEMPLATE_ID
              )} or
                template_id_qualified_name = ${QualifiedName(
                SummarizingMiningRound.COMPANION.TEMPLATE_ID
              )}
              )
            """.as[Long].headOption,
            "getOpenIssuingOrSummarizingForClosedRounds",
          )
          .value
      } yield {
        roundsOpenIssuingOrSummarizing
          .flatMap(count => if (count > 0) Some(round) else None)
      }
    }
    Future
      .sequence(results)
      .map(_.flatten)
  }

  /** Appends the RoundTotals to the round_totals table,
    * calculating cumulative sums starting from previous round_totals,
    * up to including the last closed round.
    */
  def appendRoundTotals(
      previousRoundTotalsO: Option[RoundTotals],
      lastClosedRound: Long,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val previousRoundTotals = previousRoundTotalsO.getOrElse(
      RoundTotals(storeId = storeId, closedRound = -1L)
    )
    def doQuery = {
      val q = sql"""
      with new_totals_per_entry as(
        select    round,
                  0 as closed_round_effective_at,
                  sum(reward_amount) as app_rewards,
                  0 as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.AppRewardLogEntry.dbType}
        group by  round
        union all
        select    round,
                  0 as closed_round_effective_at,
                  0 as app_rewards,
                  sum(reward_amount) as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.ValidatorRewardLogEntry.dbType}
        group by  round
        union all
        select    round,
                  0 as closed_round_effective_at,
                  0 as app_rewards,
                  0 as validator_rewards,
                  sum(balance_change_change_to_initial_amount_as_of_round_zero) as change_to_initial_amount_as_of_round_zero,
                  sum(balance_change_change_to_holding_fees_rate) as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.BalanceChangeLogEntry.dbType}
        group by  round
        union all
        select    round,
                  max(coalesce(closed_round_effective_at,0)) as closed_round_effective_at,
                  0 as app_rewards,
                  0 as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.ClosedMiningRoundLogEntry.dbType}
        group by  round
      ),
      new_totals as(
        select    round,
                  max(closed_round_effective_at) as closed_round_effective_at,
                  sum(app_rewards) as app_rewards,
                  sum(validator_rewards) as validator_rewards,
                  sum(change_to_initial_amount_as_of_round_zero) as change_to_initial_amount_as_of_round_zero,
                  sum(change_to_holding_fees_rate) as change_to_holding_fees_rate
        from      new_totals_per_entry
        group by  round
      ),
      cumulative_totals as (
        select    round,
                  closed_round_effective_at,
                  app_rewards,
                  validator_rewards,
                  change_to_initial_amount_as_of_round_zero,
                  change_to_holding_fees_rate,
                  sum(app_rewards) over (order by round) + ${previousRoundTotals.cumulativeAppRewards} as cumulative_app_rewards,
                  sum(validator_rewards) over (order by round) + ${previousRoundTotals.cumulativeValidatorRewards} as cumulative_validator_rewards,
                  sum(change_to_initial_amount_as_of_round_zero) over (order by round) + ${previousRoundTotals.cumulativeChangeToInitialAmountAsOfRoundZero} as cumulative_change_to_initial_amount_as_of_round_zero,
                  sum(change_to_holding_fees_rate) over (order by round) + ${previousRoundTotals.cumulativeChangeToHoldingFeesRate} as cumulative_change_to_holding_fees_rate
        from      new_totals
      )
      insert into round_totals (
                  store_id,
                  closed_round,
                  closed_round_effective_at,
                  app_rewards,
                  validator_rewards,
                  change_to_initial_amount_as_of_round_zero,
                  change_to_holding_fees_rate,
                  cumulative_app_rewards,
                  cumulative_validator_rewards,
                  cumulative_change_to_initial_amount_as_of_round_zero,
                  cumulative_change_to_holding_fees_rate,
                  total_coin_balance
      )
      select      $storeId,
                  ct.round,
                  ct.closed_round_effective_at,
                  ct.app_rewards,
                  ct.validator_rewards,
                  ct.change_to_initial_amount_as_of_round_zero,
                  ct.change_to_holding_fees_rate,
                  ct.cumulative_app_rewards,
                  ct.cumulative_validator_rewards,
                  ct.cumulative_change_to_initial_amount_as_of_round_zero,
                  ct.cumulative_change_to_holding_fees_rate,
                  ct.cumulative_change_to_initial_amount_as_of_round_zero - ct.cumulative_change_to_holding_fees_rate * (ct.round + 1)
      from        cumulative_totals ct
      on conflict do nothing
    """.asUpdate

      storage
        .update(q, "appendRoundTotals")
        .map(_ => ())
    }
    val extraMsg = previousRoundTotalsO
      .map(pr => s", previously aggregated round: ${pr.closedRound}")
      .getOrElse("")
    logger.info(
      s"""Aggregating round_totals for rounds up to including round ${lastClosedRound}$extraMsg."""
    )

    doQuery
  }

  def appendRoundPartyTotals(
      lastClosedRound: Long
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    def doQuery = {
      /* The round_party_totals will only contain rows for parties which have been rewarded or purchased extra traffic in rounds.
       * Sum per round and party, where the party is the rewarded_party or extra_traffic_validator,
       * since the most recently recorded rounds in round_party_totals, up until including the last closed round.
       * Gets the previous cumulative sums for each party, for the most recent round that registered activity for that party
       * (parties can be missing in rounds, since they did not get rewarded, or did not by traffic).
       * Calculate cumulative sums left joining with possibly existing previous cumulative sums per party, keeping track of the most recent round per party.
       * finally inserting the sums and cumulative sums in the round_party_totals table.
       */
      val q = sql"""
      with previously_aggregated as(
        select coalesce(max(closed_round), -1) as last_closed_round from round_party_totals where store_id = $storeId
      ),
      new_totals_per_entry as(
        select    round,
                  rewarded_party as party,
                  sum(reward_amount) as app_rewards,
                  0 as validator_rewards,
                  0 as traffic_purchased,
                  0 as traffic_purchased_cc_spent,
                  0 as traffic_num_purchases,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.AppRewardLogEntry.dbType}
        and       rewarded_party is not null
        group by  round,
                  rewarded_party
        union all
        select    round,
                  rewarded_party as party,
                  0 as app_rewards,
                  sum(reward_amount) as validator_rewards,
                  0 as traffic_purchased,
                  0 as traffic_purchased_cc_spent,
                  0 as traffic_num_purchases,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.ValidatorRewardLogEntry.dbType}
        and       rewarded_party is not null
        group by  round,
                  rewarded_party
        union all
        select    round,
                  extra_traffic_validator as party,
                  0 as app_rewards,
                  0 as validator_rewards,
                  sum(extra_traffic_purchase_traffic_purchased) as traffic_purchased,
                  sum(extra_traffic_purchase_cc_spent) as traffic_purchased_cc_spent,
                  count(case when extra_traffic_purchase_traffic_purchased is not null then 1 end) as traffic_num_purchases,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $storeId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.ExtraTrafficPurchaseLogEntry.dbType}
        and       extra_traffic_validator is not null
        group by  round,
                  extra_traffic_validator
        union all
        select    round,
                  key as party,
                  0 as app_rewards,
                  0 as validator_rewards,
                  0 as traffic_purchased,
                  0 as traffic_purchased_cc_spent,
                  0 as traffic_num_purchases,
                  sum((value ->> 'changeToInitialAmountAsOfRoundZero')::numeric) as change_to_initial_amount_as_of_round_zero,
                  sum((value ->> 'changeToHoldingFeesRate')::numeric) as change_to_holding_fees_rate
        from      scan_txlog_store,
                  jsonb_each(entry_data -> 'partyBalanceChanges')
        where     store_id = $storeId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${TxLogEntry.BalanceChangeLogEntry.dbType}
        group by  round,
                  party
      ),
      new_totals as(
        select    round,
                  party,
                  sum(app_rewards) as app_rewards,
                  sum(validator_rewards) as validator_rewards,
                  sum(traffic_purchased) as traffic_purchased,
                  sum(traffic_purchased_cc_spent) as traffic_purchased_cc_spent,
                  sum(traffic_num_purchases) as traffic_num_purchases,
                  sum(change_to_initial_amount_as_of_round_zero) as change_to_initial_amount_as_of_round_zero,
                  sum(change_to_holding_fees_rate) as change_to_holding_fees_rate
        from      new_totals_per_entry
        group by  round,
                  party
      ),
      previous_totals as (
        select    party,
                  max(coalesce(cumulative_app_rewards, 0)) as prev_cumulative_app_rewards,
                  max(coalesce(cumulative_validator_rewards, 0)) as prev_cumulative_validator_rewards,
                  max(coalesce(cumulative_traffic_purchased, 0)) as prev_cumulative_traffic_purchased,
                  max(coalesce(cumulative_traffic_purchased_cc_spent, 0)) as prev_cumulative_traffic_purchased_cc_spent,
                  max(coalesce(cumulative_traffic_num_purchases, 0)) as prev_cumulative_traffic_num_purchases,
                  max(coalesce(cumulative_change_to_initial_amount_as_of_round_zero, 0)) as prev_cumulative_change_to_initial_amount_as_of_round_zero,
                  max(coalesce(cumulative_change_to_holding_fees_rate, 0)) as prev_cumulative_change_to_holding_fees_rate
        from      round_party_totals
        where     store_id = $storeId
        group by  party
      ),
      cumulative_totals as (
        select    nt.round,
                  nt.party,
                  nt.app_rewards,
                  nt.validator_rewards,
                  nt.traffic_purchased,
                  nt.traffic_purchased_cc_spent,
                  nt.traffic_num_purchases,
                  sum(nt.app_rewards) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_app_rewards,0) as cumulative_app_rewards,
                  sum(nt.validator_rewards) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_validator_rewards,0) as cumulative_validator_rewards,
                  sum(nt.traffic_purchased) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_traffic_purchased,0) as cumulative_traffic_purchased,
                  sum(nt.traffic_purchased_cc_spent) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_traffic_purchased_cc_spent,0) as cumulative_traffic_purchased_cc_spent,
                  sum(nt.traffic_num_purchases) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_traffic_num_purchases,0) as cumulative_traffic_num_purchases,
                  sum(nt.change_to_initial_amount_as_of_round_zero) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_change_to_initial_amount_as_of_round_zero,0) as cumulative_change_to_initial_amount_as_of_round_zero,
                  sum(nt.change_to_holding_fees_rate) over (partition by nt.party order by round) + coalesce(pt.prev_cumulative_change_to_holding_fees_rate,0) as cumulative_change_to_holding_fees_rate
        from      new_totals nt
        left join previous_totals pt
        on        nt.party = pt.party
      )
      insert into round_party_totals (
                  store_id,
                  closed_round,
                  party,
                  app_rewards,
                  validator_rewards,
                  traffic_purchased,
                  traffic_purchased_cc_spent,
                  traffic_num_purchases,
                  cumulative_app_rewards,
                  cumulative_validator_rewards,
                  cumulative_traffic_purchased,
                  cumulative_traffic_purchased_cc_spent,
                  cumulative_traffic_num_purchases,
                  cumulative_change_to_initial_amount_as_of_round_zero,
                  cumulative_change_to_holding_fees_rate
      )
      select      $storeId,
                  ct.round,
                  ct.party,
                  ct.app_rewards,
                  ct.validator_rewards,
                  ct.traffic_purchased,
                  ct.traffic_purchased_cc_spent,
                  ct.traffic_num_purchases,
                  ct.cumulative_app_rewards,
                  ct.cumulative_validator_rewards,
                  ct.cumulative_traffic_purchased,
                  ct.cumulative_traffic_purchased_cc_spent,
                  ct.cumulative_traffic_num_purchases,
                  ct.cumulative_change_to_initial_amount_as_of_round_zero,
                  ct.cumulative_change_to_holding_fees_rate
      from        cumulative_totals ct
      on conflict do nothing
      """.asUpdate

      storage
        .update(q, "appendRoundPartyTotals")
        .map(_ => ())
    }
    logger.info(
      s"Aggregating round_party_totals for rounds up to including round ${lastClosedRound}."
    )
    doQuery
  }
}

object ScanAggregator {

  type Party = String
  val zero = BigDecimal(0)

  final case class RoundRange(start: Long, end: Long)
  final case class RoundTotals(
      storeId: Int,
      closedRound: Long = 0L,
      closedRoundEffectiveAt: CantonTimestamp = CantonTimestamp.MinValue,
      appRewards: BigDecimal = zero,
      validatorRewards: BigDecimal = zero,
      changeToInitialAmountAsOfRoundZero: BigDecimal = zero,
      changeToHoldingFeesRate: BigDecimal = zero,
      cumulativeAppRewards: BigDecimal = zero,
      cumulativeValidatorRewards: BigDecimal = zero,
      cumulativeChangeToInitialAmountAsOfRoundZero: BigDecimal = zero,
      cumulativeChangeToHoldingFeesRate: BigDecimal = zero,
      totalCoinBalance: BigDecimal = zero,
  )

  implicit val GetResultRoundRange: GetResult[RoundRange] =
    GetResult { prs =>
      import prs.*
      (RoundRange.apply _).tupled(
        (
          <<[Long],
          <<[Long],
        )
      )
    }

  val roundTotalsColumns = """
    store_id,
    closed_round,
    closed_round_effective_at,
    coalesce(app_rewards, 0),
    coalesce(validator_rewards, 0),
    coalesce(change_to_initial_amount_as_of_round_zero,0),
    coalesce(change_to_holding_fees_rate, 0),
    coalesce(cumulative_app_rewards, 0),
    coalesce(cumulative_validator_rewards, 0),
    coalesce(cumulative_change_to_initial_amount_as_of_round_zero, 0),
    coalesce(cumulative_change_to_holding_fees_rate, 0),
    coalesce(total_coin_balance, 0)
    """

  implicit val GetResultRoundTotals: GetResult[RoundTotals] =
    GetResult { prs =>
      import prs.*
      (RoundTotals.apply _).tupled(
        (
          <<[Int],
          <<[Long],
          <<[CantonTimestamp],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
        )
      )
    }

  final case class RoundPartyTotals(
      storeId: Int,
      closedRound: Long = 0L,
      party: String,
      appRewards: BigDecimal = zero,
      validatorRewards: BigDecimal = zero,
      trafficPurchased: Long = 0L,
      trafficPurchasedCcSpent: BigDecimal = zero,
      trafficNumPurchases: Long = 0L,
      cumulativeAppRewards: BigDecimal = zero,
      cumulativeValidatorRewards: BigDecimal = zero,
      cumulativeChangeToInitialAmountAsOfRoundZero: BigDecimal = zero,
      cumulativeChangeToHoldingFeesRate: BigDecimal = zero,
      cumulativeTrafficPurchased: Long = 0L,
      cumulativeTrafficPurchasedCcSpent: BigDecimal = zero,
      cumulativeTrafficNumPurchases: Long = 0L,
  )
  implicit val GetResultRoundPartyTotals: GetResult[RoundPartyTotals] =
    GetResult { prs =>
      import prs.*
      (RoundPartyTotals.apply _).tupled(
        (
          <<[Int],
          <<[Long],
          <<[String],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[Long],
          <<[BigDecimal],
          <<[Long],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[BigDecimal],
          <<[Long],
          <<[BigDecimal],
          <<[Long],
        )
      )
    }
  val roundPartyTotalsColumns = """
  store_id,
  closed_round,
  party,
  coalesce(app_rewards, 0),
  coalesce(validator_rewards, 0),
  coalesce(traffic_purchased, 0),
  coalesce(traffic_purchased_cc_spent, 0),
  coalesce(traffic_num_purchases, 0),
  coalesce(cumulative_app_rewards, 0),
  coalesce(cumulative_validator_rewards, 0),
  coalesce(cumulative_change_to_initial_amount_as_of_round_zero, 0),
  coalesce(cumulative_change_to_holding_fees_rate, 0),
  coalesce(cumulative_traffic_purchased, 0),
  coalesce(cumulative_traffic_purchased_cc_spent, 0),
  coalesce(cumulative_traffic_num_purchases, 0)
  """
}
