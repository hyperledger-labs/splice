// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
import org.lfdecentralizedtrust.splice.util.QualifiedName
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
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.SummarizingMiningRound
import ScanAggregator.*
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.EntryType
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.lifecycle.SyncCloseable
import com.digitalasset.canton.config.ProcessingTimeout
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId

final class ScanAggregator(
    storage: DbStorage,
    val acsStoreId: AcsStoreId,
    val txLogStoreId: TxLogStoreId,
    isFirstSv: Boolean,
    reader: ScanAggregatesReader,
    val loggerFactory: NamedLoggerFactory,
    domainMigrationId: Long,
    val timeouts: ProcessingTimeout,
    val initialRound: Int,
)(implicit
    ec: ExecutionContext,
    closeContext: CloseContext,
) extends NamedLogging
    with AcsJdbcTypes
    with FlagCloseableAsync {
  val profile: slick.jdbc.JdbcProfile = PostgresProfile
  import profile.api.jdbcActionExtensionMethods
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  // Round totals are derived from TxLog entries, and are therefore linked to that store
  private[this] def roundTotalsStoreId: TxLogStoreId = txLogStoreId

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq(SyncCloseable("scan_aggregates_reader", reader.close()))
  }

  /** Aggregates RoundTotals and RoundPartyTotals in batches and reports the most recently aggregated RoundTotals. */
  def aggregate()(implicit traceContext: TraceContext): Future[Option[RoundTotals]] = {
    logger.debug("Aggregation triggered.")
    (for {
      previousRoundTotals <- ensureConsecutiveAggregation()
      lastClosedRoundO <- getLastCompletelyClosedRoundAfter(previousRoundTotals.map(_.closedRound))
      _ <- lastClosedRoundO match {
        case Some(lastClosedRound) =>
          for {
            _ <- storage.update_(
              aggregateRoundTotals(previousRoundTotals, lastClosedRound).andThen(
                aggregateRoundPartyTotals(lastClosedRound).transactionally
              ),
              "aggregate round totals and round party totals",
            )
          } yield ()
        case None =>
          skipAggregation(previousRoundTotals)
      }
      lastAggregated <- getLastAggregatedRoundTotals()
    } yield lastAggregated).recover {
      case Skip(msg) =>
        logger.debug(msg)
        None
      case CannotAdvance(msg) =>
        logger.debug(msg)
        None
    }
  }

  def backFillAggregates()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    (for {
      earliestClosedRound <- getEarliestAggregatedRound()
      res <-
        earliestClosedRound match {
          case None =>
            logger.debug(
              s"No closed round found in round_totals, not backfilling aggregates with initial round = $initialRound."
            )
            Future.successful(None)
          case Some(round) if round == initialRound =>
            logger.debug(
              s"Initial round $initialRound exists. No need to backfill aggregates."
            )
            Future.successful(Some(round))
          case Some(closedRound) =>
            val backFillRound = closedRound - 1L
            logger.debug(
              s"Backfilling round $backFillRound. (Earliest closed round = $closedRound)"
            )
            backFill(backFillRound).map(_ => Some(backFillRound))
        }
    } yield res).recover { case CannotAdvance(msg) =>
      logger.debug(msg)
      None
    }
  }

  def backFill(round: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    for {
      aggregatedRound <- reader.readRoundAggregateFromDso(round)
      res <- aggregatedRound match {
        case Some(RoundAggregate(rt, rpt)) =>
          storage
            .update_(
              insertRoundTotals(rt).andThen(insertRoundPartyTotals(rpt)).transactionally,
              "backfill insert round aggregates from dso",
            )
            .map(_ => ())
        case None =>
          Future.failed(
            CannotAdvance(
              s"Could not read aggregates for round $round from dso while backfilling aggregates."
            )
          )
      }
    } yield res
  }

  private def skipAggregation(
      previousRoundTotals: Option[RoundTotals]
  ): Future[_] = {
    previousRoundTotals
      .map { lastAggregated =>
        Future.failed(
          Skip(
            s"Skipped aggregation, no new completely closed rounds to aggregate yet, previously aggregated round: ${lastAggregated.closedRound}, store_id = $roundTotalsStoreId"
          )
        )
      }
      .getOrElse(
        Future.failed(
          Skip(
            s"Skipped aggregation, no completely closed rounds to aggregate yet, store_id = $roundTotalsStoreId"
          )
        )
      )
  }

  def ensureConsecutiveAggregation()(implicit
      traceContext: TraceContext
  ): Future[Option[RoundTotals]] =
    for {
      lastRoundTotals <- getLastAggregatedRoundTotals()
      previousRoundTotals <- lastRoundTotals match {
        case Some(prev) =>
          logger.debug(
            s"Aggregation continues from previously aggregated round: ${prev.closedRound}, store_id = $roundTotalsStoreId"
          )
          Future.successful(lastRoundTotals)
        case None =>
          if (isFirstSv) {
            logger.debug(
              s"Aggregation starts from round $initialRound, store_id = $roundTotalsStoreId"
            )
            Future.successful(None)
          } else {
            for {
              openRound <- findFirstOpenMiningRound()
              prev <- openRound match {
                case Some(round) if round == initialRound =>
                  logger.debug(
                    s"Updating aggregates from DSO for round $round, store_id = $roundTotalsStoreId"
                  )
                  updateRoundAggregateFromDso(round)
                case Some(round) if round > initialRound =>
                  logger.debug(
                    s"Aggregation starts from round $round once last aggregates are updated from DSO, store_id = $roundTotalsStoreId"
                  )
                  logger.debug(
                    s"Updating aggregates from DSO for round ${round - 1}, before the first detected open mining round: $round, store_id = $roundTotalsStoreId"
                  )
                  updateRoundAggregateFromDso(round - 1)
                case Some(round) =>
                  Future.failed(
                    CannotAdvance(
                      s"Unexpected negative open mining round: $round, store_id = $roundTotalsStoreId"
                    )
                  )
                case None =>
                  // note: getLastCompletelyClosedRoundAfter checks for consecutive closed rounds, so no need to fail here.
                  logger.debug(
                    s"No first open mining round found yet, store_id = $roundTotalsStoreId"
                  )
                  Future.successful(None)
              }
            } yield prev
          }
      }
    } yield {
      previousRoundTotals
    }

  def updateRoundAggregateFromDso(
      round: Long
  )(implicit traceContext: TraceContext): Future[Option[RoundTotals]] = {
    for {
      aggregatedRound <- reader.readRoundAggregateFromDso(round)
      lastRoundTotals <- aggregatedRound match {
        case Some(RoundAggregate(rt, rpt)) =>
          for {
            _ <- storage.update_(
              insertRoundTotals(rt).andThen(insertRoundPartyTotals(rpt)).transactionally,
              "insert round aggregates from dso",
            )
            lastRoundTotals <- getLastAggregatedRoundTotals()
          } yield lastRoundTotals
        case None =>
          Future.failed(
            CannotAdvance(
              s"Could not read aggregates for round $round from dso"
            )
          )
      }
    } yield lastRoundTotals
  }

  def insertRoundTotals(
      rt: RoundTotals
  ): DBIOAction[Unit, NoStream, Effect.Write] = {
    sql"""
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
        total_amulet_balance
      )
      values (
        ${roundTotalsStoreId},
        ${rt.closedRound},
        ${rt.closedRoundEffectiveAt},
        ${rt.appRewards},
        ${rt.validatorRewards},
        ${rt.changeToInitialAmountAsOfRoundZero},
        ${rt.changeToHoldingFeesRate},
        ${rt.cumulativeAppRewards},
        ${rt.cumulativeValidatorRewards},
        ${rt.cumulativeChangeToInitialAmountAsOfRoundZero},
        ${rt.cumulativeChangeToHoldingFeesRate},
        ${rt.totalAmuletBalance}
      )
      on conflict do nothing
    """.asUpdate.map(_ => ())
  }

  def insertRoundPartyTotals(
      roundPartyTotals: Vector[RoundPartyTotals]
  ): DBIOAction[Unit, NoStream, Effect.Write & Effect.Transactional] = {
    def insert(rpt: RoundPartyTotals) = {
      sql"""
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
      values (
        ${roundTotalsStoreId},
        ${rpt.closedRound},
        ${lengthLimited(rpt.party)},
        ${rpt.appRewards},
        ${rpt.validatorRewards},
        ${rpt.trafficPurchased},
        ${rpt.trafficPurchasedCcSpent},
        ${rpt.trafficNumPurchases},
        ${rpt.cumulativeAppRewards},
        ${rpt.cumulativeValidatorRewards},
        ${rpt.cumulativeTrafficPurchased},
        ${rpt.cumulativeTrafficPurchasedCcSpent},
        ${rpt.cumulativeTrafficNumPurchases},
        ${rpt.cumulativeChangeToInitialAmountAsOfRoundZero},
        ${rpt.cumulativeChangeToHoldingFeesRate}
      )
      on conflict do nothing
    """.asUpdate
    }
    DBIOAction
      .sequence(roundPartyTotals.map(insert))
      .map(_ => ())
      .transactionally
  }

  def getLastAggregatedRoundTotals()(implicit
      traceContext: TraceContext
  ): Future[Option[RoundTotals]] = {
    val q = sql"""
      select #$roundTotalsColumns
      from   round_totals
      where  store_id = $roundTotalsStoreId
      and    closed_round = (select coalesce(max(closed_round), $initialRound) from round_totals where store_id = $roundTotalsStoreId)
    """
    storage
      .querySingle(
        q.as[RoundTotals].headOption,
        "getLastAggregatedRoundTotals",
      )
      .value
  }

  def getEarliestAggregatedRound()(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val q = sql"""
      select closed_round
      from   round_totals
      where  store_id = $roundTotalsStoreId
      and    closed_round = (select coalesce(min(closed_round), $initialRound) from round_totals where store_id = $roundTotalsStoreId)
    """
    storage
      .querySingle(
        q.as[Option[Long]].headOption,
        "getLastAggregatedRoundTotals",
      )
      .value
      .map(_.flatten)
  }

  def getRoundTotals(round: Long)(implicit
      traceContext: TraceContext
  ): Future[Option[RoundTotals]] = {
    val q = sql"""
      select #$roundTotalsColumns
      from   round_totals
      where  store_id = $roundTotalsStoreId
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
      where    store_id = $roundTotalsStoreId
      and      closed_round = $round
      order by party
    """
    storage
      .query(
        q.as[RoundPartyTotals],
        "getRoundPartyTotals",
      )
  }

  private[this] val closedRoundsSinceLimit = 100

  /** Returns the last completely closed round for which no more events can occur, after the previously aggregated round.
    * (no incomplete reassignments, no open, issuing or summarizing rounds)
    * and all previous rounds are closed.
    */
  def getLastCompletelyClosedRoundAfter(
      lastAggregatedRoundO: Option[Long],
      closedRoundsSinceLimit: Int = closedRoundsSinceLimit,
  )(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val lastAggregatedRound = lastAggregatedRoundO.getOrElse(-1L)
    def go(candidateLastRound: Long): Future[Option[Long]] = for {
      rounds <- getClosedMiningRoundsSinceLastAggregated(candidateLastRound)
      _ = if (rounds.nonEmpty) logger.debug {
        val roundsReported = rounds.mkString(", ")
        s"Closed mining rounds since last aggregated: $roundsReported"
      }
      // TODO (#799): find a different approach for finding in-flight closed rounds
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
      availableClosedRounds = (rounds
        .map(_._1)
        .toSet -- (roundsIncomplete ++ roundsOpenIssuingOrSummarizing).toSet).toVector.sorted
      lastRound = availableClosedRounds.foldLeft(candidateLastRound) { (lastClosedRound, round) =>
        if (lastClosedRound == -1) round
        else if (round == lastClosedRound + 1) round
        else lastClosedRound
      }
      result <-
        if ( // whether there might be another page
          rounds.sizeIs >= closedRoundsSinceLimit
          // whether the fold above might continue onto the next page
          && availableClosedRounds.lastOption.contains(lastRound)
        ) {
          // effectively, if the fold above didn't terminate before the last
          // element, it might keep folding if we retrieve more closed rounds
          go(lastRound)
        } else
          Future successful {
            if (lastRound == lastAggregatedRound) {
              logger.debug(
                s"LastRound $lastRound is equal to lastAggregatedRound $lastAggregatedRound"
              )
              None
            } else if (lastRound < lastAggregatedRound) {
              logger.error(
                s"The last completely closed round: ${lastRound} is smaller than the previously aggregated round: ${lastAggregatedRound}"
              )
              None
            } else {
              if (lastAggregatedRound == -1) {
                if (isFirstSv) {
                  // only allowed to start from round zero with no previous total rounds if the scan is reading from participant begin (the sv1)
                  Some(lastRound)
                } else {
                  logger.debug(
                    s"Cannot start from round zero when not reading from participant-begin, store_id = $roundTotalsStoreId"
                  )
                  None
                }
              } else {
                Some(lastRound)
              }
            }
          }
    } yield result
    go(lastAggregatedRound)
  }

  def findFirstOpenMiningRound()(implicit
      traceContext: TraceContext
  ): Future[Option[Long]] = {
    val q = sql"""
      select   round
      from     scan_txlog_store
      where    store_id = $txLogStoreId
      and      entry_type = ${EntryType.OpenMiningRoundTxLogEntry}
      order by round asc
      limit 1
    """
    storage
      .querySingle(
        q.as[Long].headOption,
        "findFirstOpenMiningRound",
      )
      .value
  }

  // invariant: every result element has _1 > lastAggregatedRound
  // invariant: ordered by _1
  private def getClosedMiningRoundsSinceLastAggregated(
      lastAggregatedRound: Long,
      closedRoundsSinceLimit: Int = closedRoundsSinceLimit,
  )(implicit
      traceContext: TraceContext
  ) =
    storage
      .query(
        sql"""
            select   round,
                     -- TODO (#799) This query was using the deprecated acs_contract_id which ends up always being null
                     null
            from     scan_txlog_store
            where    store_id = $txLogStoreId
            and      entry_type = ${EntryType.ClosedMiningRoundTxLogEntry}
            and      round > $lastAggregatedRound
            order by round asc
            limit $closedRoundsSinceLimit
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
                where    store_id = $acsStoreId
                and      migration_id = $domainMigrationId
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
              where    store_id = $acsStoreId
              and      migration_id = $domainMigrationId
              and      round = $round
              and
              (
                template_id_qualified_name = ${QualifiedName(
                OpenMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID
              )} or
                template_id_qualified_name = ${QualifiedName(
                IssuingMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID
              )} or
                template_id_qualified_name = ${QualifiedName(
                SummarizingMiningRound.TEMPLATE_ID_WITH_PACKAGE_ID
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

  /** Aggregates the RoundTotals in the round_totals table,
    * calculating cumulative sums starting from previous round_totals, inserting the new sums and cumulative sums,
    * up to including the last closed round.
    */
  def aggregateRoundTotals(
      previousRoundTotalsO: Option[RoundTotals],
      lastClosedRound: Long,
  )(implicit
      traceContext: TraceContext
  ): DBIOAction[Unit, NoStream, Effect.Write] = {
    val previousRoundTotals = previousRoundTotalsO.getOrElse(
      RoundTotals(closedRound = -1L)
    )
    def doQuery() = {
      sql"""
      with new_totals_per_entry as(
        select    round,
                  0 as closed_round_effective_at,
                  sum(reward_amount) as app_rewards,
                  0 as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $txLogStoreId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.AppRewardTxLogEntry}
        group by  round
        union all
        select    round,
                  0 as closed_round_effective_at,
                  0 as app_rewards,
                  sum(reward_amount) as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $txLogStoreId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.ValidatorRewardTxLogEntry}
        group by  round
        union all
        select    round,
                  0 as closed_round_effective_at,
                  0 as app_rewards,
                  0 as validator_rewards,
                  sum(balance_change_change_to_initial_amount_as_of_round_zero) as change_to_initial_amount_as_of_round_zero,
                  sum(balance_change_change_to_holding_fees_rate) as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $txLogStoreId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.BalanceChangeTxLogEntry}
        group by  round
        union all
        select    round,
                  max(coalesce(closed_round_effective_at, $initialRound)) as closed_round_effective_at,
                  0 as app_rewards,
                  0 as validator_rewards,
                  0 as change_to_initial_amount_as_of_round_zero,
                  0 as change_to_holding_fees_rate
        from      scan_txlog_store
        where     store_id = $txLogStoreId
        and       round > ${previousRoundTotals.closedRound}
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.ClosedMiningRoundTxLogEntry}
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
                  total_amulet_balance
      )
      select      $roundTotalsStoreId,
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
    """.asUpdate.andThen(DBIO.successful(()))
    }
    val extraMsg = previousRoundTotalsO
      .map(pr => s", previously aggregated round: ${pr.closedRound}")
      .getOrElse(", from the beginning")
    logger.info(
      s"""Aggregating round_totals for rounds up to including round ${lastClosedRound}$extraMsg."""
    )
    doQuery()
  }

  /** Aggregates the RoundPartyTotals in the round_party_totals table,
    * calculating cumulative sums starting from previous round_party_totals, inserting the new sums and cumulative sums,
    * up to including the last closed round.
    */
  def aggregateRoundPartyTotals(
      lastClosedRound: Long
  )(implicit
      traceContext: TraceContext
  ): DBIOAction[Unit, NoStream, Effect.Write] = {

    /** Initialization of active_parties is done in an idempotent stored proc (Effect.Write).
      * This is done outside of flyway migration so that it does not impact scan app startup time.
      */
    def initializeActiveParties(): DBIOAction[Unit, NoStream, Effect.Write] = {
      sqlu"""call initialize_active_parties()"""
        .andThen(DBIO.successful(()))
    }

    /** The round_party_totals will only contain rows for parties which have balance change or have been rewarded or purchased extra traffic in rounds.
      * Sum per round and party, where the party is the rewarded_party or extra_traffic_validator,
      * since the most recently recorded rounds in round_party_totals, up until including the last closed round.
      * Gets the previous cumulative sums for each party from the active_parties table, which keeps track of the most recent round that registered activity for that party
      * (parties can be missing in rounds, since they did not get rewarded, or did not buy traffic).
      * Calculate cumulative sums left joining with possibly existing previous cumulative sums per party, keeping track of the most recent round per party.
      * The left join with sums over all rounds grouped by parties ensures that all parties balance changes, rewards and purchases are included in the next result,
      * and that it is safe to start the aggregation from any previously aggregated round, even if some parties are missing in some rounds.
      * finally inserting the sums and cumulative sums in the round_party_totals table.
      */
    def aggregate(): DBIOAction[Unit, NoStream, Effect.Write] = {
      sqlu"""
      create temp table active_parties_before on commit drop as
      select party,
             (select max(closed_round) from active_parties where store_id = $roundTotalsStoreId) as aggr_round,
             closed_round as active_round
      from   active_parties
      where  store_id = $roundTotalsStoreId;

      create temp table temp_cumulative_totals on commit drop as
      with previously_aggregated as(
        select coalesce(max(closed_round), -1) as last_closed_round from round_party_totals where store_id = $roundTotalsStoreId
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
        where     store_id = $txLogStoreId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.AppRewardTxLogEntry}
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
        where     store_id = $txLogStoreId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.ValidatorRewardTxLogEntry}
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
        where     store_id = $txLogStoreId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.ExtraTrafficPurchaseTxLogEntry}
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
        where     store_id = $txLogStoreId
        and       round > (select last_closed_round from previously_aggregated)
        and       round <= $lastClosedRound
        and       entry_type = ${EntryType.BalanceChangeTxLogEntry}
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
        select  rpt.party as party,
                coalesce(cumulative_app_rewards, 0) as prev_cumulative_app_rewards,
                coalesce(cumulative_validator_rewards, 0) as prev_cumulative_validator_rewards,
                coalesce(cumulative_traffic_purchased, 0) as prev_cumulative_traffic_purchased,
                coalesce(cumulative_traffic_purchased_cc_spent, 0) as prev_cumulative_traffic_purchased_cc_spent,
                coalesce(cumulative_traffic_num_purchases, 0) as prev_cumulative_traffic_num_purchases,
                coalesce(cumulative_change_to_initial_amount_as_of_round_zero, 0) as prev_cumulative_change_to_initial_amount_as_of_round_zero,
                coalesce(cumulative_change_to_holding_fees_rate, 0) as prev_cumulative_change_to_holding_fees_rate
        from    round_party_totals rpt
        join    active_parties ap
        on      rpt.store_id = ap.store_id
        and     rpt.party = ap.party
        and     rpt.closed_round = ap.closed_round
        where   rpt.store_id = $roundTotalsStoreId
      )
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
      on        nt.party = pt.party;

      insert into active_parties (store_id, party, closed_round)
      select $roundTotalsStoreId, party, max(round)
      from temp_cumulative_totals
      group by party
      on conflict (store_id, party)
      do update set closed_round = excluded.closed_round;

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
      select      $roundTotalsStoreId,
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
      from        temp_cumulative_totals ct
      on conflict do nothing;

      -- added for total_amulet_balance until TODO(#800) is fixed
      -- the active_parties_for_aggr_rounds contains for every party, the round it was active (active_round),
      -- when the round was aggregated (aggr_round).
      -- Enables getting the cumulative values per party for when that party was active,
      -- for the aggregated round, from the round_party_totals table.
      create temp table active_parties_for_aggr_rounds on commit drop as
      select party, aggr_round as aggr_round, active_round from active_parties_before
      union
      -- all the parties that were active in the newly aggregated rounds
      select party, round as aggr_round, round as active_round from temp_cumulative_totals
      union
      -- adding the lastClosedRound as aggr_round, for parties that were not active but have been aggregated in that round
      select party,
             $lastClosedRound as aggr_round,
             closed_round as active_round
      from   active_parties
      where  store_id = $roundTotalsStoreId;

      insert into round_total_amulet_balance (
        store_id,
        closed_round,
        sum_cumulative_change_to_initial_amount_as_of_round_zero,
        sum_cumulative_change_to_holding_fees_rate
      )
      select   $roundTotalsStoreId,
               ap.aggr_round,
               sum(rpt.cumulative_change_to_initial_amount_as_of_round_zero),
               sum(rpt.cumulative_change_to_holding_fees_rate)
      from     round_party_totals rpt
      join     active_parties_for_aggr_rounds ap
      on       rpt.closed_round = ap.active_round
      and      rpt.party = ap.party
      and      rpt.store_id = $roundTotalsStoreId
      group by ap.aggr_round
      on conflict (store_id, closed_round)
      do update set sum_cumulative_change_to_initial_amount_as_of_round_zero = excluded.sum_cumulative_change_to_initial_amount_as_of_round_zero,
        sum_cumulative_change_to_holding_fees_rate = excluded.sum_cumulative_change_to_holding_fees_rate;
    """.andThen(DBIOAction.successful(()))
    }
    logger.info(
      s"Aggregating round_party_totals for rounds up to including round ${lastClosedRound}."
    )
    for {
      _ <- initializeActiveParties()
      _ <- aggregate()
    } yield ()
  }
}

object ScanAggregator {
  sealed trait Error
  object Skip {
    def apply(msg: String): Skip = new Skip(msg)
  }
  final case class Skip(msg: String) extends RuntimeException(msg) with Error
  object CannotAdvance {
    def apply(msg: String): CannotAdvance = new CannotAdvance(msg)
  }
  final case class CannotAdvance(msg: String) extends RuntimeException(msg) with Error

  type Party = String
  val zero = BigDecimal(0)

  final case class RoundAggregate(
      roundTotals: RoundTotals,
      roundPartyTotals: Vector[RoundPartyTotals],
  ) {
    def closedRound = roundTotals.closedRound
  }

  final case class RoundRange(start: Long, end: Long) {
    def contains(round: Long): Boolean = round >= start && round <= end
  }
  final case class RoundTotals(
      closedRound: Long,
      closedRoundEffectiveAt: CantonTimestamp = CantonTimestamp.MinValue,
      appRewards: BigDecimal = zero,
      validatorRewards: BigDecimal = zero,
      changeToInitialAmountAsOfRoundZero: BigDecimal = zero,
      changeToHoldingFeesRate: BigDecimal = zero,
      cumulativeAppRewards: BigDecimal = zero,
      cumulativeValidatorRewards: BigDecimal = zero,
      cumulativeChangeToInitialAmountAsOfRoundZero: BigDecimal = zero,
      cumulativeChangeToHoldingFeesRate: BigDecimal = zero,
      totalAmuletBalance: BigDecimal = zero,
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
    coalesce(total_amulet_balance, 0)
    """

  implicit val GetResultRoundTotals: GetResult[RoundTotals] =
    GetResult { prs =>
      import prs.*
      (RoundTotals.apply _).tupled(
        (
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
      closedRound: Long,
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
