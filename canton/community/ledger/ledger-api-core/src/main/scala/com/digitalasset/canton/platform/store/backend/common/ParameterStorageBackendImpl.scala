// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.platform.store.backend.common

import anorm.SqlParser.{int, long}
import anorm.{BatchSql, NamedParameter, RowParser, ~}
import com.daml.scalautil.Statement.discard
import com.digitalasset.canton.RepairCounter
import com.digitalasset.canton.data.{CantonTimestamp, Offset}
import com.digitalasset.canton.ledger.api.ParticipantId
import com.digitalasset.canton.ledger.participant.state.{
  RepairIndex,
  SequencerIndex,
  SynchronizerIndex,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.platform.store.backend.Conversions.offset
import com.digitalasset.canton.platform.store.backend.common.ComposableQuery.SqlStringInterpolation
import com.digitalasset.canton.platform.store.backend.{Conversions, ParameterStorageBackend}
import com.digitalasset.canton.platform.store.interning.StringInterning
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import scalaz.syntax.tag.*

import java.sql.Connection

import SimpleSqlExtensions.*

private[backend] class ParameterStorageBackendImpl(
    queryStrategy: QueryStrategy,
    stringInterning: StringInterning,
) extends ParameterStorageBackend {
  import Conversions.OffsetToStatement

  override def updateLedgerEnd(
      ledgerEnd: ParameterStorageBackend.LedgerEnd,
      lastSynchronizerIndex: Map[SynchronizerId, SynchronizerIndex] = Map.empty,
  )(connection: Connection): Unit = discard {
    queryStrategy.forceSynchronousCommitForCurrentTransactionForPostgreSQL(connection)
    discard(
      SQL"""
        UPDATE
          lapi_parameters
        SET
          ledger_end = ${ledgerEnd.lastOffset},
          ledger_end_sequential_id = ${ledgerEnd.lastEventSeqId},
          ledger_end_string_interning_id = ${ledgerEnd.lastStringInterningId},
          ledger_end_publication_time = ${ledgerEnd.lastPublicationTime.toMicros}
        """
        .execute()(connection)
    )

    batchUpsert(
      """INSERT INTO
        |  lapi_ledger_end_synchronizer_index
        |  (synchronizer_id, sequencer_timestamp, repair_timestamp, repair_counter, record_time)
        |VALUES
        |  ({internalizedSynchronizerId}, {sequencerTimestampMicros}, {repairTimestampMicros}, {repairCounter}, {recordTimeMicros})
        |""".stripMargin,
      """UPDATE
        |  lapi_ledger_end_synchronizer_index
        |SET
        |  sequencer_timestamp = case when {sequencerTimestampMicros} is null then sequencer_timestamp else {sequencerTimestampMicros} end,
        |  repair_timestamp = case when {repairTimestampMicros} is null then repair_timestamp else {repairTimestampMicros} end,
        |  repair_counter = case when {repairTimestampMicros} is null then repair_counter else {repairCounter} end,
        |  record_time = {recordTimeMicros}
        |WHERE
        |  synchronizer_id = {internalizedSynchronizerId}
        |""".stripMargin,
      lastSynchronizerIndex.toList.map { case (synchronizerId, synchronizerIndex) =>
        Seq[NamedParameter](
          "internalizedSynchronizerId" -> stringInterning.synchronizerId.internalize(
            synchronizerId
          ),
          "sequencerTimestampMicros" -> synchronizerIndex.sequencerIndex.map(
            _.sequencerTimestamp.toMicros
          ),
          "repairTimestampMicros" -> synchronizerIndex.repairIndex.map(_.timestamp.toMicros),
          "repairCounter" -> synchronizerIndex.repairIndex.map(_.counter.unwrap),
          "recordTimeMicros" -> synchronizerIndex.recordTime.toMicros,
        )
      },
    )(connection)
  }

  private val SqlGetLedgerEnd =
    SQL"""
      SELECT
        ledger_end,
        ledger_end_sequential_id,
        ledger_end_string_interning_id,
        ledger_end_publication_time
      FROM
        lapi_parameters
      """

  override def ledgerEnd(connection: Connection): Option[ParameterStorageBackend.LedgerEnd] =
    SqlGetLedgerEnd
      .as(LedgerEndParser.singleOpt)(connection)
      .flatten

  private val TableName: String = "lapi_parameters"
  private val ParticipantIdColumnName: String = "participant_id"
  private val LedgerEndColumnName: String = "ledger_end"
  private val LedgerEndSequentialIdColumnName: String = "ledger_end_sequential_id"
  private val LedgerEndStringInterningIdColumnName: String = "ledger_end_string_interning_id"
  private val LedgerEndPublicationTimeColumnName: String = "ledger_end_publication_time"

  private val ParticipantIdParser: RowParser[ParticipantId] =
    Conversions.participantId(ParticipantIdColumnName).map(ParticipantId(_))

  private val LedgerEndOffsetParser: RowParser[Option[Offset]] =
    offset(LedgerEndColumnName).?

  private val LedgerEndSequentialIdParser: RowParser[Option[Long]] =
    long(LedgerEndSequentialIdColumnName).?

  private val LedgerEndStringInterningIdParser: RowParser[Option[Int]] =
    int(LedgerEndStringInterningIdColumnName).?

  private val LedgerIdentityParser: RowParser[ParameterStorageBackend.IdentityParams] =
    ParticipantIdParser map { case participantId =>
      ParameterStorageBackend.IdentityParams(participantId)
    }

  private val LedgerEndPublicationTimeParser: RowParser[Option[CantonTimestamp]] =
    long(LedgerEndPublicationTimeColumnName).map(CantonTimestamp.ofEpochMicro).?

  private val LedgerEndParser: RowParser[Option[ParameterStorageBackend.LedgerEnd]] =
    LedgerEndOffsetParser ~ LedgerEndSequentialIdParser ~ LedgerEndStringInterningIdParser ~ LedgerEndPublicationTimeParser map {
      case Some(lastOffset) ~ Some(lastEventSequentialId) ~
          Some(lastStringInterningId) ~ Some(lastPublicationTime) =>
        // the four values are updated the same time, so it is expected that if one is not null, then all of them will not be null
        Some(
          ParameterStorageBackend.LedgerEnd(
            lastOffset,
            lastEventSequentialId,
            lastStringInterningId,
            lastPublicationTime,
          )
        )
      case None ~ None ~ None ~ None => None
      case _ =>
        throw new IllegalStateException(
          "The offset, eventSequentialId, stringInterningId and publicationTime of the ledger end should have been defined at the same time"
        )
    }

  override def initializeParameters(
      params: ParameterStorageBackend.IdentityParams,
      loggerFactory: NamedLoggerFactory,
  )(connection: Connection): Unit = {
    val logger = loggerFactory.getTracedLogger(getClass)
    implicit val traceContext: TraceContext = TraceContext.empty
    // Note: this method is the only one that inserts a row into the parameters table
    val previous = ledgerIdentity(connection)
    val participantId = params.participantId
    previous match {
      case None =>
        logger.info(
          s"Initializing new database for participantId '${params.participantId}'"
        )
        val lastOffset: Option[Offset] = None
        val lastEventSeqId: Option[Long] = None
        val lastStringInterningId: Option[Int] = None
        val lastPublicationTime: Option[Long] = None

        discard(
          SQL"""insert into #$TableName(
              #$ParticipantIdColumnName,
              #$LedgerEndColumnName,
              #$LedgerEndSequentialIdColumnName,
              #$LedgerEndStringInterningIdColumnName,
              #$LedgerEndPublicationTimeColumnName
            ) values(
              ${participantId.unwrap: String},
              ${lastOffset.map(_.unwrap)},
              $lastEventSeqId,
              $lastStringInterningId,
              $lastPublicationTime
            )"""
            .execute()(connection)
        )
      case Some(ParameterStorageBackend.IdentityParams(`participantId`)) =>
        logger.info(
          s"Found existing database for participantId '${params.participantId}'"
        )
      case Some(ParameterStorageBackend.IdentityParams(existing)) =>
        logger.error(
          s"Found existing database with mismatching participantId: existing '$existing', provided '${params.participantId}'"
        )
        throw new MismatchException.ParticipantId(
          existing = existing,
          provided = params.participantId,
        )
    }
  }

  override def ledgerIdentity(
      connection: Connection
  ): Option[ParameterStorageBackend.IdentityParams] =
    SQL"select #$ParticipantIdColumnName from #$TableName"
      .as(LedgerIdentityParser.singleOpt)(connection)

  override def updatePrunedUptoInclusive(
      prunedUpToInclusive: Offset
  )(connection: Connection): Unit =
    discard(
      SQL"""
        update lapi_parameters set participant_pruned_up_to_inclusive=$prunedUpToInclusive
        where participant_pruned_up_to_inclusive < $prunedUpToInclusive or participant_pruned_up_to_inclusive is null
        """
        .execute()(connection)
    )
  override def updatePrunedAllDivulgedContractsUpToInclusive(
      prunedUpToInclusive: Offset
  )(connection: Connection): Unit =
    discard(
      SQL"""
        update lapi_parameters set participant_all_divulged_contracts_pruned_up_to_inclusive=$prunedUpToInclusive
        where participant_all_divulged_contracts_pruned_up_to_inclusive < $prunedUpToInclusive or participant_all_divulged_contracts_pruned_up_to_inclusive is null
        """
        .execute()(connection)
    )
  private val SqlSelectMostRecentPruning =
    SQL"select participant_pruned_up_to_inclusive from lapi_parameters"

  override def prunedUpToInclusive(connection: Connection): Option[Offset] =
    SqlSelectMostRecentPruning
      .as(offset("participant_pruned_up_to_inclusive").?.single)(connection)

  private val SqlSelectMostRecentPruningAllDivulgedContracts =
    SQL"select participant_all_divulged_contracts_pruned_up_to_inclusive from lapi_parameters"

  override def participantAllDivulgedContractsPrunedUpToInclusive(
      connection: Connection
  ): Option[Offset] =
    SqlSelectMostRecentPruningAllDivulgedContracts
      .as(offset("participant_all_divulged_contracts_pruned_up_to_inclusive").?.single)(
        connection
      )

  private val SqlSelectMostRecentPruningAndLedgerEnd =
    SQL"select participant_pruned_up_to_inclusive, #$LedgerEndColumnName from lapi_parameters"

  private val PruneUptoInclusiveAndLedgerEndParser
      : RowParser[ParameterStorageBackend.PruneUptoInclusiveAndLedgerEnd] =
    offset("participant_pruned_up_to_inclusive").? ~ LedgerEndOffsetParser map {
      case pruneUptoInclusive ~ ledgerEndOffset =>
        ParameterStorageBackend.PruneUptoInclusiveAndLedgerEnd(
          pruneUptoInclusive = pruneUptoInclusive,
          ledgerEnd = ledgerEndOffset,
        )
    }

  override def prunedUpToInclusiveAndLedgerEnd(
      connection: Connection
  ): ParameterStorageBackend.PruneUptoInclusiveAndLedgerEnd =
    SqlSelectMostRecentPruningAndLedgerEnd
      .as(PruneUptoInclusiveAndLedgerEndParser.singleOpt)(connection)
      .getOrElse(
        ParameterStorageBackend.PruneUptoInclusiveAndLedgerEnd(
          pruneUptoInclusive = None,
          ledgerEnd = None,
        )
      )

  override def cleanSynchronizerIndex(synchronizerId: SynchronizerId)(
      connection: Connection
  ): Option[SynchronizerIndex] =
    // not using stringInterning here to allow broader usage with tricky state inspection integration tests
    SQL"""
      SELECT internal_id
      FROM lapi_string_interning
      WHERE external_string = ${"d|" + synchronizerId.toProtoPrimitive}
      """
      .asSingleOpt(int("internal_id"))(connection)
      .flatMap(internedSynchronizerId =>
        SQL"""
            SELECT
              sequencer_timestamp,
              repair_timestamp,
              repair_counter,
              record_time
            FROM
              lapi_ledger_end_synchronizer_index
            WHERE
              synchronizer_id = $internedSynchronizerId
            """
          .asSingleOpt(
            for {
              repairTimestampO <- long("repair_timestamp").?
              repairCounterO <- long("repair_counter").?
              sequencerTimestampO <- long("sequencer_timestamp").?
              recordTime <- long("record_time")
            } yield {
              val repairIndex = (repairTimestampO, repairCounterO) match {
                case (Some(repairTimestamp), Some(repairCounter)) =>
                  List(
                    SynchronizerIndex.of(
                      RepairIndex(
                        timestamp = CantonTimestamp.ofEpochMicro(repairTimestamp),
                        counter = RepairCounter(repairCounter),
                      )
                    )
                  )

                case (None, None) =>
                  Nil

                case _ =>
                  throw new IllegalStateException(
                    s"Invalid persisted data in lapi_ledger_end_synchronizer_index table: either both repair_counter and repair_timestamp should be defined or none of them, but an invalid combination found for synchronizer:${synchronizerId.toProtoPrimitive} repair_counter: $repairCounterO, repair_timestamp: $repairTimestampO"
                  )
              }
              val sequencerIndex = sequencerTimestampO
                .map(CantonTimestamp.ofEpochMicro)
                .map(SequencerIndex.apply)
                .map(SynchronizerIndex.of)
                .toList
              val recordTimeSynchronizerIndex = SynchronizerIndex.of(
                CantonTimestamp.ofEpochMicro(recordTime)
              )
              (recordTimeSynchronizerIndex :: repairIndex ::: sequencerIndex)
                .reduceOption(_ max _)
                .getOrElse(
                  throw new IllegalStateException(
                    s"Invalid persisted data in lapi_ledger_end_synchronizer_index table: none of the optional fields are defined for synchronizer ${synchronizerId.toProtoPrimitive}"
                  )
                )
            }
          )(connection)
      )

  override def updatePostProcessingEnd(postProcessingEnd: Option[Offset])(
      connection: Connection
  ): Unit =
    batchUpsert(
      "INSERT INTO lapi_post_processing_end VALUES ({postProcessingEnd})",
      "UPDATE lapi_post_processing_end SET post_processing_end = {postProcessingEnd}",
      List(
        Seq[NamedParameter](
          "postProcessingEnd" -> postProcessingEnd.map(_.unwrap)
        )
      ),
    )(connection)

  override def postProcessingEnd(connection: Connection): Option[Offset] =
    SQL"select post_processing_end from lapi_post_processing_end"
      .asSingleOpt(
        offset("post_processing_end").?
      )(connection)
      .flatten

  private def batchSql(
      sqlWithNamedParams: String,
      namedParamsBatch: List[Seq[NamedParameter]],
  )(connection: Connection): Array[Int] =
    namedParamsBatch match {
      case Nil => Array.empty
      case head :: tail =>
        BatchSql(sqlWithNamedParams, head, tail*).execute()(connection)
    }

  private def batchUpsert(
      insertSql: String,
      updateSql: String,
      namedParamsBatch: List[Seq[NamedParameter]],
  )(connection: Connection): Unit = {
    val updateCounts = batchSql(updateSql, namedParamsBatch)(connection)
    val insertCounts = batchSql(
      insertSql,
      updateCounts.toList
        .zip(namedParamsBatch)
        .filter(
          _._1 == 0
        ) // collecting all failed updates, these are the missing entries in the table, which we need to insert
        .map(_._2),
    )(connection)
    assert(
      insertCounts.forall(_ == 1),
      "batch upserting should succeed for all inserts (maybe batch upserts are running in parallel?)",
    )
  }

}
