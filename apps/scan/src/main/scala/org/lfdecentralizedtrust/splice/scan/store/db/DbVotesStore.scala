// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.Transaction
import com.digitalasset.canton.config.CantonRequireTypes.{String2066, String256M}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_AnsEntryContext,
  ARC_DsoRules,
  ExtActionRequiringConformation,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_Expired
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  Reason,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, DbKeyValueStore}
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import org.lfdecentralizedtrust.splice.scan.store.ScanKeyValueProvider
import org.lfdecentralizedtrust.splice.store.events.DsoRulesCloseVoteRequest
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import org.lfdecentralizedtrust.splice.util.Trees
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.{JdbcProfile, PostgresProfile}

import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import scala.util.{Failure, Success, Try}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Historical vote results (exercised DsoRules_CloseVoteRequest choices).
  *
  * Owns table `votes_store_results`, and the key `votes_store_first_ingested_record_time_<storeId>`
  * in the key value store.
  */
class DbVotesStore private (
    storage: DbStorage,
    val storeId: Int,
    kvStore: DbKeyValueStore,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, closeContext: CloseContext)
    extends AcsJdbcTypes
    with HistoricalVotesStore
    with LimitHelpers
    with NamedLogging {

  import DbVotesStore.*

  override val profile: JdbcProfile = PostgresProfile

  // Sort key: the effective date, or completedAt for rejected/expired votes that have none.
  private val sortKey = "coalesce(vote_effective_at, vote_completed_at)"

  /** The record time of the first transaction that the ingestion of this store has seen.
    * Vote results from before that time have to be backfilled.
    */
  def getFirstIngestedRecordTime()(implicit tc: TraceContext): Future[Option[CantonTimestamp]] = {
    import ScanKeyValueProvider.timestampCodec
    kvStore
      .readValueAndLogOnDecodingFailure[CantonTimestamp](firstIngestedRecordTimeKey(storeId))
      .value
  }

  override def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: Limit,
      after: Option[Long],
  )(implicit tc: TraceContext): Future[ResultsPage[DsoRules_CloseVoteRequestResult]] =
    for {
      // The page token is a row_id, which is only used to look up the position in the sort order.
      where <- Future.fromTry(Try {
        val afterCond = after.map(a => sql"""(#$sortKey, record_time, event_number) <
          (select #$sortKey, record_time, event_number
           from #$table where store_id = $storeId and row_id = $a)""")
        and(filterConditions(storeId, filters) ++ afterCond.toList.map(toSQLActionBuilderChain))
      })
      rows <- futureUnlessShutdownToFuture(
        storage.query(
          (sql"select row_id, choice_result from #$table where " ++ where ++
            sql" order by #$sortKey desc, record_time desc, event_number desc limit ${sqlLimit(limit)}").toActionBuilder
            .as[(Long, String)],
          "DbVotesStore.listVoteRequestResults",
        )
      )
      limited = applyLimit("listVoteRequestResults", limit, rows)
    } yield ResultsPage(
      limited.map { case (_, json) => DsoRules_CloseVoteRequestResult.fromJson(json) },
      limited.lastOption.map(_._1),
    )

  override def countVoteRequestResults(filters: VoteResultsFilters)(implicit
      tc: TraceContext
  ): Future[Long] = {
    for {
      where <- Future.fromTry(Try(and(filterConditions(storeId, filters))))
      count <- futureUnlessShutdownToFuture(
        storage.query(
          (sql"select count(*) from #$table where " ++ where).toActionBuilder
            .as[Long]
            .head,
          "DbVotesStore.countVoteRequestResults",
        )
      )
    } yield {
      count
    }
  }

  /** Fails with INVALID_ARGUMENT (HTTP 400) if the date cannot be parsed. */
  private def parseMicros(field: String, s: String): Long =
    Try(Instant.parse(s)).toOption
      .flatMap(CantonTimestamp.fromInstant(_).toOption)
      .getOrElse(
        throw Status.INVALID_ARGUMENT
          .withDescription(s"Invalid $field: '$s' is not an ISO-8601 instant")
          .asRuntimeException()
      )
      .toMicros

  private def and(conds: NonEmptyList[SQLActionBuilderChain]) =
    conds.reduceLeft((a, b) => (a ++ sql" and " ++ b).toActionBuilder)

  // No indexes support these filters: the table has one row per closed vote request,
  // so it stays small (thousands of rows) and is scanned. Only the sort order is index-backed.
  private def filterConditions(
      storeId: Int,
      f: VoteResultsFilters,
  ): NonEmptyList[SQLActionBuilderChain] = NonEmptyList(
    toSQLActionBuilderChain(sql"store_id = $storeId"),
    List(
      f.actionName.map(n => sql"vote_action_name like ${lengthLimited(s"%$n%")}"),
      f.accepted.map(a => sql"vote_accepted = $a"),
      f.requester.map(r => sql"vote_requester_name like ${lengthLimited(s"%$r%")}"),
      (
        f.effectiveFrom.map(parseMicros("effective_from", _)),
        f.effectiveTo.map(parseMicros("effective_to", _)),
      ) match {
        case (Some(from), Some(to)) => Some(sql"vote_effective_at between $from and $to")
        case (Some(from), None) => Some(sql"vote_effective_at >= $from")
        case (None, Some(to)) => Some(sql"vote_effective_at <= $to")
        case (None, None) => None
      },
    ).flatten.map(toSQLActionBuilderChain),
  )

}

object DbVotesStore {

  /** The store id is the id of the TxLog store of the ACS store that ingests the votes
    * (see [[IngestionSink]]).
    */
  def apply(
      storeId: Int,
      storage: DbStorage,
      kvStore: DbKeyValueStore,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext, cc: CloseContext): DbVotesStore =
    new DbVotesStore(storage, storeId, kvStore, loggerFactory)

  /** Ingests the votes together with the ACS and the TxLog of the ACS store, using the id of
    * its TxLog store as the store id. Unlike the ACS, the TxLog store is not reset when the ACS is
    * (see `DbMultiDomainAcsStore.ingestionSink.initialize`), so ingested votes are never lost
    * or skipped.
    */
  class IngestionSink(
      kvStore: DbKeyValueStore,
      override protected val loggerFactory: NamedLoggerFactory,
  ) extends AdditionalIngestionSink
      with NamedLogging {

    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    @volatile
    private var ingestionStateInitialized: Boolean = false

    override def prepareBatch(
        txLogStoreId: TxLogStoreId,
        transactions: NonEmptyList[Transaction],
    )(implicit
        tc: TraceContext
    ): Seq[PreparedIngestionAction] = {
      val storeId = TxLogStoreId.unwrap(txLogStoreId)
      val actions: List[PreparedIngestionAction] =
        transactions.toList.flatMap(parseTransaction(_, storeId))
      if (ingestionStateInitialized) {
        actions
      } else {
        val firstRecordTime = CantonTimestamp.assertFromInstant(transactions.head.getRecordTime)
        SetFirstIngestedRecordTime(kvStore, storeId, firstRecordTime) +: actions
      }
    }

    override def onTransactionCommitted(
        transactions: NonEmptyList[Transaction]
    )(implicit tc: TraceContext): Unit =
      // The actions from the previous `prepareBatch` have been committed, including
      // `SetFirstIngestedRecordTime`, so we can set the flag to avoid running it in the future.
      // `SetFirstIngestedRecordTime` itself does nothing if the value is already set, this is just
      // a best-effort optimization to avoid unnecessary database writes.
      ingestionStateInitialized = true
  }

  val table = "votes_store_results"
  // Data in the key value store is scoped to the instance of ScanKeyValueStore, i.e.,
  // all components in the scan app share a single key value store.
  // The store id is the id of the TxLog store. We include it in the key to avoid
  // making assumptions about it never changing.
  def firstIngestedRecordTimeKey(storeId: Int): String =
    s"votes_store_first_ingested_record_time_$storeId"

  /** Records the record time of the first transaction that the ingestion has seen, unless it is already set.
    * Runs in the same SQL transaction as the ingestion of the votes, so the value is recorded if and only if
    * the votes of the batch are.
    */
  final case class SetFirstIngestedRecordTime(
      kvStore: DbKeyValueStore,
      storeId: Int,
      recordTime: CantonTimestamp,
  ) extends PreparedIngestionAction {
    import ScanKeyValueProvider.timestampCodec

    override def action =
      kvStore.setValueIfNotExistsAction(firstIngestedRecordTimeKey(storeId), recordTime)
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("recordTime", _.recordTime)
      )
    }
  }

  final case class InsertVoteResult(
      storeId: Int,
      recordTime: Long,
      synchronizerId: String2066,
      updateId: String2066,
      eventNumber: Int,
      voteActionName: String2066,
      voteRequesterName: String2066,
      voteAccepted: Boolean,
      voteTrackingCid: String2066,
      voteCompletedAt: Option[Long],
      voteEffectiveAt: Option[Long],
      choiceResult: String256M,
      error: Option[String] = None,
  ) extends PreparedIngestionAction {
    override def action = {
      sqlu"""
        insert into #$table(
          store_id,
          record_time,
          synchronizer_id,
          update_id,
          event_number,
          vote_action_name,
          vote_requester_name,
          vote_accepted,
          vote_tracking_cid,
          vote_completed_at,
          vote_effective_at,
          choice_result,
          error
        ) values (
          $storeId,
          ${recordTime},
          ${synchronizerId},
          ${updateId},
          ${eventNumber},
          ${voteActionName},
          ${voteRequesterName},
          ${voteAccepted},
          ${voteTrackingCid},
          ${voteCompletedAt},
          ${voteEffectiveAt},
          ${choiceResult}::jsonb,
          ${error}
        )
        on conflict do nothing
        """
    }
    override def pretty: Pretty[this.type] = {
      prettyOfClass(
        param("updateId", _.updateId.str.unquoted),
        param("voteActionName", _.voteActionName.str.unquoted),
        param("voteTrackingCid", _.voteTrackingCid.str.unquoted),
      )
    }
  }

  def parseTransaction(
      tx: Transaction,
      storeId: Int,
  )(implicit elc: ErrorLoggingContext): Seq[InsertVoteResult] = {
    val mapping = Trees.getLocalEventIndices(tx)
    val recordTime = CantonTimestamp.assertFromInstant(tx.getRecordTime).toMicros
    tx.getEventsById.asScala.iterator
      .flatMap { case (nodeId, event) =>
        lazy val eventNumber = mapping(nodeId).intValue
        // Only the nodes that are a CloseVoteRequest exercise can fail here, all other nodes are ignored.
        Try(event match {
          case DsoRulesCloseVoteRequest(node) =>
            Some(toInsert(node.result.value, storeId, recordTime, tx, eventNumber))
          case _ => None
        }) match {
          case Success(insert) => insert
          case Failure(e) =>
            // Throwing would stop the ingestion of the whole update history, which can't continue past this update.
            // Instead, write a row that only identifies the vote, so that it can be fixed later.
            elc.error(
              s"Failed to decode the vote result in update ${tx.getUpdateId} at record time $recordTime. " +
                "Ingesting a placeholder instead.",
              e,
            )
            Some(
              toInsert(placeholderResult(recordTime), storeId, recordTime, tx, eventNumber)
                .copy(error = Some(e.getMessage.take(MaxErrorLength)))
            )
        }
      }
      .toSeq
      .sortBy(_.eventNumber)
  }

  private def toInsert(
      result: DsoRules_CloseVoteRequestResult,
      storeId: Int,
      recordTime: Long,
      tx: Transaction,
      eventNumber: Int,
  ): InsertVoteResult = {
    val outcome = VoteRequestOutcome.parse(result.outcome)
    InsertVoteResult(
      storeId = storeId,
      recordTime = recordTime,
      synchronizerId = String2066.tryCreate(tx.getSynchronizerId),
      updateId = String2066.tryCreate(tx.getUpdateId),
      eventNumber = eventNumber,
      voteActionName = String2066.tryCreate(actionName(result.request.action)),
      voteRequesterName = String2066.tryCreate(result.request.requester),
      voteAccepted = outcome match { case _: Accepted => true; case _ => false },
      voteTrackingCid =
        String2066.tryCreate(result.request.trackingCid.toScala.fold("")(_.contractId)),
      voteCompletedAt = Some(toMicros(result.completedAt)),
      voteEffectiveAt = outcome.effectiveAt.map(toMicros),
      choiceResult = String256M.tryCreate(result.toJson),
    )
  }

  /** A fake vote result for a vote that could not be decoded, so that its row can be read like any other.
    * The row still identifies the vote (record time, update id, event number) and records the error.
    *
    * This should not happen in practice: all SVs are expected to upgrade their apps before a new Daml version
    * is voted in, so every app can decode every vote result that is created. Throwing instead would stop the
    * ingestion of the whole update history, which is why this only logs an error.
    */
  private def placeholderResult(recordTime: Long): DsoRules_CloseVoteRequestResult = {
    val at = CantonTimestamp.assertFromLong(recordTime).toInstant
    new DsoRules_CloseVoteRequestResult(
      new VoteRequest(
        FailedToParseVote,
        FailedToParseVote,
        new ExtActionRequiringConformation(com.daml.ledger.javaapi.data.Unit.getInstance()),
        new Reason("", FailedToParseVote),
        at,
        java.util.Map.of(),
        java.util.Optional.of(new VoteRequest.ContractId(FailedToParseVote)),
        java.util.Optional.of(at),
      ),
      at,
      java.util.List.of(),
      java.util.List.of(),
      new VRO_Expired(com.daml.ledger.javaapi.data.Unit.getInstance()),
    )
  }

  val FailedToParseVote = "<failed to parse vote>"

  private val MaxErrorLength = 1000

  private def toMicros(at: Instant): Long = CantonTimestamp.assertFromInstant(at).toMicros

  private def actionName(action: ActionRequiringConfirmation): String = action match {
    case a: ARC_DsoRules => a.dsoAction.getClass.getSimpleName
    case a: ARC_AmuletRules => a.amuletRulesAction.getClass.getSimpleName
    case a: ARC_AnsEntryContext => a.ansEntryContextAction.getClass.getSimpleName
    case _ => ""
  }
}
