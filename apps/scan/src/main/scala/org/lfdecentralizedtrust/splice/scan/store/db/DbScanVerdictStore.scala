// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbParameterUtils, DbStorage}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import io.circe.Json
import slick.jdbc.{GetResult, PositionedParameters, PositionedResult, SetParameter}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.{ExecutionContext, Future}
import cats.data.NonEmptyList

object DbScanVerdictStore {
  import io.circe.Json
  import com.digitalasset.canton.data.CantonTimestamp
  import com.digitalasset.canton.topology.SynchronizerId
  import com.digitalasset.canton.mediator.admin.{v30}

  final case class TransactionViewT(
      verdictRowId: Long,
      viewId: Int,
      informees: Seq[String],
      confirmingParties: Json,
      subViews: Seq[Int],
  )

  final case class VerdictT(
      rowId: Long,
      migrationId: Long,
      domainId: SynchronizerId,
      recordTime: CantonTimestamp,
      finalizationTime: CantonTimestamp,
      submittingParticipantUid: String,
      verdictResult: Short,
      mediatorGroup: Int,
      updateId: String,
      submittingParties: Seq[String],
      transactionRootViews: Seq[Int],
  )

  object VerdictResultDbValue {
    val Unspecified: Short = 0
    val Accepted: Short = 1
    val Rejected: Short = 2

    def fromProto(v: v30.VerdictResult): Short = v match {
      case v30.VerdictResult.VERDICT_RESULT_ACCEPTED => Accepted
      case v30.VerdictResult.VERDICT_RESULT_REJECTED => Rejected
      case _ => Unspecified
    }

    def toProto(s: Short): v30.VerdictResult = s match {
      case Accepted => v30.VerdictResult.VERDICT_RESULT_ACCEPTED
      case Rejected => v30.VerdictResult.VERDICT_RESULT_REJECTED
      case Unspecified => v30.VerdictResult.VERDICT_RESULT_UNSPECIFIED
      case _ => v30.VerdictResult.VERDICT_RESULT_UNSPECIFIED
    }
  }
}

class DbScanVerdictStore(
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
    val verdicts = "scan_verdict_store"
    val views = "scan_verdict_transaction_view_store"
  }
  import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  // Expose stable, top-level case classes via the instance for convenience
  type TransactionViewT = DbScanVerdictStore.TransactionViewT
  val TransactionViewT = DbScanVerdictStore.TransactionViewT
  type VerdictT = DbScanVerdictStore.VerdictT
  val VerdictT = DbScanVerdictStore.VerdictT

  private implicit val intArrayGetResult: GetResult[Array[Int]] = (r: PositionedResult) => {
    val sqlArray = r.rs.getArray(r.skip.currentPos)
    if (sqlArray == null) Array.emptyIntArray
    else
      sqlArray.getArray match {
        case arr: Array[java.lang.Integer] => arr.map(_.intValue())
        case arr: Array[Int] => arr
        case x: Array[?] =>
          // fallback: attempt to parse string representation
          x.map(_.toString.toInt)
        case other =>
          throw new IllegalStateException(
            s"Expected an array of integers, but got $other. Are you sure you selected an integer array column?"
          )
      }
  }

  private implicit val GetResultVerdictRow: GetResult[VerdictT] = GetResult { prs =>
    import prs.*
    VerdictT(
      <<[Long],
      <<[Long],
      <<[SynchronizerId],
      <<[CantonTimestamp],
      <<[CantonTimestamp],
      <<[String],
      <<[Short],
      <<[Int],
      <<[String],
      // Arrays
      stringArrayGetResult(prs).toSeq,
      intArrayGetResult(prs).toSeq,
    )
  }

  private implicit val GetResultTransactionViewRow: GetResult[TransactionViewT] = GetResult { prs =>
    import prs.*
    TransactionViewT(
      <<[Long],
      <<[Int],
      stringArrayGetResult(prs).toSeq,
      <<[Json],
      intArrayGetResult(prs).toSeq,
    )
  }

  private implicit val intSeqSetParameter: SetParameter[Seq[Int]] =
    (ints: Seq[Int], pp: PositionedParameters) =>
      DbParameterUtils.setArrayIntOParameterDb(Some(ints.toArray), pp)

  private def sqlInsertVerdictReturningId(rowT: VerdictT) = {
    sql"""
      insert into #${Tables.verdicts}(
        migration_id,
        domain_id,
        record_time,
        finalization_time,
        submitting_participant_uid,
        verdict_result,
        mediator_group,
        update_id,
        submitting_parties,
        transaction_root_views
      ) values (
        ${rowT.migrationId},
        ${rowT.domainId},
        ${rowT.recordTime},
        ${rowT.finalizationTime},
        ${rowT.submittingParticipantUid},
        ${rowT.verdictResult},
        ${rowT.mediatorGroup},
        ${rowT.updateId},
        ${rowT.submittingParties.map(lengthLimited).toSeq},
        ${rowT.transactionRootViews.toSeq}
      ) returning row_id
    """.as[Long].headOption
  }

  private def sqlInsertView(row: TransactionViewT) = {
    sql"""
        insert into #${Tables.views}(
           verdict_row_id,
           view_id,
           informees,
           confirming_parties,
           sub_views
        ) values (
          ${row.verdictRowId},
          ${row.viewId},
          ${row.informees.map(lengthLimited).toSeq},
          ${row.confirmingParties},
          ${row.subViews.toSeq}
        )
      """.asUpdate
  }

  def insertVerdict(row: VerdictT)(implicit tc: TraceContext): Future[Int] = {
    storage
      .querySingle(
        sqlInsertVerdictReturningId(row),
        "scanVerdict.insertVerdictReturningId",
      )
      .value
      .map {
        case Some(id) => id.toInt
        case None => throw new RuntimeException("insertVerdict did not return row_id")
      }
  }

  def insertTransactionViews(
      rows: Seq[TransactionViewT]
  )(implicit tc: TraceContext): Future[Int] = {
    if (rows.isEmpty) Future.successful(0)
    else {
      import slick.dbio.DBIO
      futureUnlessShutdownToFuture(
        storage.update(
          DBIO.sequence(rows.map(sqlInsertView)).map(_.sum: Int),
          "scanVerdict.insertTransactionViews",
        )
      )
    }
  }

  def insertVerdictAndTransactionViews(
      verdict: VerdictT,
      mkViews: Long => Seq[TransactionViewT],
  )(implicit tc: TraceContext): Future[Unit] = {
    import slick.dbio.DBIO
    import profile.api.jdbcActionExtensionMethods

    val action: DBIO[Unit] = for {
      idOpt <- sqlInsertVerdictReturningId(verdict)
      rowId <- idOpt match {
        case Some(id) => DBIO.successful(id)
        case None => DBIO.failed(new RuntimeException("insertVerdict did not return row_id"))
      }
      views = mkViews(rowId)
      _ <- DBIO.sequence(views.map(sqlInsertView)).map(_ => ())
    } yield ()

    futureUnlessShutdownToFuture(
      storage.queryAndUpdate(action.transactionally, "scanVerdict.insertVerdictAndTransactionViews")
    )
  }

  /** Insert multiple verdicts and their transaction views in a single transaction.
    *
    * Similar to insertItems of UpdateHistory, we check whether the first verdict's
    * update_id already exists. If it does, we assume this batch has been
    * inserted already and skip all inserts.
    */
  def insertVerdictAndTransactionViews(
      items: Seq[(VerdictT, Long => Seq[TransactionViewT])]
  )(implicit tc: TraceContext): Future[Unit] = {
    import slick.dbio.DBIO
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      val (headVerdict, _) = items.headOption.getOrElse(
        throw new RuntimeException("insertVerdictAndTransactionViews called with empty items")
      )
      val checkExists = sql"""
             select exists(
               select 1
               from #${Tables.verdicts}
               where update_id = ${headVerdict.updateId}
             )
           """.as[Boolean].head

      val action: DBIO[Unit] = for {
        alreadyExists <- checkExists
        _ <-
          if (!alreadyExists) {
            DBIO
              .sequence(items.map { case (verdict, mkViews) =>
                for {
                  idOpt <- sqlInsertVerdictReturningId(verdict)
                  rowId <- idOpt match {
                    case Some(id) => DBIO.successful(id)
                    case None =>
                      DBIO.failed(new RuntimeException("insertVerdict did not return row_id"))
                  }
                  views = mkViews(rowId)
                  _ <- DBIO.sequence(views.map(sqlInsertView)).map(_ => ())
                } yield ()
              })
              .map(_ => ())
          } else DBIO.successful(())
      } yield ()

      futureUnlessShutdownToFuture(
        storage.queryAndUpdate(
          action.transactionally,
          "scanVerdict.insertVerdictAndTransactionViews.batch",
        )
      )
    }
  }

  def getVerdictByUpdateId(updateId: String)(implicit
      tc: TraceContext
  ): Future[Option[VerdictT]] = {
    storage
      .querySingle(
        sql"""
            select
              row_id,
              migration_id,
              domain_id,
              record_time,
              finalization_time,
              submitting_participant_uid,
              verdict_result,
              mediator_group,
              update_id,
              submitting_parties,
              transaction_root_views
            from #${Tables.verdicts}
            where update_id = $updateId
            limit 1
          """.as[VerdictT].headOption,
        "scanVerdict.getVerdictByUpdateId",
      )
      .value
  }

  private def afterFilters(
      afterO: Option[(Long, CantonTimestamp)],
      includeImportUpdates: Boolean,
  ): NonEmptyList[SQLActionBuilder] = {
    val gt = if (includeImportUpdates) ">=" else ">"
    afterO match {
      case None =>
        NonEmptyList.of(sql"migration_id >= 0 and record_time #$gt ${CantonTimestamp.MinValue}")
      case Some((afterMigrationId, afterRecordTime)) =>
        NonEmptyList.of(
          sql"migration_id = ${afterMigrationId} and record_time > ${afterRecordTime} ",
          sql"migration_id > ${afterMigrationId} and record_time #$gt ${CantonTimestamp.MinValue}",
        )
    }
  }

  private def verdictsQuery(
      filters: NonEmptyList[SQLActionBuilder],
      orderBy: SQLActionBuilder,
      limit: Int,
  ) = {
    def makeSubQuery(afterFilter: SQLActionBuilder) = {
      sql"""
      (select
        row_id,
        migration_id,
        domain_id,
        record_time,
        finalization_time,
        submitting_participant_uid,
        verdict_result,
        mediator_group,
        update_id,
        submitting_parties,
        transaction_root_views
      from #${Tables.verdicts}
      where """ ++ afterFilter ++
        sql" order by " ++ orderBy ++ sql" limit $limit)"
    }

    if (filters.size == 1) makeSubQuery(filters.head)
    else {
      val unionAll = filters.map(makeSubQuery).reduceLeft(_ ++ sql" union all " ++ _)
      sql"select * from (" ++ unionAll ++ sql") all_queries " ++
        sql"order by " ++ orderBy ++ sql" limit $limit"
    }
  }

  def listVerdicts(
      afterO: Option[(Long, CantonTimestamp)],
      includeImportUpdates: Boolean,
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[VerdictT]] = {
    val filters = afterFilters(afterO, includeImportUpdates)
    val orderBy =
      if (includeImportUpdates) sql"migration_id, record_time, update_id"
      else sql"migration_id, record_time"
    val finalQuery = verdictsQuery(filters, orderBy, limit)
    storage.query(finalQuery.toActionBuilder.as[VerdictT], "scanVerdict.listVerdicts")
  }

  def listTransactionViews(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Seq[TransactionViewT]] = {
    storage.query(
      sql"""
           select
             verdict_row_id,
             view_id,
             informees,
             confirming_parties,
             sub_views
           from #${Tables.views}
           where verdict_row_id = $verdictRowId
           order by view_id asc
         """.as[TransactionViewT],
      "scanVerdict.listTransactionViews",
    )
  }

  def maxVerdictRecordTime(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    storage
      .query(
        (sql"select max(record_time) from #${Tables.verdicts} where migration_id = $migrationId").toActionBuilder
          .as[Option[CantonTimestamp]],
        "scanVerdict.maxVerdictRecordTime",
      )
      .map(_.headOption.flatten)
  }

  def maxUpdateRecordTime(historyId: Long, migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    val q =
      sql"select max(record_time) from (" ++
        sql"select record_time from update_history_transactions where history_id = $historyId and migration_id = $migrationId" ++
        sql" union all select record_time from update_history_assignments where history_id = $historyId and migration_id = $migrationId" ++
        sql" union all select record_time from update_history_unassignments where history_id = $historyId and migration_id = $migrationId" ++
        sql") t"

    storage
      .query(
        q.toActionBuilder.as[Option[CantonTimestamp]],
        "scanVerdict.maxUpdateRecordTime",
      )
      .map(_.headOption.flatten)
  }
}
