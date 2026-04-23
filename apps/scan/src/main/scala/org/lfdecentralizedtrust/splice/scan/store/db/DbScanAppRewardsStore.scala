// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.scan.rewards.{RewardComputationInputs, RewardIssuanceParams}
import org.lfdecentralizedtrust.splice.scan.store.ScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}

import scala.concurrent.{ExecutionContext, Future}

/** Row types and store for the CIP-0104 reward accounting tables.
  *
  * Covers six tables populated by RewardComputationTrigger:
  *   - app_activity_party_totals / app_activity_round_totals — per-party and per-round
  *     aggregation of traffic-weighted app activity.
  *   - app_reward_party_totals / app_reward_round_totals — per-party minting allowances
  *     and per-round reward summaries after applying the CIP-0104 reward formula.
  *   - app_reward_batch_hashes / app_reward_root_hashes — Merkle tree of batched reward
  *     commitments for verifiable on-ledger reward coupon creation.
  */
object DbScanAppRewardsStore {

  final case class AppActivityPartyTotalT(
      historyId: Long,
      roundNumber: Long,
      totalAppActivityWeight: Long,
      appProviderParty: String,
      numActivityRecords: Long,
  )

  final case class AppActivityRoundTotalT(
      historyId: Long,
      roundNumber: Long,
      totalRoundAppActivityWeight: Long,
      activeAppProviderPartiesCount: Long,
      activityRecordsCount: Long,
  )

  final case class AppRewardPartyTotalT(
      historyId: Long,
      roundNumber: Long,
      appProviderPartySeqNum: Int,
      appProviderParty: String,
      totalAppRewardAmount: BigDecimal,
  )

  final case class AppRewardRoundTotalT(
      historyId: Long,
      roundNumber: Long,
      totalAppRewardMintingAllowance: BigDecimal,
      totalAppRewardThresholded: BigDecimal,
      totalAppRewardUnclaimed: BigDecimal,
      rewardedAppProviderPartiesCount: Long,
  )

  final case class AppRewardBatchHashT(
      historyId: Long,
      roundNumber: Long,
      batchLevel: Int,
      partySeqNumBeginIncl: Int,
      partySeqNumEndExcl: Int,
      batchHash: RewardHash,
  )

  final case class AppRewardRootHashT(
      historyId: Long,
      roundNumber: Long,
      rootHash: RewardHash,
  )

  /** Summary of a single round's reward computation, for metrics reporting. */
  final case class RewardComputationSummary(
      activePartiesCount: Long,
      activityRecordsCount: Long,
      rewardedPartiesCount: Long,
      batchesCreatedCount: Long,
  )

  /** A SHA-256 hash stored as raw bytes, avoiding unnecessary Array[Byte] ↔
    * ByteString conversions at the DB boundary. Conversion to hex strings
    * for the HTTP layer is done via `toHex` / `fromHex`.
    */
  final case class RewardHash(bytes: Array[Byte]) {
    def toHex: String = bytes.map("%02x".format(_)).mkString
    def size: Int = bytes.length

    override def equals(obj: Any): Boolean = obj match {
      case other: RewardHash => java.util.Arrays.equals(bytes, other.bytes)
      case _ => false
    }
    override def hashCode(): Int = java.util.Arrays.hashCode(bytes)
  }

  object RewardHash {
    def fromHex(hex: String): RewardHash =
      RewardHash(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)
  }

  final case class MintingAllowance(provider: String, amount: BigDecimal)

  sealed trait BatchContents
  case class BatchOfBatches(childHashes: Seq[RewardHash]) extends BatchContents
  case class BatchOfMintingAllowances(allowances: Seq[MintingAllowance]) extends BatchContents
}

class DbScanAppRewardsStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    appActivityRecordStore: DbAppActivityRecordStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends ScanAppRewardsStore
    with NamedLogging
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries {

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val appActivityPartyTotals = "app_activity_party_totals"
    val appActivityRoundTotals = "app_activity_round_totals"
    val appRewardPartyTotals = "app_reward_party_totals"
    val appRewardRoundTotals = "app_reward_round_totals"
    val appRewardBatchHashes = "app_reward_batch_hashes"
    val appRewardRootHashes = "app_reward_root_hashes"
  }

  private def historyId = updateHistory.historyId

  // -- GetResult implicits --------------------------------------------------

  private implicit val getResultAppActivityPartyTotal
      : GetResult[DbScanAppRewardsStore.AppActivityPartyTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppActivityPartyTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      totalAppActivityWeight = prs.<<[Long],
      appProviderParty = prs.<<[String],
      numActivityRecords = prs.<<[Long],
    )
  }

  private implicit val getResultAppActivityRoundTotal
      : GetResult[DbScanAppRewardsStore.AppActivityRoundTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppActivityRoundTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      totalRoundAppActivityWeight = prs.<<[Long],
      activeAppProviderPartiesCount = prs.<<[Long],
      activityRecordsCount = prs.<<[Long],
    )
  }

  private implicit val getResultAppRewardPartyTotal
      : GetResult[DbScanAppRewardsStore.AppRewardPartyTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardPartyTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      appProviderPartySeqNum = prs.<<[Int],
      appProviderParty = prs.<<[String],
      totalAppRewardAmount = prs.<<[BigDecimal],
    )
  }

  private implicit val getResultAppRewardRoundTotal
      : GetResult[DbScanAppRewardsStore.AppRewardRoundTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardRoundTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      totalAppRewardMintingAllowance = prs.<<[BigDecimal],
      totalAppRewardThresholded = prs.<<[BigDecimal],
      totalAppRewardUnclaimed = prs.<<[BigDecimal],
      rewardedAppProviderPartiesCount = prs.<<[Long],
    )
  }

  private implicit val getResultAppRewardBatchHash
      : GetResult[DbScanAppRewardsStore.AppRewardBatchHashT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardBatchHashT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      batchLevel = prs.<<[Int],
      partySeqNumBeginIncl = prs.<<[Int],
      partySeqNumEndExcl = prs.<<[Int],
      batchHash = DbScanAppRewardsStore.RewardHash(prs.<<[Array[Byte]]),
    )
  }

  private implicit val getResultAppRewardRootHash
      : GetResult[DbScanAppRewardsStore.AppRewardRootHashT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardRootHashT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      rootHash = DbScanAppRewardsStore.RewardHash(prs.<<[Array[Byte]]),
    )
  }

  private implicit val getResultRewardComputationSummary
      : GetResult[DbScanAppRewardsStore.RewardComputationSummary] = GetResult { prs =>
    DbScanAppRewardsStore.RewardComputationSummary(
      activePartiesCount = prs.<<[Long],
      activityRecordsCount = prs.<<[Long],
      rewardedPartiesCount = prs.<<[Long],
      batchesCreatedCount = prs.<<[Long],
    )
  }

  // -- app_activity_party_totals --------------------------------------------

  private def batchInsertAppActivityPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityPartyTotalT]
  ) = {
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.totalAppActivityWeight},
              ${row.appProviderParty},
              ${row.numActivityRecords})"""
      })
      (sql"""insert into #${Tables.appActivityPartyTotals}(
              history_id, round_number, total_app_activity_weight,
              app_provider_party,
              num_activity_records
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppActivityPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityPartyTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppActivityPartyTotals(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app activity party totals."))
          .transactionally,
        "appRewards.insertAppActivityPartyTotals",
      )
    }
  }

  def getAppActivityPartyTotalsByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppActivityPartyTotalT]] = {

    runQuery(
      sql"""select history_id, round_number, total_app_activity_weight,
                   app_provider_party,
                   num_activity_records
            from #${Tables.appActivityPartyTotals}
            where history_id = $historyId and round_number = $roundNumber
            order by app_provider_party
      """.as[DbScanAppRewardsStore.AppActivityPartyTotalT],
      "appRewards.getAppActivityPartyTotalsByRound",
    )
  }

  // -- app_activity_round_totals --------------------------------------------

  private def batchInsertAppActivityRoundTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityRoundTotalT]
  ) = {
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.totalRoundAppActivityWeight},
              ${row.activeAppProviderPartiesCount}, ${row.activityRecordsCount})"""
      })
      (sql"""insert into #${Tables.appActivityRoundTotals}(
              history_id, round_number, total_round_app_activity_weight,
              active_app_provider_parties_count, activity_records_count
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppActivityRoundTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityRoundTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppActivityRoundTotals(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app activity round totals."))
          .transactionally,
        "appRewards.insertAppActivityRoundTotals",
      )
    }
  }

  def getAppActivityRoundTotalByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppActivityRoundTotalT]] = {

    runQuerySingle(
      sql"""select history_id, round_number, total_round_app_activity_weight,
                   active_app_provider_parties_count, activity_records_count
            from #${Tables.appActivityRoundTotals}
            where history_id = $historyId and round_number = $roundNumber
            limit 1
      """.as[DbScanAppRewardsStore.AppActivityRoundTotalT].headOption,
      "appRewards.getAppActivityRoundTotalByRound",
    )
  }

  /** DBIO action to read the total activity weight for a round. */
  // .head is safe: only called after aggregateActivityTotalsAction in the same transaction.
  private def getAppActivityRoundTotalWeightAction(roundNumber: Long) =
    sql"""select total_round_app_activity_weight
          from #${Tables.appActivityRoundTotals}
          where history_id = $historyId and round_number = $roundNumber
    """.as[Long].head

  // -- app_reward_party_totals ----------------------------------------------

  private def batchInsertAppRewardPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]
  ) = {
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.appProviderPartySeqNum},
              ${row.appProviderParty}, ${row.totalAppRewardAmount})"""
      })
      (sql"""insert into #${Tables.appRewardPartyTotals}(
              history_id, round_number, app_provider_party_seq_num,
              app_provider_party, total_app_reward_amount
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppRewardPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppRewardPartyTotals(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app reward party totals."))
          .transactionally,
        "appRewards.insertAppRewardPartyTotals",
      )
    }
  }

  def getAppRewardPartyTotalsByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]] = {

    runQuery(
      sql"""select history_id, round_number, app_provider_party_seq_num,
                   app_provider_party, total_app_reward_amount
            from #${Tables.appRewardPartyTotals}
            where history_id = $historyId and round_number = $roundNumber
            order by app_provider_party_seq_num
      """.as[DbScanAppRewardsStore.AppRewardPartyTotalT],
      "appRewards.getAppRewardPartyTotalsByRound",
    )
  }

  // -- app_reward_round_totals ----------------------------------------------

  private def batchInsertAppRewardRoundTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardRoundTotalT]
  ) = {
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber},
              ${row.totalAppRewardMintingAllowance}, ${row.totalAppRewardThresholded},
              ${row.totalAppRewardUnclaimed}, ${row.rewardedAppProviderPartiesCount})"""
      })
      (sql"""insert into #${Tables.appRewardRoundTotals}(
              history_id, round_number,
              total_app_reward_minting_allowance, total_app_reward_thresholded,
              total_app_reward_unclaimed, rewarded_app_provider_parties_count
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppRewardRoundTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardRoundTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppRewardRoundTotals(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app reward round totals."))
          .transactionally,
        "appRewards.insertAppRewardRoundTotals",
      )
    }
  }

  def getAppRewardRoundTotalByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppRewardRoundTotalT]] = {

    runQuerySingle(
      sql"""select history_id, round_number,
                   total_app_reward_minting_allowance, total_app_reward_thresholded,
                   total_app_reward_unclaimed, rewarded_app_provider_parties_count
            from #${Tables.appRewardRoundTotals}
            where history_id = $historyId and round_number = $roundNumber
            limit 1
      """.as[DbScanAppRewardsStore.AppRewardRoundTotalT].headOption,
      "appRewards.getAppRewardRoundTotalByRound",
    )
  }

  // -- app_reward_batch_hashes ----------------------------------------------

  private def batchInsertAppRewardBatchHashes(
      items: Seq[DbScanAppRewardsStore.AppRewardBatchHashT]
  ) = {
    import storage.DbStorageConverters.setParameterByteArray
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.batchLevel},
              ${row.partySeqNumBeginIncl}, ${row.partySeqNumEndExcl}, ${row.batchHash.bytes})"""
      })
      (sql"""insert into #${Tables.appRewardBatchHashes}(
              history_id, round_number, batch_level,
              party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppRewardBatchHashes(
      items: Seq[DbScanAppRewardsStore.AppRewardBatchHashT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppRewardBatchHashes(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app reward batch hashes."))
          .transactionally,
        "appRewards.insertAppRewardBatchHashes",
      )
    }
  }

  def getAppRewardBatchHashesByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppRewardBatchHashT]] = {

    runQuery(
      sql"""select history_id, round_number, batch_level,
                   party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash
            from #${Tables.appRewardBatchHashes}
            where history_id = $historyId and round_number = $roundNumber
            order by batch_level, party_seq_num_begin_incl
      """.as[DbScanAppRewardsStore.AppRewardBatchHashT],
      "appRewards.getAppRewardBatchHashesByRound",
    )
  }

  // -- app_reward_root_hashes -----------------------------------------------

  private def batchInsertAppRewardRootHashes(
      items: Seq[DbScanAppRewardsStore.AppRewardRootHashT]
  ) = {
    import storage.DbStorageConverters.setParameterByteArray
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.rootHash.bytes})"""
      })
      (sql"""insert into #${Tables.appRewardRootHashes}(
              history_id, round_number, root_hash
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppRewardRootHashes(
      items: Seq[DbScanAppRewardsStore.AppRewardRootHashT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      runUpdate(
        batchInsertAppRewardRootHashes(items)
          .map(_ => logger.debug(s"Inserted ${items.size} app reward root hashes."))
          .transactionally,
        "appRewards.insertAppRewardRootHashes",
      )
    }
  }

  def getAppRewardRootHashByRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppRewardRootHashT]] = {

    runQuerySingle(
      sql"""select history_id, round_number, root_hash
            from #${Tables.appRewardRootHashes}
            where history_id = $historyId and round_number = $roundNumber
            limit 1
      """.as[DbScanAppRewardsStore.AppRewardRootHashT].headOption,
      "appRewards.getAppRewardRootHashByRound",
    )
  }

  /** Look up the contents of a batch by its hash.
    *
    * Returns BatchOfBatches (child hashes) for internal nodes,
    * or BatchOfMintingAllowances (party + amount) for leaf nodes.
    * Returns None if no batch with this hash exists for the round.
    */
  def lookupBatchByHash(
      roundNumber: Long,
      batchHash: DbScanAppRewardsStore.RewardHash,
  )(implicit tc: TraceContext): Future[Option[DbScanAppRewardsStore.BatchContents]] =
    for {
      batchO <- findBatchByHash(roundNumber, batchHash)
      result <- (batchO match {
        case None =>
          Future.successful(Option.empty[DbScanAppRewardsStore.BatchContents])
        case Some((level, beginIncl, endExcl)) if level > 0 =>
          getChildBatchHashes(roundNumber, level, beginIncl, endExcl)
            .map(hashes => Some(DbScanAppRewardsStore.BatchOfBatches(hashes)))
        case Some((_, beginIncl, endExcl)) =>
          getLeafAllowances(roundNumber, beginIncl, endExcl)
            .map(allowances => Some(DbScanAppRewardsStore.BatchOfMintingAllowances(allowances)))
      })
    } yield result

  private def findBatchByHash(
      roundNumber: Long,
      batchHash: DbScanAppRewardsStore.RewardHash,
  )(implicit tc: TraceContext): Future[Option[(Int, Int, Int)]] = {
    import storage.DbStorageConverters.setParameterByteArray
    runQuerySingle(
      sql"""select batch_level, party_seq_num_begin_incl, party_seq_num_end_excl
            from #${Tables.appRewardBatchHashes}
            where history_id = $historyId
              and round_number = $roundNumber
              and batch_hash = ${batchHash.bytes}
            limit 1
      """.as[(Int, Int, Int)].headOption,
      "appRewards.lookupBatchByHash.find",
    )
  }

  private def getChildBatchHashes(
      roundNumber: Long,
      parentLevel: Int,
      beginIncl: Int,
      endExcl: Int,
  )(implicit tc: TraceContext): Future[Seq[DbScanAppRewardsStore.RewardHash]] =
    runQuery(
      sql"""select batch_hash
            from #${Tables.appRewardBatchHashes}
            where history_id = $historyId
              and round_number = $roundNumber
              and batch_level = ${parentLevel - 1}
              and party_seq_num_begin_incl >= $beginIncl
              and party_seq_num_end_excl <= $endExcl
            order by party_seq_num_begin_incl
      """.as[Array[Byte]],
      "appRewards.lookupBatchByHash.children",
    ).map(_.map(DbScanAppRewardsStore.RewardHash(_)))

  /** Retrieve minting allowances for a leaf batch range.
    *
    * Serves the contents of a level-0 batch from `lookupBatchByHash`.
    *
    * Reads only from `app_reward_party_totals` (stage 2 output) — the
    * same data source as `insertLeafBatches`, ensuring the returned
    * party+amount pairs are consistent with the hashes in the Merkle tree.
    */
  private def getLeafAllowances(
      roundNumber: Long,
      beginIncl: Int,
      endExcl: Int,
  )(implicit tc: TraceContext): Future[Seq[DbScanAppRewardsStore.MintingAllowance]] =
    runQuery(
      sql"""select app_provider_party, total_app_reward_amount
            from #${Tables.appRewardPartyTotals}
            where history_id = $historyId
              and round_number = $roundNumber
              and app_provider_party_seq_num >= $beginIncl
              and app_provider_party_seq_num < $endExcl
            order by app_provider_party_seq_num
      """.as[(String, BigDecimal)],
      "appRewards.lookupBatchByHash.allowances",
    ).map(_.map { case (provider, amount) =>
      DbScanAppRewardsStore.MintingAllowance(provider, amount)
    })

  // -- Aggregation ------------------------------------------------------------

  /** Returns the latest round for which reward computation has completed
    * (i.e. a root hash exists). None if no rounds have been computed.
    */
  def lookupLatestRoundWithRewardComputation()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {

    runQuerySingle(
      sql"""select max(round_number) from #${Tables.appRewardRootHashes}
            where history_id = $historyId
      """.as[Option[Long]].headOption.map(_.flatten),
      "appRewards.lookupLatestRoundWithRewardComputation",
    )
  }

  /** Runs the full reward computation pipeline for a single round in a single
    * transaction: aggregation, CC conversion, and Merkle tree hashing.
    *
    * The precondition check (assertCompleteActivity) runs outside the transaction.
    * All DB writes are atomic — if any step fails, the entire transaction rolls back.
    */
  def computeAndStoreRewards(
      roundNumber: Long,
      batchSize: Int,
      inputs: RewardComputationInputs,
  )(implicit tc: TraceContext): Future[DbScanAppRewardsStore.RewardComputationSummary] = {
    import profile.api.jdbcActionExtensionMethods

    for {
      _ <- appActivityRecordStore.assertCompleteActivity(roundNumber)
      _ <- runUpdate(
        (for {
          _ <- aggregateActivityTotalsAction(roundNumber)
          totalWeight <- getAppActivityRoundTotalWeightAction(roundNumber)
          params = inputs.deriveIssuanceParams(totalWeight)
          _ <- computeRewardTotalsAction(roundNumber, params)
          _ <- computeRewardHashesAction(roundNumber, batchSize)
        } yield ())
          .map(_ => logger.debug(s"Computed and stored rewards for round $roundNumber."))
          .transactionally,
        "appRewards.computeAndStoreRewards",
      )
      summary <- readComputationSummary(roundNumber)
    } yield summary
  }

  /** Aggregate per-party and per-round activity totals for the given round from
    * `app_activity_record_store`.
    *
    * Raises on duplicate key to detect unexpected re-runs; the trigger's
    * range-based task selection avoids this.
    */
  private[store] def aggregateActivityTotals(
      roundNumber: Long
  )(implicit tc: TraceContext): Future[Unit] =
    for {
      _ <- appActivityRecordStore.assertCompleteActivity(roundNumber)
      _ <- runUpdate(
        aggregateActivityTotalsAction(roundNumber)
          .map(_ => logger.debug(s"Aggregated activity totals for round $roundNumber.")),
        "appRewards.aggregateActivityTotals",
      )
    } yield ()

  /** DBIO action for aggregating activity totals (no precondition check). */
  private def aggregateActivityTotalsAction(roundNumber: Long) =
    (sql"with " ++ unnestAndAggregate(historyId, roundNumber) ++ sql", "
      ++ insertPartyTotals(historyId, roundNumber) ++ sql" "
      ++ insertRoundTotals(historyId, roundNumber)).asUpdate

  /** Unnest per-verdict activity arrays and aggregate weights by party. */
  private def unnestAndAggregate(historyId: Long, roundNumber: Long) =
    sql"""unnested as (
            select party, weight
            from app_activity_record_store a
            join scan_verdict_store v on a.verdict_row_id = v.row_id
            cross join lateral unnest(a.app_provider_parties, a.app_activity_weights)
                 as party_and_weight(party, weight)
            where a.round_number = $roundNumber
              and v.history_id = $historyId
          ),
          aggregated as (
            select party, sum(weight) as total_weight,
                   count(*) as num_activity_records
            from unnested
            group by party
          )"""

  /** Insert per-party totals and return the inserted weights via RETURNING. */
  private def insertPartyTotals(historyId: Long, roundNumber: Long) =
    sql"""inserted_parties as (
            insert into #${Tables.appActivityPartyTotals}
              (history_id,
               round_number,
               total_app_activity_weight,
               app_provider_party,
               num_activity_records)
            select $historyId, $roundNumber, total_weight, party,
                   num_activity_records
            from aggregated
            returning total_app_activity_weight, num_activity_records
          )"""

  /** Insert the round-level totals from the RETURNING output of insertPartyTotals. */
  private def insertRoundTotals(historyId: Long, roundNumber: Long) =
    sql"""insert into #${Tables.appActivityRoundTotals}
            (history_id,
             round_number,
             total_round_app_activity_weight,
             active_app_provider_parties_count,
             activity_records_count)
          select $historyId,
                 $roundNumber,
                 coalesce(sum(total_app_activity_weight), 0),
                 count(*),
                 coalesce(sum(num_activity_records), 0)
          from inserted_parties"""

  // -- Reward computation -----------------------------------------------------

  /** Convert activity totals (bytes) to CC minting allowances, apply threshold
    * filtering, and write to `app_reward_party_totals` and `app_reward_round_totals`.
    */
  private[store] def computeRewardTotals(
      roundNumber: Long,
      params: RewardIssuanceParams,
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    // TODO(#4747): assert totalAppRewardAmount <= totalIssuance (with small tolerance)
    //              and prevent writes if the assertion fails.
    runUpdate(
      computeRewardTotalsAction(roundNumber, params)
        .map(_ => logger.debug(s"Computed reward totals for round $roundNumber."))
        .transactionally,
      "appRewards.computeRewardTotals",
    )
  }

  /** Stage 2 of the reward computation pipeline.
    *
    * Reads per-party activity weights from `app_activity_party_totals` (stage 1
    * output), converts bytes to CC minting allowances via the issuance rate,
    * filters out parties below the reward threshold, and assigns contiguous
    * `app_provider_party_seq_num` values (0..M-1) to the M rewarded parties.
    *
    * Writes to `app_reward_party_totals` (per-party rewards with seq_num and
    * party name) and `app_reward_round_totals` (round-level aggregates).
    * Downstream stages (`insertLeafBatches`, `getLeafAllowances`) read only
    * from `app_reward_party_totals`.
    */
  private def computeRewardTotalsAction(
      roundNumber: Long,
      params: RewardIssuanceParams,
  ) = {
    val issuance = params.issuancePerFeaturedAppTraffic_CCperMB
    val threshold = params.threshold_CC
    val totalIssuance = params.totalIssuanceForFeaturedAppRewards
    val unclaimed = params.unclaimedAppRewardAmount

    (sql"""with computed as (
             select history_id, round_number, app_provider_party,
                    (cast(total_app_activity_weight as decimal(38,10)) / 1000000.0)
                      * $issuance as total_app_reward_amount
             from #${Tables.appActivityPartyTotals}
             where history_id = $historyId and round_number = $roundNumber
           ),
           inserted_parties as (
             insert into #${Tables.appRewardPartyTotals}
               (history_id, round_number, app_provider_party_seq_num,
                app_provider_party, total_app_reward_amount)
             select history_id, round_number,
                    (row_number() over (order by app_provider_party) - 1)::int,
                    app_provider_party, total_app_reward_amount
             from computed
             where total_app_reward_amount >= $threshold
             returning total_app_reward_amount
           )
           insert into #${Tables.appRewardRoundTotals}
             (history_id, round_number, total_app_reward_minting_allowance,
              total_app_reward_thresholded, total_app_reward_unclaimed,
              rewarded_app_provider_parties_count)
           select $historyId, $roundNumber,
             coalesce(sum(total_app_reward_amount), 0),
             $totalIssuance - $unclaimed - coalesce(sum(total_app_reward_amount), 0),
             $unclaimed,
             count(*)
           from inserted_parties""").asUpdate
  }

  // -- Computation summary ----------------------------------------------------

  /** Read back the summary counters for a round that was just computed,
    * within the same transaction.
    */
  private def readComputationSummary(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[DbScanAppRewardsStore.RewardComputationSummary] =
    runQuery(
      sql"""select
            (select active_app_provider_parties_count
             from #${Tables.appActivityRoundTotals}
             where history_id = $historyId and round_number = $roundNumber),
            (select activity_records_count
             from #${Tables.appActivityRoundTotals}
             where history_id = $historyId and round_number = $roundNumber),
            (select coalesce(rewarded_app_provider_parties_count, 0)
             from #${Tables.appRewardRoundTotals}
             where history_id = $historyId and round_number = $roundNumber),
            (select count(*)
             from #${Tables.appRewardBatchHashes}
             where history_id = $historyId and round_number = $roundNumber)
    """.as[DbScanAppRewardsStore.RewardComputationSummary].head,
      "appRewards.readComputationSummary",
    )

  // -- Merkle tree hash computation -------------------------------------------

  /** Build Merkle tree of reward hashes for a single round.
    *
    * Level 0: hash each batch of MintingAllowances from reward party totals.
    * Level 1+: hash batches of batches until a single root remains.
    * All levels run in a single transaction.
    */
  private[splice] def computeRewardHashes(
      roundNumber: Long,
      batchSize: Int,
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    runUpdate(
      computeRewardHashesAction(roundNumber, batchSize).map { case (leafCount, rootLevel) =>
        val levels = rootLevel + 1
        logger.info(
          s"Computed reward hashes for round $roundNumber: $leafCount leaf batches, $levels levels."
        )
      }.transactionally,
      "appRewards.computeRewardHashes",
    )
  }

  /** DBIO action for the Merkle tree hash computation.
    * Used by both `computeRewardHashes` (standalone) and
    * `computeAndStoreRewards` (as part of the full pipeline transaction).
    */
  private def computeRewardHashesAction(
      roundNumber: Long,
      batchSize: Int,
  ) =
    for {
      leafCount <- insertLeafBatches(roundNumber, batchSize)
      rootLevel <-
        if (leafCount > 0)
          aggregateToRoot(roundNumber, batchSize, level = 0, batchCount = leafCount)
        else
          insertEmptyLeafBatch(roundNumber).map(_ => 0)
      _ <- insertRootHash(roundNumber)
    } yield (leafCount, rootLevel)

  /** Stage 3 of the reward computation pipeline (level 0 of the Merkle tree).
    *
    * Reads only from `app_reward_party_totals` (stage 2 output). Groups
    * rewarded parties into batches of `batchSize` by integer division on
    * their contiguous `app_provider_party_seq_num`, assigned in stage 2.
    *
    * Hashes each batch as a `BatchOfMintingAllowances` using
    * `hash_batch_of_minting_allowances`, and writes the resulting entries
    * to `app_reward_batch_hashes`.
    *
    * Returns the number of leaf batches created.
    */
  private def insertLeafBatches(
      roundNumber: Long,
      batchSize: Int,
  ) =
    sql"""insert into #${Tables.appRewardBatchHashes}(
            history_id, round_number, batch_level,
            party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash)
          select
            $historyId, $roundNumber, 0,
            min(seq_num), max(seq_num) + 1,
            decode(hash_batch_of_minting_allowances(
              array_agg(
                hash_minting_allowance(party, daml_numeric_to_text(amount))
                order by seq_num
              )
            ), 'hex')
          from (
            select
              app_provider_party_seq_num as seq_num,
              app_provider_party as party,
              total_app_reward_amount as amount,
              (app_provider_party_seq_num / $batchSize) as batch_num
            from #${Tables.appRewardPartyTotals}
            where history_id = $historyId and round_number = $roundNumber
          ) sub
          group by batch_num
    """.asUpdate

  /** Aggregate batches at the current level into parent batches at level+1.
    * Recurses until only one batch remains.
    */
  /** @return the level of the root batch (0 if only leaf batches exist) */
  private def aggregateToRoot(
      roundNumber: Long,
      batchSize: Int,
      level: Int,
      batchCount: Int,
  ): DBIOAction[Int, NoStream, Effect.All] =
    if (batchCount <= 1) DBIO.successful(level)
    else
      for {
        nextCount <- insertNextLevel(roundNumber, batchSize, level)
        rootLevel <- aggregateToRoot(roundNumber, batchSize, level + 1, nextCount)
      } yield rootLevel

  /** Insert level+1 batches by grouping level batches into chunks of batchSize,
    * hashing each chunk as BatchOfBatches.
    */
  private def insertNextLevel(
      roundNumber: Long,
      batchSize: Int,
      currentLevel: Int,
  ) =
    sql"""insert into #${Tables.appRewardBatchHashes}(
            history_id, round_number, batch_level,
            party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash)
          select
            $historyId, $roundNumber, ${currentLevel + 1},
            min(party_seq_num_begin_incl), max(party_seq_num_end_excl),
            decode(hash_batch_of_batches(
              array_agg(encode(batch_hash, 'hex') order by party_seq_num_begin_incl)
            ), 'hex')
          from (
            select
              party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash,
              (row_number() over (order by party_seq_num_begin_incl) - 1) / $batchSize as batch_num
            from #${Tables.appRewardBatchHashes}
            where history_id = $historyId and round_number = $roundNumber
              and batch_level = $currentLevel
          ) sub
          group by batch_num
    """.asUpdate

  /** The hash of a BatchOfMintingAllowances containing no parties.
    *
    * Used for rounds where no parties are above the reward threshold.
    * Inserting this as a level-0 batch and root hash marks the round as
    * computed, so that RewardComputationTrigger does not retry it, and
    * lookupBatchByHash returns BatchOfMintingAllowances(Seq.empty)
    * rather than None.
    */
  private def emptyBatchHash =
    sql"""decode(hash_batch_of_minting_allowances(ARRAY[]::text[]), 'hex')"""

  /** Insert a level-0 batch for a round with no rewarded parties.
    *
    * The batch covers the empty party range [0, 0) and hashes an empty
    * BatchOfMintingAllowances. This lets insertRootHash find a batch to
    * copy, and lets lookupBatchByHash return a well-typed empty result.
    */
  private def insertEmptyLeafBatch(roundNumber: Long) =
    (sql"""insert into #${Tables.appRewardBatchHashes}(
             history_id, round_number, batch_level,
             party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash)
           values ($historyId, $roundNumber, 0, 0, 0, """ ++ emptyBatchHash ++ sql")").asUpdate

  /** Copy the single remaining batch hash into the root hashes table.
    * Reads the highest batch_level to find the root.
    */
  private def insertRootHash(
      roundNumber: Long
  ) =
    sql"""insert into #${Tables.appRewardRootHashes}(history_id, round_number, root_hash)
          select history_id, round_number, batch_hash
          from #${Tables.appRewardBatchHashes}
          where history_id = $historyId and round_number = $roundNumber
            and batch_level = (
              select max(batch_level) from #${Tables.appRewardBatchHashes}
              where history_id = $historyId and round_number = $roundNumber
            )
    """.asUpdate

  // -- Private helpers -------------------------------------------------------

  private def runQuery[T](
      action: DBIOAction[T, NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[T] =
    futureUnlessShutdownToFuture(storage.query(action, operationName))

  private def runQuerySingle[T](
      action: DBIOAction[Option[T], NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[Option[T]] =
    futureUnlessShutdownToFuture(storage.querySingle(action, operationName).value)

  private def runUpdate[T](
      action: DBIOAction[T, NoStream, Effect.All],
      operationName: String,
  )(implicit tc: TraceContext): Future[T] =
    futureUnlessShutdownToFuture(storage.queryAndUpdate(action, operationName))
}
