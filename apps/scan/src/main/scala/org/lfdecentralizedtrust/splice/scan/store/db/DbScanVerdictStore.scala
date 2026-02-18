// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import io.circe.Json
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import cats.data.NonEmptyList
import org.lfdecentralizedtrust.splice.store.UpdateHistory

object DbScanVerdictStore {
  import com.digitalasset.canton.mediator.admin.{v30}

  final case class TransactionViewT(
      verdictRowId: Long,
      viewId: Int,
      informees: Seq[String],
      confirmingParties: Json,
      subViews: Seq[Int],
      viewHash: Option[String],
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

  def apply(
      storage: com.digitalasset.canton.resource.DbStorage,
      updateHistory: UpdateHistory,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): DbScanVerdictStore =
    new DbScanVerdictStore(storage, updateHistory, loggerFactory)
}

class DbScanVerdictStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  private def historyId = updateHistory.historyId

  def waitUntilInitialized: Future[Unit] = updateHistory.waitUntilInitialized

  override protected def timeouts = new ProcessingTimeout

  private val lastIngestedRecordTimeRef =
    new AtomicReference[Option[CantonTimestamp]](None)

  def lastIngestedRecordTime: Option[CantonTimestamp] = lastIngestedRecordTimeRef.get()

  private def advanceLastIngestedRecordTime(ts: CantonTimestamp): Unit = {
    val _ = lastIngestedRecordTimeRef.updateAndGet { curr =>
      curr match {
        case Some(c) if ts < c => curr
        case _ => Some(ts)
      }
    }
  }

  object Tables {
    val verdicts = "scan_verdict_store"
    val views = "scan_verdict_transaction_view_store"
  }

  // Expose stable, top-level case classes via the instance for convenience
  type TransactionViewT = DbScanVerdictStore.TransactionViewT
  val TransactionViewT = DbScanVerdictStore.TransactionViewT
  type VerdictT = DbScanVerdictStore.VerdictT
  val VerdictT = DbScanVerdictStore.VerdictT

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
      <<[Option[String]],
    )
  }

  private def sqlInsertVerdictReturningId(rowT: VerdictT) = {
    sql"""
      insert into #${Tables.verdicts}(
        history_id,
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
        $historyId,
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
           sub_views,
           view_hash
        ) values (
          ${row.verdictRowId},
          ${row.viewId},
          ${row.informees.map(lengthLimited).toSeq},
          ${row.confirmingParties},
          ${row.subViews.toSeq},
          ${row.viewHash}
        )
      """.asUpdate
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
      val checkExist = (sql"""
               select update_id
               from #${Tables.verdicts}
               where history_id = $historyId
                 and """ ++ inClause("update_id", items.map(t => lengthLimited(t._1.updateId))))
        .as[String]

      val action: DBIO[Unit] = for {
        alreadyExisting <- checkExist.map(_.toSet)
        nonExisting = items.filter(item => !alreadyExisting.contains(item._1.updateId))
        _ = logger.info(
          s"Already ingested verdicts: $alreadyExisting. Non-existing: ${nonExisting.map(_._1.updateId)}."
        )
        _ <-
          if (nonExisting.nonEmpty) {
            DBIO
              .sequence(nonExisting.map { case (verdict, mkViews) =>
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
          } else {
            DBIO.successful(())
          }
      } yield ()

      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            action.transactionally,
            "scanVerdict.insertVerdictAndTransactionViews.batch",
          )
      ).map { _ =>
        val maxRt = items.map(_._1.recordTime).maxOption
        maxRt.foreach(advanceLastIngestedRecordTime)
      }
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
            where history_id = $historyId and update_id = $updateId
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
      where history_id = $historyId and """ ++ afterFilter ++
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
    val finalQuery = verdictsQuery(filters, orderBy, limit).toActionBuilder.as[VerdictT]
    storage.query(finalQuery, "scanVerdict.listVerdicts")
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
             sub_views,
             view_hash
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
        (sql"""
          select max(record_time)
          from   #${Tables.verdicts}
          where  history_id = $historyId
          and    migration_id = $migrationId
          """).toActionBuilder
          .as[Option[CantonTimestamp]],
        "scanVerdict.maxVerdictRecordTime",
      )
      .map(_.headOption.flatten)
  }

  def maxUpdateRecordTime(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] = {
    val historyId = updateHistory.historyId
    val q = sql"""
      select max(record_time) from (
        select max(record_time) as record_time from update_history_transactions where history_id = $historyId and migration_id = $migrationId
         union all select max(record_time) as record_time from update_history_assignments where history_id = $historyId and migration_id = $migrationId
         union all select max(record_time) as record_time from update_history_unassignments where history_id = $historyId and migration_id = $migrationId
      ) t
    """

    storage
      .query(
        q.toActionBuilder.as[Option[CantonTimestamp]],
        "scanVerdict.maxUpdateRecordTime",
      )
      .map(_.headOption.flatten)
  }
}
