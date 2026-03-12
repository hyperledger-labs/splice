// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.DBIO

import scala.concurrent.{ExecutionContext, Future}

/** Row types and store for the CIP-0104 reward accounting tables.
  *
  * Covers six tables populated by ComputeAppRewardsTrigger:
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
      appProviderPartySeqNum: Int,
      appProviderParty: String,
  )

  final case class AppActivityRoundTotalT(
      historyId: Long,
      roundNumber: Long,
      totalRoundAppActivityWeight: Long,
      activeAppProviderPartiesCount: Long,
  )

  final case class AppRewardPartyTotalT(
      historyId: Long,
      roundNumber: Long,
      appProviderPartySeqNum: Int,
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
      batchHash: Array[Byte],
  ) {

    /** Equals ignoring reference equality on Array[Byte]. */
    override def equals(obj: Any): Boolean = obj match {
      case that: AppRewardBatchHashT =>
        this.historyId == that.historyId &&
        this.roundNumber == that.roundNumber &&
        this.batchLevel == that.batchLevel &&
        this.partySeqNumBeginIncl == that.partySeqNumBeginIncl &&
        this.partySeqNumEndExcl == that.partySeqNumEndExcl &&
        java.util.Arrays.equals(this.batchHash, that.batchHash)
      case _ => false
    }

    override def hashCode(): Int = {
      var h = 17
      h = 31 * h + historyId.hashCode()
      h = 31 * h + roundNumber.hashCode()
      h = 31 * h + batchLevel.hashCode()
      h = 31 * h + partySeqNumBeginIncl.hashCode()
      h = 31 * h + partySeqNumEndExcl.hashCode()
      h = 31 * h + java.util.Arrays.hashCode(batchHash)
      h
    }
  }

  final case class AppRewardRootHashT(
      historyId: Long,
      roundNumber: Long,
      rootHash: Array[Byte],
  ) {

    override def equals(obj: Any): Boolean = obj match {
      case that: AppRewardRootHashT =>
        this.historyId == that.historyId &&
        this.roundNumber == that.roundNumber &&
        java.util.Arrays.equals(this.rootHash, that.rootHash)
      case _ => false
    }

    override def hashCode(): Int = {
      var h = 17
      h = 31 * h + historyId.hashCode()
      h = 31 * h + roundNumber.hashCode()
      h = 31 * h + java.util.Arrays.hashCode(rootHash)
      h
    }
  }
}

class DbScanAppRewardsStore(
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
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

  // -- GetResult implicits --------------------------------------------------

  private implicit val getResultAppActivityPartyTotal
      : GetResult[DbScanAppRewardsStore.AppActivityPartyTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppActivityPartyTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      totalAppActivityWeight = prs.<<[Long],
      appProviderPartySeqNum = prs.<<[Int],
      appProviderParty = prs.<<[String],
    )
  }

  private implicit val getResultAppActivityRoundTotal
      : GetResult[DbScanAppRewardsStore.AppActivityRoundTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppActivityRoundTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      totalRoundAppActivityWeight = prs.<<[Long],
      activeAppProviderPartiesCount = prs.<<[Long],
    )
  }

  private implicit val getResultAppRewardPartyTotal
      : GetResult[DbScanAppRewardsStore.AppRewardPartyTotalT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardPartyTotalT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      appProviderPartySeqNum = prs.<<[Int],
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
      batchHash = prs.<<[Array[Byte]],
    )
  }

  private implicit val getResultAppRewardRootHash
      : GetResult[DbScanAppRewardsStore.AppRewardRootHashT] = GetResult { prs =>
    DbScanAppRewardsStore.AppRewardRootHashT(
      historyId = prs.<<[Long],
      roundNumber = prs.<<[Long],
      rootHash = prs.<<[Array[Byte]],
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
              ${row.appProviderPartySeqNum}, ${row.appProviderParty})"""
      })
      (sql"""insert into #${Tables.appActivityPartyTotals}(
              history_id, round_number, total_app_activity_weight,
              app_provider_party_seq_num, app_provider_party
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppActivityPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityPartyTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppActivityPartyTotals(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app activity party totals."))
            .transactionally,
          "appRewards.insertAppActivityPartyTotals",
        )
      )
    }
  }

  def getAppActivityPartyTotalsByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppActivityPartyTotalT]] = {
    futureUnlessShutdownToFuture(
      storage.query(
        sql"""select history_id, round_number, total_app_activity_weight,
                     app_provider_party_seq_num, app_provider_party
              from #${Tables.appActivityPartyTotals}
              where history_id = $historyId and round_number = $roundNumber
              order by app_provider_party_seq_num
        """.as[DbScanAppRewardsStore.AppActivityPartyTotalT],
        "appRewards.getAppActivityPartyTotalsByRound",
      )
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
              ${row.activeAppProviderPartiesCount})"""
      })
      (sql"""insert into #${Tables.appActivityRoundTotals}(
              history_id, round_number, total_round_app_activity_weight,
              active_app_provider_parties_count
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppActivityRoundTotals(
      items: Seq[DbScanAppRewardsStore.AppActivityRoundTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppActivityRoundTotals(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app activity round totals."))
            .transactionally,
          "appRewards.insertAppActivityRoundTotals",
        )
      )
    }
  }

  def getAppActivityRoundTotalByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppActivityRoundTotalT]] = {
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""select history_id, round_number, total_round_app_activity_weight,
                       active_app_provider_parties_count
                from #${Tables.appActivityRoundTotals}
                where history_id = $historyId and round_number = $roundNumber
                limit 1
          """.as[DbScanAppRewardsStore.AppActivityRoundTotalT].headOption,
          "appRewards.getAppActivityRoundTotalByRound",
        )
        .value
    )
  }

  // -- app_reward_party_totals ----------------------------------------------

  private def batchInsertAppRewardPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]
  ) = {
    if (items.isEmpty) DBIO.successful(0)
    else {
      val values = sqlCommaSeparated(items.map { row =>
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.appProviderPartySeqNum},
              ${row.totalAppRewardAmount})"""
      })
      (sql"""insert into #${Tables.appRewardPartyTotals}(
              history_id, round_number, app_provider_party_seq_num,
              total_app_reward_amount
            ) values """ ++ values).asUpdate
    }
  }

  def insertAppRewardPartyTotals(
      items: Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods
    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppRewardPartyTotals(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app reward party totals."))
            .transactionally,
          "appRewards.insertAppRewardPartyTotals",
        )
      )
    }
  }

  def getAppRewardPartyTotalsByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppRewardPartyTotalT]] = {
    futureUnlessShutdownToFuture(
      storage.query(
        sql"""select history_id, round_number, app_provider_party_seq_num,
                     total_app_reward_amount
              from #${Tables.appRewardPartyTotals}
              where history_id = $historyId and round_number = $roundNumber
              order by app_provider_party_seq_num
        """.as[DbScanAppRewardsStore.AppRewardPartyTotalT],
        "appRewards.getAppRewardPartyTotalsByRound",
      )
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
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppRewardRoundTotals(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app reward round totals."))
            .transactionally,
          "appRewards.insertAppRewardRoundTotals",
        )
      )
    }
  }

  def getAppRewardRoundTotalByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppRewardRoundTotalT]] = {
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""select history_id, round_number,
                       total_app_reward_minting_allowance, total_app_reward_thresholded,
                       total_app_reward_unclaimed, rewarded_app_provider_parties_count
                from #${Tables.appRewardRoundTotals}
                where history_id = $historyId and round_number = $roundNumber
                limit 1
          """.as[DbScanAppRewardsStore.AppRewardRoundTotalT].headOption,
          "appRewards.getAppRewardRoundTotalByRound",
        )
        .value
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
              ${row.partySeqNumBeginIncl}, ${row.partySeqNumEndExcl}, ${row.batchHash})"""
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
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppRewardBatchHashes(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app reward batch hashes."))
            .transactionally,
          "appRewards.insertAppRewardBatchHashes",
        )
      )
    }
  }

  def getAppRewardBatchHashesByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Seq[DbScanAppRewardsStore.AppRewardBatchHashT]] = {
    futureUnlessShutdownToFuture(
      storage.query(
        sql"""select history_id, round_number, batch_level,
                     party_seq_num_begin_incl, party_seq_num_end_excl, batch_hash
              from #${Tables.appRewardBatchHashes}
              where history_id = $historyId and round_number = $roundNumber
              order by batch_level, party_seq_num_begin_incl
        """.as[DbScanAppRewardsStore.AppRewardBatchHashT],
        "appRewards.getAppRewardBatchHashesByRound",
      )
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
        sql"""(${row.historyId}, ${row.roundNumber}, ${row.rootHash})"""
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
      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          batchInsertAppRewardRootHashes(items)
            .map(_ => logger.debug(s"Inserted ${items.size} app reward root hashes."))
            .transactionally,
          "appRewards.insertAppRewardRootHashes",
        )
      )
    }
  }

  def getAppRewardRootHashByRound(historyId: Long, roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Option[DbScanAppRewardsStore.AppRewardRootHashT]] = {
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""select history_id, round_number, root_hash
                from #${Tables.appRewardRootHashes}
                where history_id = $historyId and round_number = $roundNumber
                limit 1
          """.as[DbScanAppRewardsStore.AppRewardRootHashT].headOption,
          "appRewards.getAppRewardRootHashByRound",
        )
        .value
    )
  }
}
