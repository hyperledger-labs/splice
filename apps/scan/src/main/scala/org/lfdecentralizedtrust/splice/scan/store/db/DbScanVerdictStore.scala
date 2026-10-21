// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.nonempty.NonEmpty
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.data.CantonTimestampExtensions.*
import com.digitalasset.canton.sequencer.admin.{v30 as seqv30}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging, TracedLogger}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import io.circe.Json
import io.circe.syntax.*
import io.grpc.Status
import slick.jdbc.GetResult
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import slick.dbio.DBIO

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import cats.data.NonEmptyList
import org.lfdecentralizedtrust.splice.store.{
  HardLimit,
  Limit,
  LimitHelpers,
  TimestampWithMigrationId,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordT

object DbScanVerdictStore {
  import com.digitalasset.canton.mediator.admin.{v30}
  import com.google.protobuf.ByteString

  /** Represents a traffic summary.
    *
    * @param totalTrafficCost the total traffic cost
    * @param envelopeTrafficSummarys the per-envelope traffic summaries
    */
  final case class TrafficSummaryT(
      totalTrafficCost: Long,
      envelopeTrafficSummarys: Seq[EnvelopeT],
      sequencingTime: CantonTimestamp, // not written, used in computations, read from VerdictT
  )

  /** Represents an envelope within a traffic summary.
    *
    * @param trafficCost the traffic cost of the envelope
    * @param viewIds view IDs from the verdict's TransactionViews that correspond to this envelope,
    *                obtained by correlating the sequencer's view_hashes with the mediator's view data
    */
  final case class EnvelopeT(
      trafficCost: Long,
      viewIds: Seq[Int],
  )

  /** Convert a sequencer TrafficSummaryT proto to our storage type, with correlation map lookup.
    *
    * This is a convenience method that parses the sequencing time from the proto and looks up
    * the view hash correlation map automatically.
    *
    * @param proto the traffic summary from the sequencer
    * @param migrationId the current migration id
    * @param viewHashToViewIdByTime map from sequencing_time to (view_hash -> view_id) for correlating
    *                               envelope view_hashes with verdict view_ids
    * @param logger for logging warnings about unmatched view hashes
    */
  def fromProtoWithCorrelation(
      proto: seqv30.TrafficSummary,
      viewHashToViewIdByTime: Map[CantonTimestamp, Map[ByteString, Int]],
      logger: TracedLogger,
  )(implicit tc: TraceContext): TrafficSummaryT = {
    val sequencingTime = CantonTimestamp
      .fromProtoTimestamp(proto.getSequencingTime)
      .getOrElse(throw new IllegalArgumentException("Invalid sequencing_time in traffic summary"))
    val viewHashToViewId = viewHashToViewIdByTime.getOrElse(sequencingTime, Map.empty)
    fromProto(proto, sequencingTime, viewHashToViewId, logger)
  }

  /** Convert a sequencer TrafficSummaryT proto to our storage type.
    *
    * @param proto the traffic summary from the sequencer
    * @param migrationId the current migration id
    * @param sequencingTime the pre-parsed sequencing time from the proto
    * @param viewHashToViewId map from view_hash to view_id for correlating envelope view_hashes
    *                         with verdict view_ids (for this specific sequencing_time)
    * @param logger for logging warnings about unmatched view hashes
    */
  def fromProto(
      proto: seqv30.TrafficSummary,
      sequencingTime: CantonTimestamp,
      viewHashToViewId: Map[ByteString, Int],
      logger: TracedLogger,
  )(implicit tc: TraceContext): TrafficSummaryT = {

    val envelopeTrafficSummarys = proto.envelopes.map { env =>
      val viewIds = env.viewHashes.flatMap { viewHash =>
        viewHashToViewId.get(viewHash) match {
          case Some(viewId) => Some(viewId)
          case None =>
            logger.warn(
              s"View hash ${HexString.toHexString(viewHash)} from sequencer traffic summary " +
                s"at $sequencingTime does not match any view in the verdict"
            )
            None
        }
      }
      EnvelopeT(
        trafficCost = env.envelopeTrafficCost,
        viewIds = viewIds,
      )
    }

    TrafficSummaryT(
      totalTrafficCost = proto.totalTrafficCost,
      sequencingTime = sequencingTime,
      envelopeTrafficSummarys = envelopeTrafficSummarys,
    )
  }

  object EnvelopeT {

    def toJson(envelopes: Seq[EnvelopeT]): Json = Json.arr(
      envelopes.map { env =>
        Json.obj(
          "tc" -> env.trafficCost.asJson,
          "vid" -> env.viewIds.asJson,
        )
      }*
    )

    def fromJson(json: Json): Seq[EnvelopeT] = {
      json.asArray.getOrElse(Vector.empty).flatMap { obj =>
        for {
          trafficCost <- obj.hcursor.get[Long]("tc").toOption
          viewIds <- obj.hcursor.get[Seq[Int]]("vid").toOption
        } yield EnvelopeT(trafficCost, viewIds)
      }
    }
  }

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
      trafficSummaryO: Option[TrafficSummaryT],
      roundNumber: Option[Long],
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

  /** Convert a mediator Verdict proto to our storage types.
    *
    * @param verdict the verdict from the mediator
    * @param migrationId the current migration id
    * @param synchronizerId the synchronizer id
    * @return a tuple of (VerdictT, function to create TransactionViewT rows given a verdict row id)
    */
  def fromProto(
      verdict: v30.Verdict,
      migrationId: Long,
      synchronizerId: SynchronizerId,
      byTimestamp: Map[CantonTimestamp, TrafficSummaryT],
  ): (VerdictT, Long => Seq[TransactionViewT]) = {
    val transactionRootViews = verdict.getTransactionViews.rootViews
    val resultShort: Short = VerdictResultDbValue.fromProto(verdict.verdict)
    val recordTime = CantonTimestamp.tryFromProtoTimestamp(verdict.getRecordTime)
    val row = VerdictT(
      rowId = 0,
      migrationId = migrationId,
      domainId = synchronizerId,
      recordTime,
      finalizationTime = CantonTimestamp.tryFromProtoTimestamp(verdict.getFinalizationTime),
      submittingParticipantUid = verdict.submittingParticipantUid,
      verdictResult = resultShort,
      mediatorGroup = verdict.mediatorGroup,
      updateId = verdict.updateId,
      submittingParties = verdict.submittingParties,
      transactionRootViews = transactionRootViews,
      trafficSummaryO = byTimestamp.get(recordTime),
      roundNumber = None,
    )

    val mkViews: Long => Seq[TransactionViewT] = { rowId =>
      verdict.getTransactionViews.views.map { case (viewId, txView) =>
        val confirmingPartiesJson: Json = Json.fromValues(
          txView.confirmingParties.map { q =>
            Json.obj(
              "parties" -> Json.fromValues(q.parties.map(Json.fromString)),
              "threshold" -> Json.fromInt(q.threshold),
            )
          }
        )
        TransactionViewT(
          verdictRowId = rowId,
          viewId = viewId,
          informees = txView.informees,
          confirmingParties = confirmingPartiesJson,
          subViews = txView.subViews,
        )
      }.toSeq
    }
    (row, mkViews)
  }

  // Defensive cap on the views read back for a single verdict; exceeding it
  // fails the read rather than returning incomplete views. Canton's view-count
  // limits are not enforced on PV 35 and are far higher on PV 36, but the
  // mediator's informee tree takes at least 108 bytes per view, so slightly
  // less than 100k fit within 10 MiB. Revisit this if a well-behaved
  // synchronizer might ever raise those limits.
  private val MaxTransactionViewsPerVerdict: HardLimit = HardLimit.tryCreate(100000, 100000)

  def apply(
      storage: com.digitalasset.canton.resource.DbStorage,
      updateHistory: UpdateHistory,
      appActivityRecordStore: DbAppActivityRecordStore,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): DbScanVerdictStore =
    new DbScanVerdictStore(
      storage,
      updateHistory,
      appActivityRecordStore,
      loggerFactory,
    )
}

class DbScanVerdictStore(
    storage: DbStorage,
    updateHistory: UpdateHistory,
    val appActivityRecordStore: DbAppActivityRecordStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext
) extends NamedLogging
    with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
    with FlagCloseable
    with HasCloseContext
    with org.lfdecentralizedtrust.splice.store.db.AcsQueries
    with LimitHelpers { self =>

  private implicit val dbProfile: DbStorage.Profile = storage.profile
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
    val rowId = <<[Long]
    val migrationId = <<[Long]
    val domainId = <<[SynchronizerId]
    val recordTime = <<[CantonTimestamp]
    VerdictT(
      rowId,
      migrationId,
      domainId,
      recordTime,
      <<[CantonTimestamp],
      <<[String],
      <<[Short],
      <<[Int],
      <<[String],
      // Arrays
      stringArrayGetResult(prs).toSeq,
      intArrayGetResult(prs).toSeq,
      // TrafficSummaryT
      parseTrafficSummary(
        <<?[Long],
        <<?[Json],
        recordTime,
      ),
      <<?[Long],
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

  private def parseTrafficSummary(
      totalTrafficCostO: Option[Long],
      envelopesJsonO: Option[Json],
      recordTime: CantonTimestamp,
  ): Option[DbScanVerdictStore.TrafficSummaryT] = {
    for {
      total <- totalTrafficCostO
      json <- envelopesJsonO
      sequencingTime = recordTime
    } yield DbScanVerdictStore.TrafficSummaryT(
      total,
      DbScanVerdictStore.EnvelopeT.fromJson(json),
      sequencingTime,
    )
  }

  private def sqlInsertVerdictReturningId(rowT: VerdictT) = {
    val envelopesO = rowT.trafficSummaryO.map(_.envelopeTrafficSummarys)
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
        transaction_root_views,
        total_traffic_cost,
        envelope_traffic_costs,
        round_number
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
        ${rowT.transactionRootViews.toSeq},
        ${rowT.trafficSummaryO.map(_.totalTrafficCost)},
        ${envelopesO.map(seq => DbScanVerdictStore.EnvelopeT.toJson(seq))}::jsonb,
        ${rowT.roundNumber}
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

  /** Returns a DBIO action for inserting verdicts and their transaction views.
    *
    * Returns a map from verdict record_time to its generated row_id, which can be used
    * to insert related records (e.g., app activity records) that reference the verdict by row_id.
    */
  private def insertVerdictAndTransactionViewsDBIO(
      items: Seq[(VerdictT, Long => Seq[TransactionViewT])]
  )(implicit tc: TraceContext): DBIO[Map[CantonTimestamp, Long]] = {
    // Defense in-depth: this should never happen, but if it did we'd want to learn about it ASAP.
    val outOfOrder = items.zip(items.drop(1)).find { case ((a, _), (b, _)) =>
      Ordering[Option[Long]].gt(a.roundNumber, b.roundNumber)
    }
    outOfOrder match {
      case Some(((a, _), (b, _))) =>
        DBIO.failed(
          Status.INTERNAL
            .withDescription(
              s"Round numbers not monotonically increasing within batch: " +
                s"${a.updateId} (round ${a.roundNumber}) precedes ${b.updateId} (round ${b.roundNumber})"
            )
            .asRuntimeException()
        )
      case None =>
        NonEmpty.from(items) match {
          case None => DBIO.successful(Map.empty)
          case Some(items) =>
            val checkExist = (sql"""
                 select update_id
                 from #${Tables.verdicts}
                 where history_id = $historyId
                   and """ ++ DbStorage.toInClause(
              "update_id",
              items.map(t => lengthLimited(t._1.updateId)),
            ))
              .as[String]

            for {
              alreadyExisting <- checkExist.map(_.toSet)
              (dropped, nonExisting) =
                items.partition(item => alreadyExisting.contains(item._1.updateId))
              droppedAccepts =
                dropped.filter(
                  _._1.verdictResult == DbScanVerdictStore.VerdictResultDbValue.Accepted
                )
              nonExistingMessage = s"Non-existing: ${nonExisting.map(_._1.updateId)}."
              _ =
                if (droppedAccepts.nonEmpty)
                  logger.warn(
                    s"Dropping duplicate accepted verdicts: ${droppedAccepts.map(_._1.updateId)}. " +
                      s"All dropped verdicts: ${dropped.map(_._1.updateId)}. $nonExistingMessage"
                  )
                else if (dropped.nonEmpty)
                  logger.info(
                    s"Dropping duplicate verdicts: ${dropped.map(_._1.updateId)}. $nonExistingMessage"
                  )
                else
                  logger.info(s"Already ingested verdicts: $alreadyExisting. $nonExistingMessage")
              rowIdMap <-
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
                      } yield verdict.recordTime -> rowId
                    })
                    .map(_.toMap)
                } else {
                  DBIO.successful(Map.empty[CantonTimestamp, Long])
                }
            } yield rowIdMap
        }
    }
  }

  /** Insert multiple verdicts and their transaction views in a single transaction.
    *
    * We check whether a verdict's update_id already exists. If it does, then we assume this
    * is a retry by the DB layer of this very statement, and skip the ingestion.
    * This works as the ingestion itself ensures that there never are overlapping batches.
    */
  def insertVerdictAndTransactionViews(
      items: Seq[(VerdictT, Long => Seq[TransactionViewT])]
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    if (items.isEmpty) Future.unit
    else {
      futureUnlessShutdownToFuture(
        storage
          .queryAndUpdate(
            insertVerdictAndTransactionViewsDBIO(items).map(_ => ()).transactionally,
            "scanVerdict.insertVerdictAndTransactionViews.batch",
          )
      ).map { _ =>
        val maxRt = items.map(_._1.recordTime).maxOption
        maxRt.foreach(advanceLastIngestedRecordTime)
      }
    }
  }

  /** Insert verdicts, transaction views, and activity records in a single
    * transaction.
    *
    * @param items verdicts with transaction view constructors
    * @param appActivityRecords activity records with placeholder verdictRowIds
    * @param hasTrafficSummaries whether traffic summaries were fetched for this batch
    * @param firstActiveRoundO the OpenMiningRound round active at the earliest
    *                          record time of the batch
    * @param lastArchivedRoundO the highest archived OpenMiningRound round as of the
    *                           max record time of the batch
    */
  def insertVerdictsWithAppActivityRecords(
      items: NonEmptyList[(VerdictT, Long => Seq[TransactionViewT])],
      appActivityRecords: Seq[(CantonTimestamp, AppActivityRecordT)],
      hasTrafficSummaries: Boolean,
      firstActiveRoundO: Option[Long] = None,
      lastArchivedRoundO: Option[Long] = None,
  )(implicit tc: TraceContext): Future[Unit] = {
    import profile.api.jdbcActionExtensionMethods

    val combinedAction = for {
      rowIdByTime <- insertVerdictAndTransactionViewsDBIO(items.toList)
      // Resolve placeholder verdictRowId to actual row_ids from the inserted verdicts
      resolvedAppActivityRecords = appActivityRecords.flatMap { case (sequencingTime, record) =>
        rowIdByTime.get(sequencingTime).map(rowId => record.copy(verdictRowId = rowId))
      }
      _ <- insertAppActivityRecordsDBIO(
        resolvedAppActivityRecords,
        items.head._1.recordTime.toMicros,
        hasTrafficSummaries,
        firstActiveRoundO,
        lastArchivedRoundO,
      )
    } yield ()

    futureUnlessShutdownToFuture(
      storage.queryAndUpdate(
        combinedAction.transactionally,
        "scanVerdict.insertVerdictsWithAppActivityRecords",
      )
    ).map { _ =>
      // items is NonEmptyList so maxOption always returns Some
      items.toList.map(_._1.recordTime).maxOption.foreach(advanceLastIngestedRecordTime)
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
              transaction_root_views,
              total_traffic_cost,
              envelope_traffic_costs,
              round_number
            from #${Tables.verdicts}
            where history_id = $historyId and update_id = $updateId
            limit 1
          """.as[VerdictT].headOption,
        "scanVerdict.getVerdictByUpdateId",
      )
      .value
  }

  private def insertAppActivityRecordsDBIO(
      items: Seq[AppActivityRecordT],
      firstRecordTimeMicros: Long,
      hasTrafficSummaries: Boolean,
      firstActiveRoundO: Option[Long],
      lastArchivedRoundO: Option[Long],
  )(implicit tc: TraceContext): DBIO[Unit] =
    appActivityRecordStore.insertAppActivityRecordsDBIO(
      items,
      firstRecordTimeMicros,
      hasTrafficSummaries,
      firstActiveRoundO,
      lastArchivedRoundO,
    )

  private def afterFilters(
      afterO: Option[TimestampWithMigrationId],
      includeImportUpdates: Boolean,
  ): NonEmptyList[SQLActionBuilder] = {
    val gt = if (includeImportUpdates) ">=" else ">"
    afterO match {
      case None =>
        NonEmptyList.of(sql"migration_id >= 0 and record_time #$gt ${CantonTimestamp.MinValue}")
      case Some(TimestampWithMigrationId(afterRecordTime, afterMigrationId)) =>
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
        transaction_root_views,
        total_traffic_cost,
        envelope_traffic_costs,
        round_number
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
      afterO: Option[TimestampWithMigrationId],
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

  def listTransactionViews(
      verdictRowId: Long,
      limit: Limit = DbScanVerdictStore.MaxTransactionViewsPerVerdict,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TransactionViewT]] = {
    val opName = "scanVerdict.listTransactionViews"
    for {
      views <- storage.query(
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
           limit ${sqlLimit(limit)}
         """.as[TransactionViewT],
        opName,
      )
    } yield applyLimitOrFail(opName, limit, views)
  }

  /** Batched [[listTransactionViews]]: one query for the views of all `verdictRowIds`, each in
    * view_id order. Verdicts without views are absent from the result. Fails if any verdict has
    * more than `maxViewsPerVerdict` views.
    */
  def listTransactionViewsByVerdictRowIds(
      verdictRowIds: Seq[Long],
      maxViewsPerVerdict: HardLimit = DbScanVerdictStore.MaxTransactionViewsPerVerdict,
  )(implicit
      tc: TraceContext
  ): Future[Map[Long, Seq[TransactionViewT]]] =
    verdictRowIds.distinct match {
      case NonEmpty(verdictRowIds) =>
        // - 1 leaves room for the HardLimit's extra sqlLimit row
        val maxViews =
          (verdictRowIds.size.toLong * maxViewsPerVerdict.limit).min(Int.MaxValue - 1L).toInt
        val overallLimit = HardLimit.tryCreate(maxViews, maxViews)
        val opName = "scanVerdict.listTransactionViewsByVerdictRowIds"
        storage
          .query(
            (sql"""
               select
                 verdict_row_id,
                 view_id,
                 informees,
                 confirming_parties,
                 sub_views
               from #${Tables.views}
               where """ ++ DbStorage.toInClause("verdict_row_id", verdictRowIds) ++ sql"""
               order by verdict_row_id, view_id
               limit ${sqlLimit(overallLimit)}
             """).toActionBuilder.as[TransactionViewT],
            opName,
          )
          .map { views =>
            applyLimitOrFail(opName, overallLimit, views).groupBy(_.verdictRowId).transform {
              (verdictRowId, verdictViews) =>
                applyLimitOrFail(
                  s"$opName for verdict $verdictRowId",
                  maxViewsPerVerdict,
                  verdictViews,
                )
            }
          }
      case _ => Future.successful(Map.empty)
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
