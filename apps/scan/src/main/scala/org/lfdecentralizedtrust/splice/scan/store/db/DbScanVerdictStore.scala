package org.lfdecentralizedtrust.splice.scan.store.db


import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.config.ProcessingTimeout
import slick.jdbc.PostgresProfile
import io.circe.Json
import slick.jdbc.{GetResult, PositionedResult}

//import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
//import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain


import scala.concurrent.{ExecutionContext, Future}


object DbScanVerdictStore {
  import io.circe.Json
  import com.digitalasset.canton.data.CantonTimestamp
  import com.digitalasset.canton.topology.SynchronizerId

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
}

class DbScanVerdictStore(
  storage: DbStorage,
  override protected val loggerFactory: NamedLoggerFactory,
  migrationId: Long,
)(implicit
    ec: ExecutionContext,
) extends NamedLogging
  with org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes
  with FlagCloseableAsync
  with HasCloseContext
  with org.lfdecentralizedtrust.splice.store.db.AcsQueries
{ self =>

  val profile: slick.jdbc.JdbcProfile = PostgresProfile

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq[AsyncOrSyncCloseable](
      SyncCloseable("storage", storage.close())
    )
  }

  override protected def timeouts = new ProcessingTimeout

  object Tables {
    val verdicts = "scan_verdict_store"
    val views = "scan_verdict_transaction_view_store"
  }
  import profile.api.*
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

  def insertVerdict(row: VerdictT)(implicit tc: TraceContext): Future[Int] = {
    def insert(rowT: VerdictT) = {
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
          array[${rowT.submittingParties.map(identity).mkString(",")}],
          array[${rowT.transactionRootViews.map(identity).mkString(",")}],
         ) returning row_id
      """.asUpdate
    }

    futureUnlessShutdownToFuture(storage.update(
      insert(row).transactionally,
      "scanVerdict.insertVerdict"
    ))
  }

  def insertTransactionViews(rows: Seq[TransactionViewT])(implicit tc: TraceContext): Future[Int] = {
    if (rows.isEmpty) Future.successful(0)
    else {
      def insert(row: TransactionViewT) = {
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
            array[${row.informees.map(identity).mkString(",")}],
            ${row.confirmingParties},
            array[${row.subViews.map(identity).mkString(",")}]
          )
        """.asUpdate
      }
      futureUnlessShutdownToFuture(storage.update(DBIO.sequence(rows.map(insert)).map(_.sum), "scanVerdict.insertTransactionViews"))
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

  //def listVerdicts(
  //    migrationId: Long,
  //    domainId: SynchronizerId,
  //    fromRecordTime: Option[CantonTimestamp],
  //    limit: Int,
  //)(implicit tc: TraceContext): Future[Seq[VerdictRow]] = {
  //  val where = fromRecordTime match {
  //    case Some(from) =>
  //      sql"""
  //          migration_id = $migrationId and domain_id = $domainId and record_time >= $from
  //        """
  //    case None => sql"migration_id = $migrationId and domain_id = $domainId"
  //  }
  //  storage.query(
  //    (sql"""
  //        select
  //          row_id,
  //          migration_id,
  //          domain_id,
  //          record_time,
  //          finalization_time,
  //          submitting_participant_uid,
  //          verdict_result,
  //          mediator_group,
  //          update_id,
  //          submitting_parties,
  //          transaction_root_views
  //        from #${Tables.verdicts}
  //        where """ ++ where ++ sql" order by record_time asc limit $limit").toActionBuilder
  //      .as[VerdictRow],
  //    "scanVerdict.listVerdicts",
  //  )
  //}

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
}
