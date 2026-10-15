// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.nonempty.NonEmpty
import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore
import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.google.common.annotations.VisibleForTesting
import io.grpc.Status
import slick.jdbc.{GetResult, PostgresProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
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

  val DUMMY_VERDICT_ROW_ID: Long = -123456789L

  /** Metadata for an activity record ingestion run.
    *
    * @param historyId history identifier from update_history_descriptors
    * @param codeVersion code version of the ingestion logic
    * @param userVersion operator-configured version from ScanAppConfig
    * @param startedIngestingAt record time (microseconds since epoch) of the first
    *                           verdict in the first batch with activity records
    * @param earliestIngestedRound the earliest round number in the first batch with activity records
    * @param lastArchivedRound the highest round number which has been archived as of
    *                          the max record_time of the ingested verdicts
    */
  final case class AppActivityRecordMetaT(
      historyId: Long,
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )

  final case class IngestionVersions(code: Int, user: Int)

  sealed trait MetaCheckResult
  case object InsertMeta extends MetaCheckResult
  case object Resume extends MetaCheckResult
  final case class DowngradeDetected(
      runningCode: Int,
      runningUser: Int,
      storedCode: Int,
      storedUser: Int,
  ) extends MetaCheckResult

  def checkMetaVersions(
      existing: Option[(Int, Int)],
      runningCode: Int,
      runningUser: Int,
  ): MetaCheckResult = existing match {
    case None => InsertMeta
    case Some((storedCode, storedUser)) =>
      if (runningCode < storedCode || runningUser < storedUser)
        DowngradeDetected(runningCode, runningUser, storedCode, storedUser)
      else if (runningCode > storedCode || runningUser > storedUser)
        InsertMeta
      else
        Resume
  }
}

class DbAppActivityRecordStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val ingestionVersions: DbAppActivityRecordStore.IngestionVersions,
    isFirstSv: Boolean,
    initialRound: Long,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends AppActivityStore
    with NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries { self =>

  private implicit val dbProfile: DbStorage.Profile = storage.profile
  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def timeouts = new ProcessingTimeout

  import DbAppActivityRecordStore.*

  private val startedIngestingAtRef =
    new AtomicReference[Option[Long]](None)

  /** The record time of the first activity record in the store. */
  def startedIngestingAt(implicit tc: TraceContext): Future[Option[Long]] =
    startedIngestingAtRef.get() match {
      case some @ Some(_) => Future.successful(some)
      case None =>
        lookupActivityRecordMeta(ingestionVersions.code, ingestionVersions.user).map { metaO =>
          val tsO = metaO.map(_.startedIngestingAt)
          tsO.foreach(ts => startedIngestingAtRef.compareAndSet(None, Some(ts)))
          tsO
        }
    }

  object Tables {
    val appActivityRecords = "app_activity_record_store"
    val activityRecordMeta = "app_activity_record_meta"
  }

  private def historyId = updateHistory.historyId

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

  /** Find the earliest round with complete app activity.
    * The first ingested round may be partial, so the earliest complete round
    * is `earliest_ingested_round + 1`.
    * Returns the round only after it has been archived.
    * Returns None if no meta row exists or if archival of the earliest round has not happened yet.
    */
  def earliestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {

    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.earliest_ingested_round + 1
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
              and m.last_archived_round >= m.earliest_ingested_round + 1
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.earliestRoundWithCompleteAppActivity",
    )
  }

  /** The earliest round for which we have ingested app activity records.
    * This round may not have all app activity records ingested.
    * Returns None if no app activity records have been ingested, ie meta row does not exist.
    */
  private[store] def earliestIngestedRound()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.earliest_ingested_round
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
      """.as[Long].headOption,
      "appActivity.earliestIngestedRound",
    )
  }

  override def ingestionStatusForRound(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[RoundIngestionStatus] =
    earliestIngestedRound().map {
      case Some(earliestIngested) if roundNumber <= earliestIngested =>
        // We should have data for this round but no root hash exists:
        // a peer likely does, so delegate.
        RoundIngestionStatus.CannotProvide

      case Some(_) =>
        // Meta row present but round is beyond our ingested boundary —
        // ingestion is still catching up; retry.
        RoundIngestionStatus.Undetermined

      case None if !isFirstSv =>
        // Late-joining Scan with no ingestion boundary of its own —
        // it might seem Undetermined is right, but peers do have one,
        // so we delegate.
        RoundIngestionStatus.CannotProvide

      case None =>
        // firstSV during initial ingestion (brief startup window before
        // the meta row is inserted) — retry.
        RoundIngestionStatus.Undetermined
    }

  /** Find the latest round with complete app activity.
    * A round is complete once the verdict ingestion has moved passed its archival.
    * Returns None if no meta row exists or archival of a round has not happened yet.
    */
  def latestRoundWithCompleteAppActivity()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.last_archived_round
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
      """.as[Option[Long]].headOption.map(_.flatten),
      "appActivity.latestRoundWithCompleteAppActivity",
    )
  }

  /** Assert that all activity records for roundNumber have been ingested.
    * The round must be greater than the earliest_ingested_round,
    * and less than or equal to last_archived_round.
    */
  def assertCompleteActivity(roundNumber: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    runQuerySingle(
      sql"""select m.earliest_ingested_round, m.last_archived_round
            from #${Tables.activityRecordMeta} m
            where m.history_id = $historyId
              and m.activity_ingestion_code_version = $codeVersion
              and m.activity_ingestion_user_version = $userVersion
      """.as[(Long, Option[Long])].headOption,
      "appActivity.assertCompleteActivity",
    ).map {
      case Some((earliest, Some(lastArchived)))
          if roundNumber > earliest && roundNumber <= lastArchived =>
        ()
      case metaO =>
        val (earliestO, lastArchivedO) =
          metaO.fold((Option.empty[Long], Option.empty[Long])) { case (e, l) => (Some(e), l) }
        throw Status.FAILED_PRECONDITION
          .withDescription(
            s"Incomplete app activity for round $roundNumber: we require " +
              s"earliest_ingested_round < round <= last_archived_round, but " +
              s"earliest_ingested_round=$earliestO, last_archived_round=$lastArchivedO"
          )
          .asRuntimeException()
    }
  }

  @VisibleForTesting
  def getRecordByVerdictRowIdForTesting(verdictRowId: Long)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordT]] = {
    runQuerySingle(
      sql"""
        select verdict_row_id, round_number, app_provider_parties, app_activity_weights
        from #${Tables.appActivityRecords}
        where history_id = $historyId and verdict_row_id = $verdictRowId
        limit 1
      """.as[AppActivityRecordT].headOption,
      "appActivity.getRecordByVerdictRowIdForTesting",
    )
  }

  def getRecordsByVerdictRowIds(
      verdictRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, AppActivityRecordT]] = {
    NonEmpty.from(verdictRowIds) match {
      case None => Future.successful(Map.empty)
      case Some(verdictRowIds) =>
        startedIngestingAt.flatMap {
          case None => Future.successful(Map.empty)
          case Some(_) =>
            storage
              .query(
                (sql"""
                select verdict_row_id, round_number, app_provider_parties, app_activity_weights
                from #${Tables.appActivityRecords}
                where history_id = $historyId and """ ++ DbStorage
                  .toInClause("verdict_row_id", verdictRowIds))
                  .as[AppActivityRecordT],
                "appActivity.getRecordsByVerdictRowIds",
              )
              .map(rows => rows.map(r => r.verdictRowId -> r).toMap)
        }
    }
  }

  /** Batch insert app activity records using multi-row INSERT. */
  private def batchInsertAppActivityRecords(items: Seq[AppActivityRecordT]) = {
    if (items.isEmpty) {
      DBIO.successful(0)
    } else {
      val values = sqlCommaSeparated(
        items.map { row =>
          sql"""($historyId, ${row.verdictRowId},
                ${row.roundNumber}, ${row.appProviderParties}, ${row.appActivityWeights})"""
        }
      )

      (sql"""
        insert into #${Tables.appActivityRecords}(
          history_id, verdict_row_id, round_number, app_provider_parties, app_activity_weights
        ) values """ ++ values ++ sql" ON CONFLICT DO NOTHING").asUpdate
    }
  }

  /** Insert activity records and ensure the meta row exists.
    * Creates the meta row when enough information is available to
    * determine which rounds have complete activity, even when no
    * activity records exist (e.g., no featured app providers),
    * but only if traffic-summaries could be obtained for this batch.
    * On a fresh firstSV with no archived rounds, bootstraps round 0
    * as complete.
    */
  def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT],
      firstRecordTimeMicros: Long,
      hasTrafficSummaries: Boolean,
      firstActiveRoundO: Option[Long] = None,
      lastArchivedRoundO: Option[Long] = None,
  )(implicit tc: TraceContext): DBIO[Unit] = {
    val insertRecords =
      if (items.isEmpty) DBIO.successful(())
      else
        batchInsertAppActivityRecords(items).map { _ =>
          logger.info(s"Inserted ${items.size} app activity records.")
        }

    // earliestRound: the oldest round open at the earliest record_time of this batch.
    // or (initialRound - 1) on firstSV, so earliestRoundWithCompleteAppActivity()
    // returns initialRound (correct for non-zero-round bootstrap).
    val earliestRound = if (isFirstSv) Some(initialRound - 1) else firstActiveRoundO

    // lastArchived: the highest round archived as of this verdict batch.
    //   - From the caller when available
    //   - Bootstrapped to initialRound on a fresh firstSV so the
    //     complete-activity window covers the first TBAR round
    val lastArchived = lastArchivedRoundO
      .orElse(if (isFirstSv) Some(initialRound) else None)

    for {
      _ <- insertRecords
      ensureResult <- earliestRound match {
        case Some(earliest) if hasTrafficSummaries =>
          ensureMetaDBIO((firstRecordTimeMicros, earliest), lastArchived)
        case _ =>
          // Either we have no rounds info and this is not firstSV,
          // or we have not started obtaining the traffic summaries yet
          // — skip meta creation.
          // A later verdict batch will create it.
          DBIO.successful(Resume: MetaCheckResult)
      }
      _ <- (ensureResult, lastArchivedRoundO) match {
        // We already have meta row, so do the update in place.
        case (Resume, Some(round)) => updateLastArchivedRoundDBIO(round)
        case _ => DBIO.successful(0)
      }
    } yield ()
  }

  /** Insert activity records only, without meta row management.
    * Tests manage meta rows separately via `insertActivityRecordMetaForTesting`.
    */
  @VisibleForTesting
  def insertAppActivityRecordsForTesting(
      items: Seq[AppActivityRecordT]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            batchInsertAppActivityRecords(items).map(_ => ()).transactionally,
            "appActivity.insertAppActivityRecords.batch",
          )
      )
    }
  }

  type AppActivityRecordMetaT = DbAppActivityRecordStore.AppActivityRecordMetaT

  private implicit val getResultAppActivityRecordMeta: GetResult[AppActivityRecordMetaT] =
    GetResult { prs =>
      DbAppActivityRecordStore.AppActivityRecordMetaT(
        historyId = prs.<<[Long],
        codeVersion = prs.<<[Int],
        userVersion = prs.<<[Int],
        startedIngestingAt = prs.<<[Long],
        earliestIngestedRound = prs.<<[Long],
        lastArchivedRound = prs.<<[Option[Long]],
      )
    }

  /** Returns the meta row for the given code and user version. */
  def lookupActivityRecordMeta(codeVersion: Int, userVersion: Int)(implicit
      tc: TraceContext
  ): Future[Option[AppActivityRecordMetaT]] =
    runQuerySingle(
      sql"""select history_id, activity_ingestion_code_version,
                   activity_ingestion_user_version, started_ingesting_at,
                   earliest_ingested_round, last_archived_round
            from #${Tables.activityRecordMeta}
            where history_id = $historyId
              and activity_ingestion_code_version = $codeVersion
              and activity_ingestion_user_version = $userVersion
      """.as[AppActivityRecordMetaT].headOption,
      "appActivity.lookupActivityRecordMeta",
    )

  def insertActivityRecordMetaDBIO(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  ) =
    sql"""insert into #${Tables.activityRecordMeta}
            (history_id, activity_ingestion_code_version,
             activity_ingestion_user_version, started_ingesting_at,
             earliest_ingested_round, last_archived_round)
          values ($historyId, $codeVersion, $userVersion, $startedIngestingAt,
                  $earliestIngestedRound, $lastArchivedRound)
          ON CONFLICT DO NOTHING
    """.asUpdate

  private def updateLastArchivedRoundDBIO(round: Long) =
    sql"""update #${Tables.activityRecordMeta}
          set last_archived_round = $round,
              last_updated_at = now()
          where history_id = $historyId
            and activity_ingestion_code_version = ${ingestionVersions.code}
            and activity_ingestion_user_version = ${ingestionVersions.user}
    """.asUpdate

  @VisibleForTesting
  def insertActivityRecordMetaForTesting(
      codeVersion: Int,
      userVersion: Int,
      startedIngestingAt: Long,
      earliestIngestedRound: Long,
      lastArchivedRound: Option[Long],
  )(implicit tc: TraceContext): Future[Unit] =
    futureUnlessShutdownToFuture(
      storage.update_(
        insertActivityRecordMetaDBIO(
          codeVersion,
          userVersion,
          startedIngestingAt,
          earliestIngestedRound,
          lastArchivedRound,
        ),
        "appActivity.insertActivityRecordMeta",
      )
    )

  private val metaChecked = new AtomicBoolean(false)

  /** DBIO action that checks/inserts the meta row.
    *
    * @param ingestionStart `Some((firstRecordTimeMicros, earliestRound))` when
    *                       the batch has activity records, `None` otherwise.
    * @param lastArchivedRoundO the last archived round to store when inserting
    *                           a new meta row
    */
  def ensureMetaDBIO(
      ingestionStart: (Long, Long),
      lastArchivedRoundO: Option[Long] = None,
      exitOnDowngrade: Boolean = true,
  )(implicit tc: TraceContext): DBIO[MetaCheckResult] = {
    val codeVersion = ingestionVersions.code
    val userVersion = ingestionVersions.user
    val (firstRecordTimeMicros, earliestRound) = ingestionStart
    if (metaChecked.get()) DBIO.successful(Resume)
    else {
      for {
        maxVersions <- sql"""select max(activity_ingestion_code_version),
                                    max(activity_ingestion_user_version)
                             from #${Tables.activityRecordMeta}
                             where history_id = $historyId
                       """
          .as[(Option[Int], Option[Int])]
          .headOption
          .map(_.flatMap {
            case (Some(c), Some(u)) => Some((c, u))
            case _ => None
          })
        result <- checkMetaVersions(maxVersions, codeVersion, userVersion) match {
          case InsertMeta =>
            insertActivityRecordMetaDBIO(
              codeVersion,
              userVersion,
              firstRecordTimeMicros,
              earliestRound,
              lastArchivedRoundO,
            ).map { _ =>
              logger.info(
                s"App activity ingestion inserted new meta row for " +
                  s"codeVersion=${ingestionVersions.code}, userVersion=${ingestionVersions.user}."
              )
              metaChecked.set(true)
              InsertMeta: MetaCheckResult
            }
          case Resume =>
            logger.info(
              s"App activity ingestion resumed with existing meta row for " +
                s"codeVersion=${ingestionVersions.code}, userVersion=${ingestionVersions.user}."
            )
            DBIO.successful {
              metaChecked.set(true)
              Resume: MetaCheckResult
            }
          case d: DowngradeDetected =>
            logger.error(
              s"App activity ingestion version downgrade detected: " +
                s"running=(${d.runningCode},${d.runningUser}), stored=(${d.storedCode},${d.storedUser}). " +
                s"Make sure you did not accidentally remove or downgrade the 'activity-ingestion-user-version' field" +
                s"in the scan app config. Shutting down to prevent data corruption."
            )
            if (exitOnDowngrade) {
              sys.exit(1)
            } else {
              DBIO.successful(d: MetaCheckResult)
            }
        }
      } yield result
    }
  }

  private def runQuerySingle[T](
      action: DBIOAction[Option[T], NoStream, Effect.Read],
      operationName: String,
  )(implicit tc: TraceContext): Future[Option[T]] =
    futureUnlessShutdownToFuture(storage.querySingle(action, operationName).value)

}
