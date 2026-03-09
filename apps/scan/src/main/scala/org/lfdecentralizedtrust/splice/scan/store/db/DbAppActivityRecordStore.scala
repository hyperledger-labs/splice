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

object DbAppActivityRecordStore {

  /** App activity record for a given verdict.
    *
    * @param verdictRowId the row_id of the parent verdict in scan_verdict_store
    * @param roundNumber the mining round that was open at this record_time
    * @param appProviderParties app providers for which app activity should be recorded
    * @param appActivityWeights activity weight (in bytes of traffic) for each app provider,
    *                           in one-to-one correspondence with appProviderParties
    */
  final case class AppActivityRecordT(
      verdictRowId: Long,
      roundNumber: Long,
      appProviderParties: Seq[String],
      appActivityWeights: Seq[Long],
  )
}

class DbAppActivityRecordStore(
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val appActivityRecords = "app_activity_record_store"
  }

  type AppActivityRecordT = DbAppActivityRecordStore.AppActivityRecordT

  private implicit val getResultAppActivityRecord: GetResult[AppActivityRecordT] = GetResult {
    prs =>
      DbAppActivityRecordStore.AppActivityRecordT(
        verdictRowId = prs.<<[Long],
        roundNumber = prs.<<[Long],
        appProviderParties = stringArrayGetResult(prs).toSeq,
        appActivityWeights = longArrayGetResult(prs).toSeq,
      )
  }

  def getRecordByVerdictRowId(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordT]] = {
    futureUnlessShutdownToFuture(
      storage
        .querySingle(
          sql"""
            select verdict_row_id, round_number, app_provider_parties, app_activity_weights
            from #${Tables.appActivityRecords}
            where verdict_row_id = $verdictRowId
            limit 1
          """.as[AppActivityRecordT].headOption,
          "appActivity.getRecordByVerdictRowId",
        )
        .value
    )
  }

  def getRecordsByVerdictRowIds(
      verdictRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, AppActivityRecordT]] = {
    if (verdictRowIds.isEmpty) Future.successful(Map.empty)
    else {
      storage
        .query(
          (sql"""
          select verdict_row_id, round_number, app_provider_parties, app_activity_weights
          from #${Tables.appActivityRecords}
          where """ ++ inClause("verdict_row_id", verdictRowIds))
            .as[AppActivityRecordT],
          "appActivity.getRecordsByVerdictRowIds",
        )
        .map(rows => rows.map(r => r.verdictRowId -> r).toMap)
    }
  }

  /** Batch insert app activity records using multi-row INSERT. */
  private def batchInsertAppActivityRecords(items: Seq[AppActivityRecordT]) = {
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""(${row.verdictRowId},
                ${row.roundNumber}, ${row.appProviderParties}, ${row.appActivityWeights})"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          verdict_row_id, round_number, app_provider_parties, app_activity_weights
        ) values """ ++ values).asUpdate
    }
  }

  /** Returns a DBIO action for inserting app activity records (for use in combined transactions).
    * Unlike insertAppActivityRecords, this doesn't wrap in a transaction or Future.
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): DBIO[Unit] = {
    if (items.isEmpty) DBIO.successful(())
    else {
      batchInsertAppActivityRecords(items).map { _ =>
        logger.info(s"Inserted ${items.size} app activity records.")
      }
    }
  }

  /** Insert multiple app activity records in a single transaction.
    *
    * The unique constraint on verdict_row_id (PK) serves as a safety net against duplicates.
    */
  def insertAppActivityRecords(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            insertAppActivityRecordsDBIO(items).transactionally,
            "appActivity.insertAppActivityRecords.batch",
          )
      )
    }
  }
}
