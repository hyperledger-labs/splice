// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.{NonEmptyList, OptionT}
import cats.syntax.semigroup.*
import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.ledger.javaapi.data.{CreatedEvent, Event, ExercisedEvent, Identifier, Transaction}
import com.daml.metrics.api.MetricsContext
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.HistoryBackfilling.{
  DestinationBackfillingInfo,
  DestinationHistory,
  SourceMigrationInfo,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{HasIngestionSink, IngestionFilter}
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsQueries}
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  DomainRecordTimeRange,
  EventId,
  LegacyOffset,
  ValueJsonCodecProtobuf as ProtobufCodec,
}
import com.digitalasset.canton.config.CantonRequireTypes.String256M
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.{GetResult, JdbcProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.resource.DbStorage.SQLActionBuilderChain
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.store.ImportUpdatesBackfilling.{
  DestinationImportUpdates,
  DestinationImportUpdatesBackfillingInfo,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import slick.jdbc.canton.SQLActionBuilder

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
import com.digitalasset.canton.discard.Implicits.*
import com.digitalasset.canton.util.MonadUtil
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection

/** Stores all original daml updates visible to `updateStreamParty`.
  *
  * ==Related triggers==
  *
  * The following triggers perform long-running background tasks related to [[UpdateHistory]],
  * and must complete in the following order:
  *
  *   1. [[DeleteCorruptAcsSnapshotTrigger]] deletes all ACS snapshots that were computed from this UpdateHistory while
  *      it was missing import updates. Such snapshots are easily identified by the trigger.
  *      UpdateHistory has an in-memory flag (as part of [[UpdateHistory.State]]) that stores
  *      whether all corrupt updates have been deleted.
  *      See [[corruptAcsSnapshotsDeleted]] and [[markCorruptAcsSnapshotsDeleted]].
  *   1. [[ScanHistoryBackfillingTrigger]] backfills missing updates from peer scan applications.
  *      Information on the progress of this backfilling process is stored in the database.
  *      See [[destinationHistory.markBackfillingComplete]] and [[destinationHistory.markImportUpdatesBackfillingComplete]].
  *   1. [[AcsSnapshotTrigger]] backfills ACS snapshots.
  */
class UpdateHistory(
    storage: DbStorage,
    val domainMigrationInfo: DomainMigrationInfo,
    storeName: String,
    participantId: ParticipantId,
    val updateStreamParty: PartyId,
    val backfillingRequired: BackfillingRequirement,
    override protected val loggerFactory: NamedLoggerFactory,
    enableissue12777Workaround: Boolean,
    enableImportUpdateBackfill: Boolean,
    metrics: HistoryMetrics,
)(implicit
    ec: ExecutionContext,
    closeContext: CloseContext,
) extends HasIngestionSink
    with AcsJdbcTypes
    with AcsQueries
    with NamedLogging
    with AutoCloseable {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile

  import profile.api.jdbcActionExtensionMethods
  import UpdateHistory.*

  private[this] def domainMigrationId = domainMigrationInfo.currentMigrationId

  private val state = new AtomicReference[State](State.empty())

  def lastIngestedRecordTime: Option[CantonTimestamp] = state.get().lastIngestedRecordTime

  private def advanceLastIngestedRecordTime(ts: CantonTimestamp): Unit = {
    val newState = state.updateAndGet { s =>
      s.copy(lastIngestedRecordTime = Some(ts))
    }
    (for {
      lastIngestedRecordTime <- newState.lastIngestedRecordTime
    } yield metrics.UpdateHistory.latestRecordTime.updateValue(lastIngestedRecordTime)(
      MetricsContext(
        "update_stream_party" -> updateStreamParty.toProtoPrimitive,
        "store_name" -> storeName,
      )
    )).discard
  }

  def waitUntilInitialized: Future[Unit] = state.get().initialized.future

  def historyId: Long =
    state
      .get()
      .historyId
      .getOrElse(throw new RuntimeException("Using historyId before it was assigned"))

  def isReady: Boolean = state.get().historyId.isDefined

  override def close(): Unit = metrics.close()

  lazy val ingestionSink: MultiDomainAcsStore.IngestionSink =
    new MultiDomainAcsStore.IngestionSink {
      override def ingestionFilter: IngestionFilter = IngestionFilter(
        primaryParty = updateStreamParty,
        includeInterfaces = Seq.empty,
        includeCreatedEventBlob = false,
      )

      // TODO(#948): This can be removed eventually
      def issue12777Workaround()(implicit tc: TraceContext): Future[Unit] = {
        val action = for {
          oldHistoryIdOpt <- sql"""
             select id
             from update_history_descriptors
             where party = $updateStreamParty
              and participant_id = $participantId
              and store_name is NULL
             """
            .as[Long]
            .headOption
          newHistoryIdOpt <- sql"""
             select id
             from update_history_descriptors
             where party = $updateStreamParty
              and participant_id = $participantId
              and store_name = ${lengthLimited(storeName)}
             """
            .as[Long]
            .headOption
          _ <- (oldHistoryIdOpt, newHistoryIdOpt) match {
            case (Some(oldHistoryId), Some(newHistoryId)) =>
              logger.info(
                s"Found old descriptor with id $oldHistoryId and new descriptor with id $newHistoryId where party is $updateStreamParty. " +
                  s"Deleting data for the new descriptor, and updating the store name on the old descriptor to $storeName."
              )
              for {
                d1 <- sqlu"delete from update_history_exercises where history_id = $newHistoryId"
                d2 <- sqlu"delete from update_history_creates where history_id = $newHistoryId"
                d3 <- sqlu"delete from update_history_assignments where history_id = $newHistoryId"
                d4 <-
                  sqlu"delete from update_history_unassignments where history_id = $newHistoryId"
                d5 <- sqlu"delete from update_history_transactions where history_id = $newHistoryId"
                d6 <-
                  sqlu"delete from update_history_last_ingested_offsets where history_id = $newHistoryId"
                d7 <- sqlu"delete from update_history_descriptors where id = $newHistoryId"
                _ <-
                  sqlu"""
                  update update_history_descriptors
                  set store_name = ${lengthLimited(storeName)}
                  where id = $oldHistoryId
                """
              } yield (
                logger.info(
                  s"Deleted ($d1 exercise, $d2 create, $d3 assignment, $d4 unassignment, $d5 transaction, $d6 offset, $d7 descriptor) rows."
                )
              )
            case (Some(oldHistoryId), None) =>
              logger.info(
                s"Found old descriptor with id $oldHistoryId where party is $updateStreamParty, but no new descriptor. " +
                  s"Updating the store name on the old descriptor to $storeName."
              )
              sqlu"""
                update update_history_descriptors
                set store_name = ${lengthLimited(storeName)}
                where id = $oldHistoryId
              """
            case (None, _) =>
              logger.info(
                s"No old descriptor found for party $updateStreamParty, nothing to do."
              )
              DBIOAction.successful(())
          }
        } yield ()
        storage.queryAndUpdate(action.transactionally, "issue12777Workaround")
      }

      override def initialize()(implicit
          traceContext: TraceContext
      ): Future[IngestionStart] = {
        logger.info(s"Initializing update history ingestion sink for party $updateStreamParty")

        // Notes:
        // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
        for {
          _ <-
            if (enableissue12777Workaround) {
              issue12777Workaround()
            } else {
              Future.unit
            }
          _ <- storage
            .update(
              sql"""
            insert into update_history_descriptors (party, participant_id, store_name)
            values ($updateStreamParty, $participantId, ${lengthLimited(storeName)})
            on conflict do nothing
           """.asUpdate,
              "initialize.1",
            )

          newHistoryId <- storage
            .querySingle(
              sql"""
             select id
             from update_history_descriptors
             where party = $updateStreamParty and participant_id = $participantId and store_name = ${lengthLimited(
                  storeName
                )}
             """.as[Long].headOption,
              "initialize.2",
            )
            .getOrRaise(
              new RuntimeException(
                s"No row for ($updateStreamParty,$participantId) found, which was just inserted!"
              )
            )

          _ <- storage
            .update(
              sql"""
             insert into update_history_last_ingested_offsets (history_id, migration_id)
             values ($newHistoryId, $domainMigrationId)
             on conflict do nothing
             """.asUpdate,
              "initialize.3",
            )

          lastIngestedOffset <- storage
            .querySingle(
              sql"""
             select last_ingested_offset
             from update_history_last_ingested_offsets
             where history_id = $newHistoryId and migration_id = $domainMigrationId
             """.as[Option[String]].headOption,
              "initialize.4",
            )
            .getOrRaise(
              new RuntimeException(s"No row for $newHistoryId found, which was just inserted!")
            )
            .map(_.map(LegacyOffset.Api.assertFromStringToLong))

          _ <- cleanUpDataAfterDomainMigration(newHistoryId)
        } yield {
          state.updateAndGet(
            _.copy(
              historyId = Some(newHistoryId)
            )
          )
          state.get().initialized.trySuccess(()).discard
          lastIngestedOffset match {
            case Some(offset) =>
              logger.info(s"${description()} resumed at offset $offset")
              IngestionStart.ResumeAtOffset(offset)
            case None =>
              logger.info(s"${description()} initialized")
              // Missing updates will be later backfilled using `ScanHistoryBackfillingTrigger`.
              IngestionStart.UpdateHistoryInitAtLatestPrunedOffset
          }
        }
      }

      /** A description of this update history instance, to be used in log messages */
      private def description() =
        s"UpdateHistory(party=$updateStreamParty, participantId=$participantId, migrationId=$domainMigrationId, historyId=$historyId)"

      override def ingestAcsStreamInBatches(
          source: Source[Seq[BaseLedgerConnection.ActiveContractsItem], NotUsed],
          offset: Long,
      )(implicit tc: TraceContext, mat: Materializer): Future[Unit] = {
        logger.info(
          s"${description()} started from the ACS at offset $offset, " +
            s"but the ACS was already initialized. " +
            "This is only fine in the following cases:\n" +
            "- This is an SV node that joined late, and has thus missed past updates for the multi-hosted SV party. " +
            "In this case, the node needs to download the missing updates from other SV nodes.\n" +
            "- This is a participant starting after a hard domain migration. " +
            "In this case, all items in the ACS must come from the previous domain migration."
        )

        Future.unit
      }

      override def ingestUpdateBatch(batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint])(implicit
          traceContext: TraceContext
      ): Future[Unit] = {
        MonadUtil
          .sequentialTraverse(batch.toList) { updateOrCheckpoint =>
            val offset: Long = updateOrCheckpoint.offset
            val recordTime = updateOrCheckpoint match {
              case TreeUpdateOrOffsetCheckpoint.Update(ReassignmentUpdate(reassignment), _) =>
                Some(reassignment.recordTime)
              case TreeUpdateOrOffsetCheckpoint.Update(TransactionTreeUpdate(tree), _) =>
                Some(CantonTimestamp.assertFromInstant(tree.getRecordTime))
              case TreeUpdateOrOffsetCheckpoint.Checkpoint(_) =>
                None
            }

            val timeIngestion = (future: Future[Unit]) =>
              metrics.UpdateHistory.latency
                .timeFuture(future)(
                  metrics.metricsContextFromUpdate(updateOrCheckpoint, backfilling = false)
                )

            timeIngestion {
              // Note: in theory, it's enough if this action is atomic - there should only be a single
              // ingestion sink storing updates for the given (participant, party, migrationId) tuple,
              // so there should be no concurrent updates.
              // In practice, we still want to have some protection against duplicate inserts, in case
              // the ingestion service is buggy or there are two misconfigured apps trying to ingest the same updates.
              // This is implemented with a unique index in the database schema.
              val action = readOffsetAction()
                .flatMap({
                  case None =>
                    logger.debug(
                      s"History $historyId migration $domainMigrationId ingesting None => $offset @ $recordTime"
                    )
                    for {
                      ingestedEvents <- ingestUpdateOrCheckpoint_(
                        updateOrCheckpoint,
                        domainMigrationId,
                      )
                      _ <- updateOffset(offset)
                    } yield ingestedEvents
                  case Some(lastIngestedOffset) =>
                    if (offset <= lastIngestedOffset) {
                      updateOrCheckpoint match {
                        case _: TreeUpdateOrOffsetCheckpoint.Update =>
                          logger.warn(
                            s"Update offset $offset <= last ingested offset $lastIngestedOffset for ${description()}, skipping database actions. " +
                              "This is expected if the SQL query was automatically retried after a transient database error. " +
                              "Otherwise, this is unexpected and most likely caused by two identical UpdateIngestionService instances " +
                              "ingesting into the same logical database."
                          )
                        case _: TreeUpdateOrOffsetCheckpoint.Checkpoint =>
                          // we can receive an offset equal to the last ingested and that can be safely ignore
                          if (offset < lastIngestedOffset) {
                            logger.warn(
                              s"Checkpoint offset $offset < last ingested offset $lastIngestedOffset for ${description()}, skipping database actions. " +
                                "This is expected if the SQL query was automatically retried after a transient database error. " +
                                "Otherwise, this is unexpected and most likely caused by two identical UpdateIngestionService instances " +
                                "ingesting into the same logical database."
                            )
                          }
                      }
                      DBIO.successful(IngestedEvents(0, 0))
                    } else {
                      logger.debug(
                        s"History $historyId migration $domainMigrationId ingesting $lastIngestedOffset => $offset @ $recordTime"
                      )
                      for {
                        ingestedEvents <- ingestUpdateOrCheckpoint_(
                          updateOrCheckpoint,
                          domainMigrationId,
                        )
                        _ <- updateOffset(offset)
                      } yield ingestedEvents
                    }
                })
                .transactionally

              storage
                .queryAndUpdate(action, "ingestUpdate")
                .map { ingestedEvents =>
                  recordTime.foreach(advanceLastIngestedRecordTime)
                  metrics.UpdateHistory.eventCount.inc(ingestedEvents.numCreatedEvents)(
                    MetricsContext("event_type" -> "created")
                  )
                  metrics.UpdateHistory.eventCount.inc(ingestedEvents.numExercisedEvents)(
                    MetricsContext("event_type" -> "exercised")
                  )
                }
            }
          }
          .map(_ => ())
      }

      private def updateOffset(offset: Long): DBIOAction[?, NoStream, Effect.Write] =
        sqlu"""
        update update_history_last_ingested_offsets
        set last_ingested_offset = ${lengthLimited(LegacyOffset.Api.fromLong(offset))}
        where history_id = $historyId and migration_id = $domainMigrationId
      """
    }

  private def ingestUpdateOrCheckpoint_(
      updateOrCheckpoint: TreeUpdateOrOffsetCheckpoint,
      migrationId: Long,
  ): DBIOAction[IngestedEvents, NoStream, Effect.Read & Effect.Write] = {
    updateOrCheckpoint match {
      case TreeUpdateOrOffsetCheckpoint.Update(update, _) =>
        ingestUpdate_(update, migrationId)
      case TreeUpdateOrOffsetCheckpoint.Checkpoint(_) => DBIO.successful(IngestedEvents(0, 0))
    }
  }

  private def ingestUpdate_(
      update: TreeUpdate,
      migrationId: Long,
  ): DBIOAction[IngestedEvents, NoStream, Effect.Read & Effect.Write] = {
    update match {
      case ReassignmentUpdate(reassignment) =>
        ingestReassignment(reassignment, migrationId).map(_ => IngestedEvents(0, 0))
      case TransactionTreeUpdate(tree) =>
        ingestTransactionTree(tree, migrationId)
    }
  }

  private def ingestReassignment(
      reassignment: Reassignment[ReassignmentEvent],
      migrationId: Long,
  ): DBIOAction[?, NoStream, Effect.Write] = {
    reassignment match {
      case Reassignment(_, _, _, event: ReassignmentEvent.Assign) =>
        ingestAssignment(reassignment, event, migrationId)
      case Reassignment(_, _, _, event: ReassignmentEvent.Unassign) =>
        ingestUnassignment(reassignment, event, migrationId)
    }
  }

  private def ingestUnassignment(
      reassignment: Reassignment[?],
      event: ReassignmentEvent.Unassign,
      migrationId: Long,
  ): DBIOAction[?, NoStream, Effect.Write] = {
    val safeUpdateId = lengthLimited(reassignment.updateId)
    val safeRecordTime = reassignment.recordTime
    val safeParticipantOffset = lengthLimited(LegacyOffset.Api.fromLong(reassignment.offset))
    val safeUnassignId = lengthLimited(event.unassignId)
    val safeContractId = lengthLimited(event.contractId.contractId)
    metrics.UpdateHistory.unassignments.mark()
    sqlu"""
      insert into update_history_unassignments(
        history_id,update_id,record_time,
        participant_offset,domain_id,migration_id,
        reassignment_counter,target_domain,
        reassignment_id,submitter,
        contract_id
      )
      values (
        $historyId, $safeUpdateId, $safeRecordTime,
        $safeParticipantOffset, ${event.source}, $migrationId,
        ${event.counter}, ${event.target},
        $safeUnassignId, ${event.submitter},
        $safeContractId
      )
    """
  }

  private def ingestAssignment(
      reassignment: Reassignment[?],
      event: ReassignmentEvent.Assign,
      migrationId: Long,
  ): DBIOAction[?, NoStream, Effect.Write] = {
    val safeUpdateId = lengthLimited(reassignment.updateId)
    val safeRecordTime = reassignment.recordTime
    val safeParticipantOffset = lengthLimited(LegacyOffset.Api.fromLong(reassignment.offset))
    val safeUnassignId = lengthLimited(event.unassignId)
    val safeContractId = lengthLimited(event.createdEvent.getContractId)
    val safeEventId = lengthLimited(
      EventId.prefixedFromUpdateIdAndNodeId(reassignment.updateId, event.createdEvent.getNodeId)
    )
    val templateId = event.createdEvent.getTemplateId
    val templateIdModuleName = lengthLimited(templateId.getModuleName)
    val templateIdEntityName = lengthLimited(templateId.getEntityName)
    val templateIdPackageId = lengthLimited(templateId.getPackageId)
    val safePackageName = lengthLimited(event.createdEvent.getPackageName)
    val createArguments =
      String256M.tryCreate(ProtobufCodec.serializeValue(event.createdEvent.getArguments))
    val contractKey =
      event.createdEvent.getContractKey.toScala
        .map(ProtobufCodec.serializeValue)
        .map(s => String256M.tryCreate(s))
    val safeCreatedAt = CantonTimestamp.assertFromInstant(event.createdEvent.createdAt)
    val safeSignatories = event.createdEvent.getSignatories.asScala.toSeq.map(lengthLimited)
    val safeObservers = event.createdEvent.getObservers.asScala.toSeq.map(lengthLimited)
    metrics.UpdateHistory.assignments.mark()
    sqlu"""
      insert into update_history_assignments(
        history_id,update_id,record_time,
        participant_offset,domain_id,migration_id,
        reassignment_counter,source_domain,
        reassignment_id,submitter,
        contract_id, event_id, created_at,
        template_id_package_id, template_id_module_name, template_id_entity_name,
        package_name, create_arguments,
        signatories, observers, contract_key
      )
      values (
        $historyId, $safeUpdateId, $safeRecordTime,
        $safeParticipantOffset, ${event.target}, $migrationId,
        ${event.counter}, ${event.source},
        $safeUnassignId, ${event.submitter},
        $safeContractId, $safeEventId, $safeCreatedAt,
        $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
        $safePackageName, $createArguments::jsonb,
        $safeSignatories, $safeObservers, $contractKey::jsonb

      )
    """
  }

  private def ingestTransactionTree(
      tree: Transaction,
      migrationId: Long,
  ): DBIOAction[IngestedEvents, NoStream, Effect.Read & Effect.Write] = {
    metrics.UpdateHistory.transactionsTrees.mark()
    insertTransactionUpdateRow(tree, migrationId)
      .flatMap(updateRowId => {
        // Note: the order of elements in the eventsById map doesn't matter, and is not preserved here.
        // The order of elements in the rootEventIds and childEventIds lists DOES matter, and needs to be preserved.
        DBIOAction.seq[Effect.Write](
          tree.getEventsById.values().asScala.toSeq.map {
            case created: CreatedEvent =>
              insertCreateEventRow(tree.getUpdateId, created, tree, migrationId, updateRowId)
            case exercised: ExercisedEvent =>
              insertExerciseEventRow(
                tree.getUpdateId,
                exercised,
                tree,
                migrationId,
                updateRowId,
                tree.getChildNodeIds(exercised).asScala.toSeq.map(_.intValue()),
              )
            case e =>
              throw new RuntimeException(s"Unsupported event type: $e")
          }*
        )
      })
      .map(_ => IngestedEvents.eventCount(Seq(tree)))
  }

  private def insertTransactionUpdateRow(
      tree: Transaction,
      migrationId: Long,
  ): DBIOAction[Long, NoStream, Effect.Read & Effect.Write] = {
    val safeUpdateId = lengthLimited(tree.getUpdateId)
    val safeRecordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)
    val safeParticipantOffset = lengthLimited(LegacyOffset.Api.fromLong(tree.getOffset))
    val safeSynchronizerId = lengthLimited(tree.getSynchronizerId)
    val safeEffectiveAt = CantonTimestamp.assertFromInstant(tree.getEffectiveAt)
    val safeRootEventIds = tree.getRootNodeIds.asScala.toSeq
      .map(EventId.prefixedFromUpdateIdAndNodeId(tree.getUpdateId, _))
      .map(lengthLimited)
    val safeWorkflowId = lengthLimited(tree.getWorkflowId)
    val safeCommandId = lengthLimited(tree.getCommandId)

    (sql"""
      insert into update_history_transactions(
        history_id, update_id, record_time,
        participant_offset, domain_id, migration_id,
        effective_at, root_event_ids, workflow_id, command_id
      )
      values (
        $historyId, $safeUpdateId, $safeRecordTime,
        $safeParticipantOffset, $safeSynchronizerId, $migrationId,
        $safeEffectiveAt, $safeRootEventIds, $safeWorkflowId, $safeCommandId
      )
      returning row_id
    """.asUpdateReturning[Long].head)
  }

  private def insertCreateEventRow(
      updateId: String,
      event: CreatedEvent,
      tree: Transaction,
      migrationId: Long,
      updateRowId: Long,
  ): DBIOAction[?, NoStream, Effect.Write] = {
    val safeEventId = lengthLimited(
      EventId.prefixedFromUpdateIdAndNodeId(updateId, event.getNodeId)
    )
    val safeContractId = lengthLimited(event.getContractId)
    val templateId = event.getTemplateId
    val templateIdModuleName = lengthLimited(templateId.getModuleName)
    val templateIdEntityName = lengthLimited(templateId.getEntityName)
    val templateIdPackageId = lengthLimited(templateId.getPackageId)
    val safePackageName = lengthLimited(event.getPackageName)
    val createArguments = String256M.tryCreate(ProtobufCodec.serializeValue(event.getArguments))
    val contractKey = event.getContractKey.toScala
      .map(ProtobufCodec.serializeValue)
      .map(s => String256M.tryCreate(s))
    val safeCreatedAt = CantonTimestamp.assertFromInstant(event.createdAt)
    val safeSignatories = event.getSignatories.asScala.toSeq.map(lengthLimited)
    val safeObservers = event.getObservers.asScala.toSeq.map(lengthLimited)
    val recordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)
    val safeUpdateId = lengthLimited(tree.getUpdateId)
    val safeDomainId = lengthLimited(tree.getSynchronizerId)

    sqlu"""
      insert into update_history_creates(
        history_id, event_id, update_row_id,
        contract_id, created_at,
        template_id_package_id, template_id_module_name, template_id_entity_name,
        package_name, create_arguments, signatories, observers,
        contract_key,
        record_time, update_id, domain_id, migration_id
      )
      values (
        $historyId, $safeEventId, $updateRowId,
        $safeContractId, $safeCreatedAt,
        $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
        $safePackageName, $createArguments::jsonb, $safeSignatories, $safeObservers,
        $contractKey::jsonb,
        $recordTime, $safeUpdateId, $safeDomainId, $migrationId
      )
    """
  }

  private def insertExerciseEventRow(
      updateId: String,
      event: ExercisedEvent,
      tree: Transaction,
      migrationId: Long,
      updateRowId: Long,
      childNodeids: Seq[Int],
  ): DBIOAction[?, NoStream, Effect.Write] = {
    val safeEventId = lengthLimited(
      EventId.prefixedFromUpdateIdAndNodeId(updateId, event.getNodeId)
    )
    val safeChoice = lengthLimited(event.getChoice)
    val safeContractId = lengthLimited(event.getContractId)
    val safeChildEventIds = childNodeids
      .map(EventId.prefixedFromUpdateIdAndNodeId(updateId, _))
      .map(lengthLimited)
    val templateId = event.getTemplateId
    val templateIdModuleName = lengthLimited(templateId.getModuleName)
    val templateIdEntityName = lengthLimited(templateId.getEntityName)
    val templateIdPackageId = lengthLimited(templateId.getPackageId)
    val safePackageName = lengthLimited(event.getPackageName)
    val choiceArguments =
      String256M.tryCreate(ProtobufCodec.serializeValue(event.getChoiceArgument))
    val exerciseResult =
      String256M.tryCreate(ProtobufCodec.serializeValue(event.getExerciseResult))
    val safeActingParties = event.getActingParties.asScala.toSeq.map(lengthLimited)
    val interfaceIdModuleName =
      event.getInterfaceId.toScala.map(i => lengthLimited(i.getModuleName))
    val interfaceIdEntityName =
      event.getInterfaceId.toScala.map(i => lengthLimited(i.getEntityName))
    val interfaceIdPackageId =
      event.getInterfaceId.toScala.map(i => lengthLimited(i.getPackageId))
    val recordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)
    val safeUpdateId = lengthLimited(tree.getUpdateId)
    val safeDomainId = lengthLimited(tree.getSynchronizerId)

    sqlu"""
      insert into update_history_exercises(
        history_id, event_id, update_row_id,
        child_event_ids, choice,
        template_id_package_id, template_id_module_name, template_id_entity_name,
        contract_id, consuming,
        package_name, argument, result,
        acting_parties,
        interface_id_package_id, interface_id_module_name, interface_id_entity_name,
        record_time, update_id, domain_id, migration_id
      )
      values (
        $historyId, $safeEventId, $updateRowId,
        $safeChildEventIds, $safeChoice,
        $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
        $safeContractId, ${event.isConsuming},
        $safePackageName, $choiceArguments::jsonb, $exerciseResult::jsonb,
        $safeActingParties,
        $interfaceIdPackageId, $interfaceIdModuleName, $interfaceIdEntityName,
        $recordTime, $safeUpdateId, $safeDomainId, $migrationId
      )
    """
  }

  def migrationsWithCorruptSnapshots()(implicit tc: TraceContext): Future[Set[Long]] = {
    for {
      migrationsWithImportUpdates <- storage
        .query(
          // The following is equivalent to:
          //    """select distinct migration_id
          //    from update_history_transactions
          //    where history_id = $historyId
          //    and record_time = ${CantonTimestamp.MinValue}"""
          // but it uses a recursive CTE to implement a loose index scan
          sql"""
              with recursive t as (
                (
                  select migration_id
                  from update_history_transactions
                  where history_id = $historyId and record_time = ${CantonTimestamp.MinValue}
                  order by migration_id limit 1
                )
                union all
                select (
                  select migration_id
                  from update_history_transactions
                  where migration_id > t.migration_id and history_id = $historyId and record_time = ${CantonTimestamp.MinValue}
                  order by migration_id limit 1
                )
                from t
                where t.migration_id is not null
              )
              select migration_id from t where migration_id is not null
             """.as[Long],
          "deleteInvalidAcsSnapshots.1",
        )
      firstMigrationIdO <- getFirstMigrationId(historyId)
      migrationsWithSnapshots <- storage
        .query(
          sql"""
               select distinct migration_id
               from acs_snapshot
               where history_id = $historyId
             """.as[Long],
          "deleteInvalidAcsSnapshots.2",
        )
    } yield {
      firstMigrationIdO match {
        case None =>
          Set.empty
        case Some(firstMigrationId) =>
          val migrationsThatNeedImportUpdates: Set[Long] =
            migrationsWithSnapshots.toSet - firstMigrationId
          migrationsThatNeedImportUpdates -- migrationsWithImportUpdates.toSet
      }
    }
  }

  def corruptAcsSnapshotsDeleted: Boolean = state.get.corruptSnapshotsDeleted
  def markCorruptAcsSnapshotsDeleted(): Unit = {
    state.updateAndGet(_.copy(corruptSnapshotsDeleted = true))
    ()
  }

  private[this] def cleanUpDataAfterDomainMigration(
      historyId: Long
  )(implicit tc: TraceContext): Future[Unit] = {
    val previousMigrationId = domainMigrationInfo.currentMigrationId - 1
    domainMigrationInfo.migrationTimeInfo match {
      case Some(info) =>
        for {
          _ <-
            if (info.synchronizerWasPaused) {
              for {
                _ <- verifyNoRolledBackAcsSnapshots(
                  historyId,
                  previousMigrationId,
                  info.acsRecordTime,
                )
                _ <- verifyNoRolledBackData(historyId, previousMigrationId, info.acsRecordTime)
              } yield ()
            } else {
              for {
                _ <- deleteAcsSnapshotsAfter(historyId, previousMigrationId, info.acsRecordTime)
                _ <- deleteRolledBackUpdateHistory(
                  historyId,
                  previousMigrationId,
                  info.acsRecordTime,
                )
              } yield ()
            }
        } yield ()
      case _ =>
        logger.debug("No previous domain migration, not checking or deleting updates")
        Future.unit
    }
  }

  private[this] def verifyNoRolledBackData(
      historyId: Long, // Not using the storeId from the state, as the state might not be updated yet
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    val action = DBIO
      .sequence(
        Seq(
          sql"""
            select count(*) from update_history_creates
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """.as[Long].head,
          sql"""
            select count(*) from update_history_exercises
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """.as[Long].head,
          sql"""
            select count(*) from update_history_transactions
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """.as[Long].head,
          sql"""
            select count(*) from update_history_assignments
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """.as[Long].head,
          sql"""
            select count(*) from update_history_unassignments
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """.as[Long].head,
        )
      )
      .map(rows =>
        if (rows.sum > 0) {
          throw new IllegalStateException(
            s"Found $rows rows for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime, " +
              "but the configuration says the domain was paused during the migration. " +
              "Check the domain migration configuration and the content of the update history database."
          )
        } else {
          logger.debug(
            s"No updates found for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime"
          )
        }
      )
    storage.query(action, "verifyNoRolledBackData")
  }

  private[this] def deleteRolledBackUpdateHistory(
      historyId: Long, // Not using the storeId from the state, as the state might not be updated yet
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(
      s"Deleting all updates for $updateStreamParty where migration = $migrationId and record time > $recordTime"
    )
    val action = DBIO
      .sequence(
        Seq(
          sqlu"""
            delete from update_history_creates
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """,
          sqlu"""
            delete from update_history_exercises
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """,
          sqlu"""
            delete from update_history_transactions
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """,
          sqlu"""
            delete from update_history_assignments
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """,
          sqlu"""
            delete from update_history_unassignments
            where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
          """,
        )
      )
      .map(rows =>
        if (rows.sum > 0) {
          logger.info(
            s"Deleted $rows rows for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime. " +
              "This is expected during a disaster recovery, where we are rolling back the domain to a previous state. " +
              "In is NOT expected during regular hard domain migrations."
          )
        } else {
          logger.info(s"No rows deleted for $updateStreamParty")
        }
      )
    storage.update(action, "deleteRolledBackUpdateHistory")
  }

  /** Deletes all ACS snapshots with a record time after the given time.
    *
    * Note: ACS snapshots are managed by [[AcsSnapshotStore]] which is part of the scan app
    * and depends on this store. In theory this method should be implemented there.
    *
    * However, due to foreign key constraints, we need to delete acs snapshots before we can delete
    * updates referenced by the snapshots, and this store deletes updates as part of its initialization.
    * To avoid orchestrating store initialization, we simply implement this method here.
    * This works because all apps use the same database schema.
    */
  def deleteAcsSnapshotsAfter(
      historyId: Long,
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(
      s"Deleting ACS snapshots for history $historyId with migration $migrationId and recordTime > $recordTime"
    )
    val deleteAction = for {
      dataToDelete <-
        sql"""
          delete from acs_snapshot
          where history_id = $historyId and migration_id = $migrationId and snapshot_record_time > $recordTime
          returning first_row_id, last_row_id
        """.asUpdateReturning[(Long, Long)]
      expectedDataRows = dataToDelete.foldLeft(0L)((total, r) => total + (r._2 - r._1 + 1))
      _ = logger.info(
        s"Deleted ${dataToDelete.size} rows from acs_snapshot, expecting to delete $expectedDataRows rows from acs_snapshot_data"
      )
      deletedDataRows <- DBIO.traverse(dataToDelete) { case (first_row, last_row) =>
        sqlu"""
          delete from acs_snapshot_data
          where row_id between $first_row and $last_row
        """
      }
      _ = logger.info(
        s"Deleted ${deletedDataRows.sum} rows from acs_snapshot_data"
      )
    } yield ()

    storage
      .queryAndUpdate(
        deleteAction.transactionally,
        "deleteAcsSnapshotsAfter",
      )
  }

  def verifyNoRolledBackAcsSnapshots(
      historyId: Long,
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    val action = sql"""
          select snapshot_record_time from acs_snapshot
          where history_id = $historyId and migration_id = $migrationId and snapshot_record_time > $recordTime
        """
      .as[CantonTimestamp]
      .map(times =>
        if (times.length > 0) {
          throw new IllegalStateException(
            s"Found acs snapshots at $times for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime, " +
              "but the configuration says the domain was paused during the migration. " +
              "Check the domain migration configuration and the content of the update history database"
          )
        } else {
          logger.debug(
            s"No updates found for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime"
          )
        }
      )

    storage
      .query(
        action,
        "verifyNoRolledBackAcsSnapshots",
      )
  }

  /** Deletes all updates on the given domain with a record time before the given time.
    */
  def deleteUpdatesBefore(
      synchronizerId: SynchronizerId,
      migrationId: Long,
      recordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(
      s"Deleting updates before $recordTime on domain $synchronizerId from store $storeName with id $historyId"
    )
    val filterCondition = sql"""
      domain_id = $synchronizerId and
      migration_id = $migrationId and
      history_id = $historyId and
      record_time < $recordTime"""

    val deleteAction = for {
      numCreates <- (
        sql"delete from update_history_creates where " ++ filterCondition
      ).toActionBuilder.asUpdate
      numExercises <- (
        sql"delete from update_history_exercises where " ++ filterCondition
      ).toActionBuilder.asUpdate
      numTransactions <- (
        sql"delete from update_history_transactions where " ++ filterCondition
      ).toActionBuilder.asUpdate
      numAssignments <- (
        sql"delete from update_history_assignments where " ++ filterCondition
      ).toActionBuilder.asUpdate
      numUnassignments <- (
        sql"delete from update_history_unassignments where " ++ filterCondition
      ).toActionBuilder.asUpdate
    } yield (numCreates, numExercises, numTransactions, numAssignments, numUnassignments)

    for {
      (numCreates, numExercises, numTransactions, numAssignments, numUnassignments) <- storage
        .update(
          deleteAction.transactionally,
          "deleteUpdatesForTable",
        )
    } yield (
      logger.info(
        s"Deleted $numCreates creates, $numExercises exercises, $numTransactions transactions, $numAssignments assignments, " +
          s"and $numUnassignments unassignments from store $storeName with id $historyId"
      )
    )
  }

  private def afterFilters(
      afterO: Option[(Long, CantonTimestamp)],
      includeImportUpdates: Boolean,
  ): NonEmptyList[SQLActionBuilder] = {
    val gtMin = if (includeImportUpdates) ">=" else ">"
    afterO match {
      case None =>
        NonEmptyList.of(sql"migration_id >= 0 and record_time #$gtMin ${CantonTimestamp.MinValue}")
      case Some((afterMigrationId, afterRecordTime)) =>
        // This makes it so that the two queries use updt_hist_tran_hi_mi_rt_di,
        NonEmptyList.of(
          sql"migration_id = ${afterMigrationId} and record_time > ${afterRecordTime} ",
          sql"migration_id > ${afterMigrationId} and record_time #$gtMin ${CantonTimestamp.MinValue}",
        )
    }
  }

  private def beforeFilters(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      beforeRecordTime: CantonTimestamp,
      atOrAfterRecordTimeO: Option[CantonTimestamp],
  ): NonEmptyList[SQLActionBuilder] = {
    atOrAfterRecordTimeO match {
      case None =>
        NonEmptyList.of(
          // Uses `> CantonTimestamp.MinValue` to exclude import updates
          sql"""migration_id = $migrationId and
                domain_id = $synchronizerId and
                record_time < $beforeRecordTime and
                record_time > ${CantonTimestamp.MinValue}"""
        )
      case Some(atOrAfterRecordTime) =>
        NonEmptyList.of(
          sql"""migration_id = $migrationId and
                domain_id = $synchronizerId and
                record_time < $beforeRecordTime and
                record_time >= ${atOrAfterRecordTime}
                """
        )
    }

  }

  private def updatesQuery(
      filters: NonEmptyList[SQLActionBuilder],
      orderBy: SQLActionBuilder,
      limit: Limit,
      makeSubQuery: SQLActionBuilder => SQLActionBuilderChain,
  ) = {
    if (filters.size == 1) {
      makeSubQuery(filters.head)
    } else {
      // Using an OR in a query might cause the query planner to do a Seq scan,
      // whereas using a union all makes it so that the individual queries use the right index,
      // and are merged via Merge Append.
      val unionAll = filters.map(makeSubQuery).reduceLeft(_ ++ sql" union all " ++ _)

      sql"select * from (" ++ unionAll ++ sql") all_queries " ++
        sql"order by " ++ orderBy ++ sql" limit ${limit.limit}"
    }
  }

  private def getTxUpdates(
      filters: NonEmptyList[SQLActionBuilder],
      orderBy: SQLActionBuilder,
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {
    def makeSubQuery(afterFilter: SQLActionBuilder): SQLActionBuilderChain = {
      sql"""
      (select
        row_id,
        update_id,
        record_time,
        participant_offset,
        domain_id,
        migration_id,
        effective_at,
        root_event_ids,
        workflow_id,
        command_id
      from update_history_transactions
      where
        history_id = $historyId and """ ++ afterFilter ++
        sql" order by " ++ orderBy ++ sql" limit ${limit.limit})"
    }

    val finalQuery = updatesQuery(filters, orderBy, limit, makeSubQuery)
    for {
      rows <- storage
        .query(
          finalQuery.toActionBuilder.as[SelectFromTransactions],
          "getTxUpdates",
        )
      creates <- queryCreateEvents(rows.map(_.rowId))
      exercises <- queryExerciseEvents(rows.map(_.rowId))
    } yield {
      rows.map { row =>
        TreeUpdateWithMigrationId(
          decodeTransaction(
            row,
            creates.getOrElse(row.rowId, Seq.empty),
            exercises.getOrElse(row.rowId, Seq.empty),
          ),
          row.migrationId,
        )
      }
    }
  }

  private def getAssignmentUpdates(
      filters: NonEmptyList[SQLActionBuilder],
      orderBy: SQLActionBuilder,
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {

    def makeSubQuery(afterFilter: SQLActionBuilder): SQLActionBuilderChain = {
      sql"""
    (select
      update_id,
      record_time,
      participant_offset,
      domain_id,
      migration_id,
      reassignment_counter,
      source_domain,
      reassignment_id,
      submitter,
      contract_id,
      event_id,
      created_at,
      template_id_package_id,
      template_id_module_name,
      template_id_entity_name,
      package_name,
      create_arguments,
      signatories,
      observers,
      contract_key
    from update_history_assignments
    where
      history_id = $historyId and """ ++ afterFilter ++
        sql" order by " ++ orderBy ++ sql" limit ${limit.limit})"
    }

    val finalQuery = updatesQuery(filters, orderBy, limit, makeSubQuery)
    for {
      rows <- storage
        .query(
          finalQuery.toActionBuilder.as[SelectFromAssignments],
          "getAssignmentUpdates",
        )
    } yield {
      rows.map { row => TreeUpdateWithMigrationId(decodeAssignment(row), row.migrationId) }
    }
  }

  private def getUnassignmentUpdates(
      filters: NonEmptyList[SQLActionBuilder],
      orderBy: SQLActionBuilder,
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {

    def makeSubQuery(afterFilter: SQLActionBuilder): SQLActionBuilderChain = {
      sql"""
    (select
      update_id,
      record_time,
      participant_offset,
      domain_id,
      migration_id,
      reassignment_counter,
      target_domain,
      reassignment_id,
      submitter,
      contract_id
    from update_history_unassignments
    where
      history_id = $historyId and """ ++ afterFilter ++
        sql" order by " ++ orderBy ++ sql" limit ${limit.limit})"
    }

    val finalQuery = updatesQuery(filters, orderBy, limit, makeSubQuery)
    for {
      rows <- storage
        .query(
          finalQuery.toActionBuilder.as[SelectFromUnassignments],
          "getUnassignmentUpdates",
        )
    } yield {
      rows.map { row => TreeUpdateWithMigrationId(decodeUnassignment(row), row.migrationId) }
    }
  }

  def getUpdatesWithoutImportUpdates(
      afterO: Option[(Long, CantonTimestamp)],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {
    val filters = afterFilters(afterO, includeImportUpdates = false)
    val orderBy = sql"migration_id, record_time, domain_id"
    for {
      txs <- getTxUpdates(filters, orderBy, limit)
      assignments <- getAssignmentUpdates(filters, orderBy, limit)
      unassignments <- getUnassignmentUpdates(filters, orderBy, limit)
    } yield {
      (txs ++ assignments ++ unassignments).sorted.take(limit.limit)
    }
  }

  def getLowestMigrationForRecordTime(
      recordTime: CantonTimestamp
  )(implicit tc: TraceContext): Future[Option[Long]] = {
    // Including migration >= 0 to make sure it hits the indices
    val filter = NonEmptyList.of(sql"migration_id >= 0 and record_time > ${recordTime}")
    val orderBy = sql"migration_id, record_time, domain_id"
    val limit = HardLimit.tryCreate(1)
    for {
      txs <- getTxUpdates(filter, orderBy, limit)
      assignments <- getAssignmentUpdates(filter, orderBy, limit)
      unassignments <- getUnassignmentUpdates(filter, orderBy, limit)
    } yield {
      (txs ++ assignments ++ unassignments).sorted.headOption.map(_.migrationId)
    }
  }

  def getAllUpdates(
      afterO: Option[(Long, CantonTimestamp)],
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {
    val filters = afterFilters(afterO, includeImportUpdates = true)
    // With import updates, we have to include the update id to get a deterministic order.
    // We don't have an index for this order, but this is only used in test code and deprecated scan endpoints.
    val orderBy = sql"migration_id, record_time, domain_id, update_id"
    for {
      txs <- getTxUpdates(filters, orderBy, limit)
      assignments <- getAssignmentUpdates(filters, orderBy, limit)
      unassignments <- getUnassignmentUpdates(filters, orderBy, limit)
    } yield {
      (txs ++ assignments ++ unassignments).sorted.take(limit.limit)
    }
  }

  def getUpdatesBefore(
      migrationId: Long,
      synchronizerId: SynchronizerId,
      beforeRecordTime: CantonTimestamp,
      atOrAfterRecordTime: Option[CantonTimestamp],
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {
    val filters = beforeFilters(migrationId, synchronizerId, beforeRecordTime, atOrAfterRecordTime)
    val orderBy = sql"record_time desc"
    for {
      txs <- getTxUpdates(filters, orderBy, limit)
      assignments <- getAssignmentUpdates(filters, orderBy, limit)
      unassignments <- getUnassignmentUpdates(filters, orderBy, limit)
    } yield {
      (txs ++ assignments ++ unassignments).sorted.reverse.take(limit.limit)
    }
  }

  /** Returns paginated import updates for the given migration id.
    *
    * Note: we store original import updates in the database (as we receive them from the ledger API),
    * but we want this method to return updates that are consistent across SVs.
    * Original import updates have an update id that differs across SVs,
    * and we don't want to rely on the fact that each import update has exactly one create event.
    *
    * Therefore, we rewrite the import updates such that:
    * - Each import update has exactly one create event
    * - The update id is generated from the contract id
    *
    * Note: HttpScanHandler rewrites event ids in order to make them consistent across SVs.
    * For import updates, we need to implement the rewrite here, as it needs to implemented
    * in the database query.
    */
  def getImportUpdates(
      migrationId: Long,
      afterUpdateId: String,
      limit: PageLimit,
  )(implicit tc: TraceContext): Future[Seq[TreeUpdateWithMigrationId]] = {
    val query =
      sql"""
        select
          tx.record_time,
          tx.participant_offset,
          tx.domain_id,
          tx.migration_id,
          tx.effective_at,
          tx.workflow_id,
          tx.command_id,
          c.event_id,
          c.contract_id,
          c.created_at,
          c.template_id_package_id,
          c.template_id_module_name,
          c.template_id_entity_name,
          c.package_name,
          c.create_arguments,
          c.signatories,
          c.observers,
          c.contract_key
        from
          update_history_creates c,
          update_history_transactions tx
        where
          c.history_id = $historyId and
          c.migration_id = $migrationId and
          c.record_time = ${CantonTimestamp.MinValue} and
          c.contract_id > $afterUpdateId and
          c.update_row_id = tx.row_id
        order by c.contract_id asc
        limit ${limit.limit}
         """
    for {
      rows <- storage
        .query(
          query.toActionBuilder.as[SelectFromImportUpdates],
          "getImportUpdates",
        )
    } yield {
      rows.map { row =>
        TreeUpdateWithMigrationId(
          decodeImportTransaction(
            row
          ),
          row.migrationId,
        )
      }
    }
  }

  def getUpdate(
      updateId: String
  )(implicit tc: TraceContext): Future[Option[TreeUpdateWithMigrationId]] = {
    val safeUpdateId = lengthLimited(updateId)
    val query =
      sql"""
      select
        row_id,
        update_id,
        record_time,
        participant_offset,
        domain_id,
        migration_id,
        effective_at,
        root_event_ids,
        workflow_id,
        command_id
      from  update_history_transactions
      where update_id = $safeUpdateId
      and history_id = $historyId
        """

    for {
      rows <- storage
        .query(
          query.toActionBuilder.as[SelectFromTransactions],
          "getUpdate",
        )
      creates <- queryCreateEvents(rows.map(_.rowId))
      exercises <- queryExerciseEvents(rows.map(_.rowId))
    } yield {
      rows.map { row =>
        TreeUpdateWithMigrationId(
          decodeTransaction(
            row,
            creates.getOrElse(row.rowId, Seq.empty),
            exercises.getOrElse(row.rowId, Seq.empty),
          ),
          row.migrationId,
        )
      }.headOption
    }
  }

  private def queryCreateEvents(
      transactionRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, Seq[SelectFromCreateEvents]]] = {
    if (transactionRowIds.isEmpty) {
      Future.successful(Map.empty)
    } else {
      storage
        .query(
          (sql"""
      select
        update_row_id,
        event_id,
        contract_id,
        created_at,
        template_id_package_id,
        template_id_module_name,
        template_id_entity_name,
        package_name,
        create_arguments,
        signatories,
        observers,
        contract_key

      from update_history_creates
      where """ ++ inClause("update_row_id", transactionRowIds)).toActionBuilder
            .as[SelectFromCreateEvents],
          "queryCreateEvents",
        )
        .map(_.groupBy(_.updateRowId))
    }
  }

  def lookupContractById[TCId <: ContractId[?], T <: DamlRecord[?]](
      companion: Contract.Companion.Template[TCId, T]
  )(contractId: TCId)(implicit tc: TraceContext): Future[Option[Contract[TCId, T]]] = {
    for {
      // Annoyingly our index for contract id lookups does not include the history id.
      // In production, we only ever have one history id per database but at least in tests
      // postgres sometimes picks an index to filter by history_id and does a linear search over contract_id.
      // The materialized CTE forces it to pick the contract_id index.
      r <- storage
        .querySingle(
          sql"""
            with unfiltered_contracts as materialized (select
              update_row_id,
              event_id,
              contract_id,
              created_at,
              template_id_package_id,
              template_id_module_name,
              template_id_entity_name,
              package_name,
              create_arguments,
              signatories,
              observers,
              contract_key,
              history_id
            from update_history_creates
            where contract_id = $contractId)
            select * from unfiltered_contracts where history_id = $historyId""".toActionBuilder
            .as[SelectFromCreateEvents]
            .headOption,
          "lookupContractById",
        )
        .value
        .map(_.map(_.toContract(companion)))
    } yield r
  }

  private def queryExerciseEvents(
      transactionRowIds: Seq[Long]
  )(implicit tc: TraceContext): Future[Map[Long, Seq[SelectFromExerciseEvents]]] = {
    if (transactionRowIds.isEmpty) {
      Future.successful(Map.empty)
    } else {
      storage
        .query(
          (sql"""
      select
        update_row_id,
        event_id,
        child_event_ids,
        choice,
        template_id_package_id,
        template_id_module_name,
        template_id_entity_name,
        contract_id,
        consuming,
        package_name,
        argument,
        result,
        acting_parties,
        interface_id_package_id,
        interface_id_module_name,
        interface_id_entity_name
      from update_history_exercises
      where """ ++ inClause("update_row_id", transactionRowIds)).toActionBuilder
            .as[SelectFromExerciseEvents],
          "queryExerciseEvents",
        )
        .map(_.groupBy(_.updateRowId))
    }
  }

  /** Decodes the result of fetching one import contract ([[SelectFromImportUpdates]]) into
    * an artificial update ([[UpdateHistoryResponse]]) with exactly one create event.
    *
    * The result of this method should be consistent and stable. Values of fields that could
    * differ across SVs or across Canton versions are rewritten using determinisitic values.
    *
    * The deterministic values chosen here will be persisted in the UpdateHistory database
    * of late-joining SVs when they backfill import updates.
    * If you change any of the deterministic values here, it will lead to BFT consistency
    * warnings until all SV nodes have deployed the new version.
    */
  private def decodeImportTransaction(
      updateRow: SelectFromImportUpdates
  ): UpdateHistoryResponse = {
    // We don't use any prefix so that we can use an index on contract ids when fetching import updates.
    val updateId = updateRow.contractId
    val eventNodeId = 0
    // The prefix needs to be preserved, because we're relying on it to determine whether a given update
    // was an import update.
    val workflowId = s"${IMPORT_ACS_WORKFLOW_ID_PREFIX}-${updateId}"
    // Command id and participant offset are not included in API responses,
    // but we're making them consistent anyway.
    val commandId = ""
    val offset = 0L

    val createEvent = new CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      /*offset = */ 0, // not populated
      /*nodeId = */ eventNodeId,
      /*templateId = */ tid(
        updateRow.templatePackageId,
        updateRow.templateModuleName,
        updateRow.templateEntityName,
      ),
      /* packageName = */ updateRow.packageName,
      /*contractId = */ updateRow.contractId,
      /*arguments = */ ProtobufCodec.deserializeValue(updateRow.createArguments).asRecord().get(),
      /*createdEventBlob = */ ByteString.EMPTY,
      /*interfaceViews = */ java.util.Collections.emptyMap(),
      /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
      /*contractKey = */ updateRow.contractKey.map(ProtobufCodec.deserializeValue).toJava,
      /*signatories = */ updateRow.signatories.getOrElse(missingStringSeq).asJava,
      /*observers = */ updateRow.observers.getOrElse(missingStringSeq).asJava,
      /*createdAt = */ updateRow.createdAt.toInstant,
      /*acsDelta = */ false,
      /*representativePackageId = */ updateRow.templatePackageId,
    )

    UpdateHistoryResponse(
      update = TransactionTreeUpdate(
        new Transaction(
          /*updateId = */ updateId,
          /*commandId = */ commandId,
          /*workflowId = */ workflowId,
          /*effectiveAt = */ updateRow.effectiveAt.toInstant,
          /*events = */ java.util.Collections.singletonList(createEvent),
          /*offset = */ offset,
          /*synchronizerId = */ updateRow.synchronizerId,
          /*traceContext = */ TraceContextOuterClass.TraceContext.getDefaultInstance,
          /*recordTime = */ updateRow.recordTime.toInstant,
          /*externalTransactionHash = */ ByteString.EMPTY, // TODO(#3408): Revisit when ingesting to DB
        )
      ),
      synchronizerId = SynchronizerId.tryFromString(updateRow.synchronizerId),
    )
  }

  private def decodeTransaction(
      updateRow: SelectFromTransactions,
      createRows: Seq[SelectFromCreateEvents],
      exerciseRows: Seq[SelectFromExerciseEvents],
  ): UpdateHistoryResponse = {

    val createEvents = createRows.map(_.toCreatedEvent.event)
    // TODO(#640) - remove this conversion as it's costly
    val nodesWithChildren = exerciseRows
      .map(exercise =>
        EventId.nodeIdFromEventId(exercise.eventId) -> exercise.childEventIds
          .map(EventId.nodeIdFromEventId)
      )
      .toMap
    val exerciseEvents = exerciseRows.map { row =>
      val nodeId = EventId.nodeIdFromEventId(row.eventId)
      new ExercisedEvent(
        /*witnessParties = */ java.util.Collections.emptyList(),
        /*offset = */ 0, // not populated
        /*nodeId = */ nodeId,
        /*templateId = */ tid(
          row.templatePackageId,
          row.templateModuleName,
          row.templateEntityName,
        ),
        /*packageName = */ row.packageName.getOrElse(missingString),
        /*interfaceId = */ tid(
          row.interfacePackageId,
          row.interfaceModuleName,
          row.interfaceEntityName,
        ).toJava,
        /*contractId = */ row.contractId,
        /*choice = */ row.choice,
        /*choiceArgument = */ ProtobufCodec.deserializeValue(row.argument),
        /*actingParties = */ row.actingParties.getOrElse(missingStringSeq).asJava,
        /*consuming = */ row.consuming,
        /*lastDescendedNodeId = */ Integer.valueOf(
          EventId.lastDescendedNodeFromChildNodeIds(nodeId, nodesWithChildren)
        ),
        /*exerciseResult = */ ProtobufCodec.deserializeValue(row.result),
        /*implementedInterfaces = */ java.util.Collections.emptyList(),
        /*acsDelta = */ false,
      )
    }
    val events: Seq[Event] = (createEvents ++ exerciseEvents).sortBy(_.getNodeId)

    UpdateHistoryResponse(
      update = TransactionTreeUpdate(
        new Transaction(
          /*updateId = */ updateRow.updateId,
          /*commandId = */ updateRow.commandId.getOrElse(missingString),
          /*workflowId = */ updateRow.workflowId.getOrElse(missingString),
          /*effectiveAt = */ updateRow.effectiveAt.toInstant,
          /*events = */ events.asJava,
          /*offset = */ LegacyOffset.Api.assertFromStringToLong(updateRow.participantOffset),
          /*synchronizerId = */ updateRow.synchronizerId,
          /*traceContext = */ TraceContextOuterClass.TraceContext.getDefaultInstance,
          /*recordTime = */ updateRow.recordTime.toInstant,
          /*externalTransactionHash = */ ByteString.EMPTY, // TODO(#3408): Revisit when ingesting to DB
        )
      ),
      synchronizerId = SynchronizerId.tryFromString(updateRow.synchronizerId),
    )
  }

  private def decodeAssignment(
      row: SelectFromAssignments
  ): UpdateHistoryResponse = {
    UpdateHistoryResponse(
      ReassignmentUpdate(
        Reassignment[Assign](
          updateId = row.updateId,
          offset = row.participantOffset,
          recordTime = row.recordTime,
          event = Assign(
            submitter = row.submitter,
            source = row.sourceDomain,
            target = row.synchronizerId,
            unassignId = row.reassignmentId,
            createdEvent = new CreatedEvent(
              /*witnessParties = */ java.util.Collections.emptyList(),
              /*offset = */ 0, // not populated
              /*nodeId = */ EventId.nodeIdFromEventId(row.eventId),
              /*templateId = */ tid(
                row.templatePackageId,
                row.templateModuleName,
                row.templateEntityName,
              ),
              /*packageName = */ row.packageName,
              /*contractId = */ row.contractId,
              /*arguments = */ ProtobufCodec.deserializeValue(row.createArguments).asRecord().get(),
              /*createdEventBlob = */ ByteString.EMPTY,
              /*interfaceViews = */ java.util.Collections.emptyMap(),
              /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
              /*contractKey = */ java.util.Optional.empty(),
              /*signatories = */ row.signatories.getOrElse(missingStringSeq).asJava,
              /*observers = */ row.observers.getOrElse(missingStringSeq).asJava,
              /*createdAt = */ row.createdAt.toInstant,
              /*acsDelta = */ false,
              /*representativePackageId = */ row.templatePackageId,
            ),
            counter = row.reassignmentCounter,
          ),
        )
      ),
      row.synchronizerId,
    )
  }

  private def decodeUnassignment(
      row: SelectFromUnassignments
  ): UpdateHistoryResponse = {
    UpdateHistoryResponse(
      ReassignmentUpdate(
        Reassignment[Unassign](
          updateId = row.updateId,
          offset = row.participantOffset,
          recordTime = row.recordTime,
          event = Unassign(
            submitter = row.submitter,
            source = row.synchronizerId,
            target = row.targetDomain,
            unassignId = row.reassignmentId,
            counter = row.reassignmentCounter,
            contractId = new ContractId(row.contractId),
          ),
        )
      ),
      row.synchronizerId,
    )
  }

  private implicit lazy val GetResultSelectFromTransactions: GetResult[SelectFromTransactions] =
    GetResult { prs =>
      import prs.*
      (SelectFromTransactions.apply _).tupled(
        (
          <<[Long],
          <<[String],
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[Long],
          <<[CantonTimestamp],
          <<[Seq[String]],
          <<[Option[String]],
          <<[Option[String]],
        )
      )
    }

  private implicit lazy val GetResultSelectFromExerciseEvents: GetResult[SelectFromExerciseEvents] =
    GetResult { prs =>
      import prs.*
      (SelectFromExerciseEvents.apply _).tupled(
        (
          <<[Long],
          <<[String],
          <<[Seq[String]],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Boolean],
          <<[Option[String]],
          <<[String],
          <<[String],
          <<[Option[Seq[String]]],
          <<[Option[String]],
          <<[Option[String]],
          <<[Option[String]],
        )
      )
    }

  private implicit lazy val GetResultSelectFromAssignments: GetResult[SelectFromAssignments] =
    GetResult { prs =>
      import prs.*
      (SelectFromAssignments.apply _).tupled(
        (
          <<[String],
          <<[CantonTimestamp],
          LegacyOffset.Api.assertFromStringToLong(<<[String]),
          <<[SynchronizerId],
          <<[Long],
          <<[Long],
          <<[SynchronizerId],
          <<[String],
          <<[PartyId],
          <<[String],
          <<[String],
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Option[Seq[String]]],
          <<[Option[Seq[String]]],
          <<[Option[String]],
        )
      )
    }

  private implicit lazy val GetResultSelectFromUnassignments: GetResult[SelectFromUnassignments] =
    GetResult { prs =>
      import prs.*
      (SelectFromUnassignments.apply _).tupled(
        (
          <<[String],
          <<[CantonTimestamp],
          LegacyOffset.Api.assertFromStringToLong(<<[String]),
          <<[SynchronizerId],
          <<[Long],
          <<[Long],
          <<[SynchronizerId],
          <<[String],
          <<[PartyId],
          <<[String],
        )
      )
    }

  private implicit lazy val GetResultSelectFromImportUpdates: GetResult[SelectFromImportUpdates] =
    GetResult { prs =>
      import prs.*
      (SelectFromImportUpdates.apply _).tupled(
        (
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[Long],
          <<[CantonTimestamp],
          <<[Option[String]],
          <<[Option[String]],
          <<[String],
          <<[String],
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Option[Seq[String]]],
          <<[Option[Seq[String]]],
          <<[Option[String]],
        )
      )
    }

  /** Returns the record time range of sequenced events excluding ACS imports after a HDM.
    */
  def getRecordTimeRange(
      migrationId: Long
  )(implicit tc: TraceContext): Future[Map[SynchronizerId, DomainRecordTimeRange]] = {
    // This query is rather tricky, there are two parts we need to tackle:
    // 1. get the list of distinct domain ids
    // 2. for each of them get the min and max record time
    // A naive group by does not hit an index for either of them.
    // To get the list of domain ids we simulate a loose index scan as describe in https://wiki.postgresql.org/wiki/Loose_indexscan.
    // We then exploit a lateral join to get the record time range as described in https://www.timescale.com/blog/select-the-most-recent-record-of-many-items-with-postgresql/.
    // This relies on the number of domain ids being reasonably small to perform well which is a valid assumption.
    def range(table: String): Future[Map[SynchronizerId, DomainRecordTimeRange]] = {
      storage
        .query(
          sql"""
            with recursive domains AS (
              select min(domain_id) AS domain_id FROM #$table where history_id = $historyId and migration_id = $migrationId
              union ALL
              select (select min(domain_id) FROM #$table WHERE history_id = $historyId and migration_id = $migrationId and domain_id > domains.domain_id)
              FROM domains where domain_id is not null
            )
            select domain_id, min_record_time, max_record_time
            from domains
            inner join lateral (select min(record_time) as min_record_time, max(record_time) as max_record_time from #$table where history_id = $historyId and migration_id = $migrationId and domain_id = domains.domain_id and record_time > ${CantonTimestamp.MinValue}) time_range
            on true
            where domain_id is not null
           """
            .as[(SynchronizerId, Option[CantonTimestamp], Option[CantonTimestamp])],
          s"getRecordTimeRange.$table",
        )
        .map(row =>
          row.view
            .flatMap(row =>
              for {
                min <- row._2
                max <- row._3
              } yield row._1 -> DomainRecordTimeRange(min, max)
            )
            .toMap
        )
    }

    for {
      rangeTransactions <- range("update_history_transactions")
      rangeAssignments <- range("update_history_assignments")
      rangeUnassignments <- range("update_history_unassignments")
    } yield {
      rangeTransactions |+| rangeUnassignments |+| rangeAssignments
    }
  }

  def getLastImportUpdateId(
      migrationId: Long
  )(implicit tc: TraceContext): Future[Option[String]] = {
    if (enableImportUpdateBackfill) {
      storage.query(
        sql"""
        select
          -- Note: to make update ids consistent across SVs, we use the contract id as the update id.
          max(c.contract_id)
        from
          update_history_creates c
        where
          history_id = $historyId and
          migration_id = $migrationId and
          record_time = ${CantonTimestamp.MinValue}
      """.as[Option[String]].head,
        s"getLastImportUpdateId",
      )
    } else {
      Future.successful(None)
    }
  }

  def getPreviousMigrationId(migrationId: Long)(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    def previousId(table: String) = {
      storage.query(
        // The following is equivalent to:
        //     select max(migration_id)
        //     from #$table
        //     where history_id = $historyId and migration_id < $migrationId
        // but uses a recursive CTE to avoid a backwards-index-scan which attempts to read most of the table.
        sql"""
          WITH RECURSIVE t AS (
             (SELECT migration_id FROM #$table where history_id = $historyId and migration_id < $migrationId ORDER BY migration_id LIMIT 1)
             UNION ALL
             SELECT (SELECT migration_id FROM #$table WHERE history_id = $historyId and migration_id < $migrationId and migration_id > t.migration_id ORDER BY migration_id LIMIT 1)
             FROM t
             WHERE t.migration_id IS NOT NULL
             )
          SELECT MAX(migration_id) FROM t WHERE migration_id IS NOT NULL;
           """.as[Option[Long]].head,
        s"getPreviousMigrationId.$table",
      )
    }

    for {
      transactions <- previousId("update_history_transactions")
      assignments <- previousId("update_history_assignments")
      unassignments <- previousId("update_history_unassignments")
    } yield {
      List(
        transactions,
        assignments,
        unassignments,
      ).flatten.maxOption
    }
  }

  private[this] def getFirstMigrationId(historyId: Long)(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    def previousId(table: String) = {
      storage.query(
        sql"""
             select min(migration_id)
             from #$table
             where history_id = $historyId
           """.as[Option[Long]].head,
        s"getFirstMigrationId.$table",
      )
    }

    for {
      transactions <- previousId("update_history_transactions")
      assignments <- previousId("update_history_assignments")
      unassignments <- previousId("update_history_unassignments")
    } yield {
      List(
        transactions,
        assignments,
        unassignments,
      ).flatten.minOption
    }
  }

  /** Returns the migration id at which the import update backfilling should start */
  private[this] def getImportUpdateBackfillingMigrationId()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    for {
      importUpdateBackfillingComplete <- storage
        .query(
          sql"""
          select import_updates_complete
          from update_history_backfilling
          where history_id = $historyId
        """.as[Boolean].head,
          "getImportUpdateBackfillingMigrationId.1",
        )
      (firstMigration, lastMigration) <- storage.query(
        sql"""
             select min(migration_id), max(migration_id)
             from update_history_transactions
             where history_id = $historyId
           """.as[(Option[Long], Option[Long])].head,
        s"getImportUpdateBackfillingMigrationId.2",
      )
      firstMigrationWithImportUpdates <- storage.query(
        sql"""
             select min(migration_id)
             from update_history_transactions
             where history_id = $historyId
              and record_time = ${CantonTimestamp.MinValue}
           """.as[Option[Long]].head,
        s"getImportUpdateBackfillingMigrationId.3",
      )
    } yield {
      if (importUpdateBackfillingComplete) {
        // Import updates backfilling complete, we know everything about import updates up to the very first migration.
        firstMigration
      } else {
        // Import updates backfilling not complete yet, return the first migration that has any import updates,
        // or, if there are no import updates whatsoever, the last migration.
        if (firstMigrationWithImportUpdates.isDefined) {
          firstMigrationWithImportUpdates
        } else {
          lastMigration
        }
      }
    }
  }

  def getBackfillingState()(implicit
      tc: TraceContext
  ): Future[BackfillingState] =
    getBackfillingStateForHistory(historyId)

  private[this] def getBackfillingStateForHistory(historyId: Long)(implicit
      tc: TraceContext
  ): Future[BackfillingState] = {
    backfillingRequired match {
      case BackfillingRequirement.BackfillingNotRequired =>
        Future.successful(BackfillingState.Complete)
      case BackfillingRequirement.NeedsBackfilling =>
        storage
          .query(
            sql"""
          select complete, import_updates_complete
          from update_history_backfilling
          where history_id = $historyId
        """.as[(Boolean, Boolean)].headOption,
            "getBackfillingStateForHistory",
          )
          .map {
            case Some((updatesComplete, importUpdatesComplete)) =>
              if (updatesComplete && importUpdatesComplete) {
                BackfillingState.Complete
              } else if (updatesComplete && !enableImportUpdateBackfill) {
                // If import update backfilling is disabled, behave as if it was not implemented
                BackfillingState.Complete
              } else {
                BackfillingState.InProgress(
                  updatesComplete = updatesComplete,
                  importUpdatesComplete = importUpdatesComplete,
                )
              }
            case None => BackfillingState.NotInitialized
          }
    }
  }

  private[this] def setBackfillingComplete()(implicit
      tc: TraceContext
  ): Future[Unit] = {
    assert(backfillingRequired == BackfillingRequirement.NeedsBackfilling)
    storage
      .update(
        sqlu"""
          update update_history_backfilling
          set complete = true
          where history_id = $historyId
        """,
        "setBackfillingComplete",
      )
      .map(_ => ())
  }

  private[this] def setBackfillingImportUpdatesComplete()(implicit
      tc: TraceContext
  ): Future[Unit] =
    storage
      .update(
        sqlu"""
          update update_history_backfilling
          set import_updates_complete = true
          where history_id = $historyId
        """,
        "setBackfillingImportUpdatesComplete",
      )
      .map(_ => ())

  def initializeBackfilling(
      joiningMigrationId: Long,
      joiningSynchronizerId: SynchronizerId,
      joiningUpdateId: String,
      complete: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    assert(backfillingRequired == BackfillingRequirement.NeedsBackfilling)
    logger.info(
      s"Initializing backfilling for history $historyId with joiningMigrationId=$joiningMigrationId, joiningSynchronizerId=$joiningSynchronizerId, joiningUpdateId=$joiningUpdateId, and complete=$complete"
    )
    val safeUpdateId = lengthLimited(joiningUpdateId)
    storage
      .update(
        sqlu"""
          insert into update_history_backfilling (history_id, joining_migration_id, joining_domain_id, joining_update_id, complete, import_updates_complete)
          values ($historyId, $joiningMigrationId, $joiningSynchronizerId, $safeUpdateId, $complete, $complete)
          on conflict (history_id) do update set
            joining_migration_id = $joiningMigrationId,
            joining_domain_id = $joiningSynchronizerId,
            joining_update_id = $safeUpdateId,
            complete = $complete,
            import_updates_complete = $complete
        """,
        "initializeBackfilling",
      )
      .map(_ => ())
  }

  private def readOffsetAction(): DBIOAction[Option[Long], NoStream, Effect.Read] =
    sql"""
        select last_ingested_offset
        from update_history_last_ingested_offsets
        where history_id = $historyId and migration_id = $domainMigrationId
      """
      .as[Option[String]]
      .head
      .map(_.map(LegacyOffset.Api.assertFromStringToLong))

  /** Testing API: lookup last ingested offset */
  private[store] def lookupLastIngestedOffset()(implicit tc: TraceContext): Future[Option[Long]] = {
    storage.query(readOffsetAction(), "readOffset")
  }

  lazy val sourceHistory: HistoryBackfilling.SourceHistory[UpdateHistoryResponse] =
    new HistoryBackfilling.SourceHistory[UpdateHistoryResponse] {
      override def isReady: Boolean = state
        .get()
        .historyId
        .isDefined

      override def migrationInfo(
          migrationId: Long
      )(implicit tc: TraceContext): Future[Option[SourceMigrationInfo]] = for {
        // Note: As the following queries are not wrapped in a REPEATABLE_READ transaction,
        // the individual results do not form a consistent snapshot of the migration metadata.
        // This is fine because update history is append-only,
        // but we have to make sure to query the state first to avoid returning record time ranges
        // from before the update history was initialized.
        state <- getBackfillingState()
        previousMigrationId <- getPreviousMigrationId(migrationId)
        recordTimeRange <- getRecordTimeRange(migrationId)
        lastImportUpdateId <- getLastImportUpdateId(migrationId)
      } yield {
        state match {
          case BackfillingState.NotInitialized =>
            None
          case BackfillingState.Complete =>
            Option.when(recordTimeRange.nonEmpty)(
              SourceMigrationInfo(
                previousMigrationId = previousMigrationId,
                recordTimeRange = recordTimeRange,
                lastImportUpdateId = lastImportUpdateId,
                complete = true,
                importUpdatesComplete = true,
              )
            )
          case BackfillingState.InProgress(updatesComplete, importUpdatesComplete) =>
            Option.when(recordTimeRange.nonEmpty)(
              SourceMigrationInfo(
                previousMigrationId = previousMigrationId,
                recordTimeRange = recordTimeRange,
                lastImportUpdateId = lastImportUpdateId,
                // Note: this will only report this migration as "complete" if the backfilling process has completed for
                // all migration ids (`state` contains global information, across all migrations).
                // This is not wrong, but we could also report this migration as complete if there exists any data on
                // the previous migration.
                complete = updatesComplete,
                importUpdatesComplete = importUpdatesComplete,
              )
            )
        }
      }

      override def items(
          migrationId: Long,
          synchronizerId: SynchronizerId,
          before: CantonTimestamp,
          count: Int,
      )(implicit tc: TraceContext): Future[Seq[UpdateHistoryResponse]] = {
        getUpdatesBefore(
          migrationId = migrationId,
          synchronizerId = synchronizerId,
          beforeRecordTime = before,
          atOrAfterRecordTime = None,
          limit = PageLimit.tryCreate(count),
        ).map(_.map(_.update))
      }
    }

  class DestinationHistoryImplementation
      extends HistoryBackfilling.DestinationHistory[UpdateHistoryResponse]
      with ImportUpdatesBackfilling.DestinationImportUpdates[UpdateHistoryResponse] {

    override def isReady = state
      .get()
      .historyId
      .isDefined

    override def backfillingInfo(implicit
        tc: TraceContext
    ): Future[Option[DestinationBackfillingInfo]] = (for {
      state <- OptionT.liftF(getBackfillingState())
      if state != BackfillingState.NotInitialized
      migrationId <- OptionT(getFirstMigrationId(historyId))
      recordTimeRange <- OptionT.liftF(getRecordTimeRange(migrationId))
    } yield DestinationBackfillingInfo(
      migrationId = migrationId,
      backfilledAt = recordTimeRange.view.mapValues(_.min).toMap,
    )).value

    override def importUpdatesBackfillingInfo(implicit
        tc: TraceContext
    ): Future[Option[DestinationImportUpdatesBackfillingInfo]] = (for {
      state <- OptionT.liftF(getBackfillingState())
      if state != BackfillingState.NotInitialized
      migrationId <- OptionT(getImportUpdateBackfillingMigrationId())
      lastUpdateId <- OptionT.liftF(getLastImportUpdateId(migrationId))
    } yield DestinationImportUpdatesBackfillingInfo(
      migrationId = migrationId,
      lastUpdateId = lastUpdateId,
    )).value

    override def insert(
        migrationId: Long,
        synchronizerId: SynchronizerId,
        items: Seq[UpdateHistoryResponse],
    )(implicit
        tc: TraceContext
    ): Future[DestinationHistory.InsertResult] = {
      insertItems(migrationId, items).map { insertedItems =>
        val ingestedEvents = IngestedEvents.eventCount(insertedItems.map(_.update).collect {
          case TransactionTreeUpdate(tree) => tree
        })
        DestinationHistory.InsertResult(
          backfilledUpdates = insertedItems.size.toLong,
          backfilledExercisedEvents = ingestedEvents.numExercisedEvents,
          backfilledCreatedEvents = ingestedEvents.numCreatedEvents,
          lastBackfilledRecordTime = insertedItems.last.update.recordTime,
        )
      }
    }

    override def insertImportUpdates(
        migrationId: Long,
        items: Seq[UpdateHistoryResponse],
    )(implicit
        tc: TraceContext
    ): Future[DestinationImportUpdates.InsertResult] = {
      insertItems(migrationId, items).map(insertedItems =>
        DestinationImportUpdates.InsertResult(
          migrationId = migrationId,
          backfilledContracts = insertedItems.size.toLong,
        )
      )
    }

    private def insertItems(
        migrationId: Long,
        items: Seq[UpdateHistoryResponse],
    )(implicit
        tc: TraceContext
    ): Future[NonEmptyList[UpdateHistoryResponse]] = {
      assert(backfillingRequired == BackfillingRequirement.NeedsBackfilling)
      val nonEmpty = NonEmptyList
        .fromFoldable(items)
        .getOrElse(
          throw new RuntimeException("insert() must not be called with an empty sequence")
        )
      // Because DbStorage requires all actions to be idempotent, and we can't just slap a "ON CONFLICT DO NOTHING"
      // onto all subqueries of ingestUpdate_() because they are using "RETURNING" which doesn't work with the above,
      // we simply check whether one of the items was already inserted.
      val (headItemTable, headItemRecordTime, headItemSynchronizerId, headItemUpdateId) =
        nonEmpty.head.update match {
          case TransactionTreeUpdate(tree) =>
            (
              "update_history_transactions",
              CantonTimestamp.assertFromInstant(tree.getRecordTime),
              SynchronizerId.tryFromString(tree.getSynchronizerId),
              tree.getUpdateId,
            )
          case ReassignmentUpdate(update) =>
            update.event match {
              case _: ReassignmentEvent.Assign =>
                (
                  "update_history_assignments",
                  update.recordTime,
                  update.event.target,
                  update.updateId,
                )
              case _: ReassignmentEvent.Unassign =>
                (
                  "update_history_unassignments",
                  update.recordTime,
                  update.event.source,
                  update.updateId,
                )
            }
        }

      val action = for {
        itemExists <-
          sql"""
             select exists(
               select row_id
               from #$headItemTable
               where
                 history_id = $historyId and
                 migration_id = $migrationId and
                 domain_id = $headItemSynchronizerId and
                 record_time = $headItemRecordTime and
                 update_id = $headItemUpdateId
             )
           """.as[Boolean].head
        _ <-
          if (!itemExists) {
            DBIOAction
              .sequence(items.map { item =>
                ingestUpdate_(item.update, migrationId)
              })
          } else {
            DBIOAction.successful(())
          }
      } yield nonEmpty

      storage
        .queryAndUpdate(
          action.transactionally,
          "destinationHistory.insert",
        )
        .map { nonEmpty =>
          nonEmpty
        }
    }

    override def markBackfillingComplete()(implicit
        tc: TraceContext
    ): Future[Unit] = setBackfillingComplete()

    override def markImportUpdatesBackfillingComplete()(implicit tc: TraceContext): Future[Unit] =
      setBackfillingImportUpdatesComplete()
  }

  lazy val destinationHistory: HistoryBackfilling.DestinationHistory[
    UpdateHistoryResponse
  ] & ImportUpdatesBackfilling.DestinationImportUpdates[UpdateHistoryResponse] =
    new DestinationHistoryImplementation()
}

object UpdateHistory {

  // Separate method so we can use this without a full UpdateHistory instance.
  // Since we're interested in the highest known migration id, we don't need to filter by anything
  // (store ID, participant ID, etc. are not even known at the time we want to call this).
  def getHighestKnownMigrationId(
      storage: DbStorage
  )(implicit
      ec: ExecutionContext,
      closeContext: CloseContext,
      tc: TraceContext,
  ): Future[Option[Long]] = {
    for {
      queryResult <- storage.query(
        sql"""
               select max(migration_id) from update_history_last_ingested_offsets
            """.as[Option[Long]],
        "getHighestKnownMigrationId",
      )
    } yield {
      queryResult.headOption.flatten
    }
  }

  sealed trait BackfillingRequirement
  object BackfillingRequirement {

    /** This history is guaranteed to have started ingestion early enough
      * such that it didn't miss any update visible to `updateStreamParty`.
      */
    final case object BackfillingNotRequired extends BackfillingRequirement

    /** The ingestion for this history started at a record time, where updates for `updateStreamParty`
      * might already exist. The missing updates at the beginning of the history need to be backfilled,
      * see for example [[ScanHistoryBackfillingTrigger]].
      */
    final case object NeedsBackfilling extends BackfillingRequirement
  }

  final case class UpdateHistoryResponse(
      update: TreeUpdate,
      synchronizerId: SynchronizerId,
  )

  case class State(
      historyId: Option[Long],
      initialized: Promise[Unit],
      corruptSnapshotsDeleted: Boolean,
      lastIngestedRecordTime: Option[CantonTimestamp],
  ) {}

  object State {
    def empty(): State = State(
      historyId = None,
      initialized = Promise[Unit](),
      corruptSnapshotsDeleted = false,
      lastIngestedRecordTime = None,
    )
  }

  sealed trait BackfillingState
  object BackfillingState {
    case object Complete extends BackfillingState
    case class InProgress(updatesComplete: Boolean, importUpdatesComplete: Boolean)
        extends BackfillingState
    case object NotInitialized extends BackfillingState
  }

  private case class SelectFromTransactions(
      rowId: Long,
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: String,
      synchronizerId: String,
      migrationId: Long,
      effectiveAt: CantonTimestamp,
      rootEventIds: Seq[String],
      workflowId: Option[String],
      commandId: Option[String],
  )

  case class SelectFromCreateEvents(
      updateRowId: Long,
      eventId: String,
      contractId: String,
      createdAt: CantonTimestamp,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      packageName: String,
      createArguments: String,
      signatories: Option[Seq[String]],
      observers: Option[Seq[String]],
      contractKey: Option[String],
  ) {

    def toContract[TCId <: ContractId[?], T <: DamlRecord[?]](
        companion: Contract.Companion.Template[TCId, T]
    ): Contract[TCId, T] = {
      Contract
        .fromCreatedEvent(companion)(this.toCreatedEvent.event)
        .getOrElse(
          throw new IllegalStateException(
            s"Stored a contract that cannot be decoded as ${companion.TEMPLATE_ID}: $this"
          )
        )
    }

    def toCreatedEvent: SpliceCreatedEvent = {
      SpliceCreatedEvent(
        eventId,
        new CreatedEvent(
          /*witnessParties = */ java.util.Collections.emptyList(),
          /*offset = */ 0, // not populated
          /*nodeId = */ EventId.nodeIdFromEventId(eventId),
          /*templateId = */ tid(
            templatePackageId,
            templateModuleName,
            templateEntityName,
          ),
          /* packageName = */ packageName,
          /*contractId = */ contractId,
          /*arguments = */ ProtobufCodec.deserializeValue(createArguments).asRecord().get(),
          /*createdEventBlob = */ ByteString.EMPTY,
          /*interfaceViews = */ java.util.Collections.emptyMap(),
          /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
          /*contractKey = */ contractKey.map(ProtobufCodec.deserializeValue).toJava,
          /*signatories = */ signatories.getOrElse(missingStringSeq).asJava,
          /*observers = */ observers.getOrElse(missingStringSeq).asJava,
          /*createdAt = */ createdAt.toInstant,
          /*acsDelta = */ false,
          /*representativePackageId = */ templatePackageId,
        ),
      )
    }
  }

  object SelectFromCreateEvents {
    implicit def GetResultSelectFromCreateEvents(implicit
        optSeqStringGetResult: GetResult[Option[Seq[String]]]
    ): GetResult[SelectFromCreateEvents] =
      GetResult { prs =>
        import prs.*
        (SelectFromCreateEvents.apply _).tupled(
          (
            <<[Long],
            <<[String],
            <<[String],
            <<[CantonTimestamp],
            <<[String],
            <<[String],
            <<[String],
            <<[String],
            <<[String],
            <<[Option[Seq[String]]],
            <<[Option[Seq[String]]],
            <<[Option[String]],
          )
        )
      }
  }

  private case class SelectFromExerciseEvents(
      updateRowId: Long,
      eventId: String,
      childEventIds: Seq[String],
      choice: String,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      contractId: String,
      consuming: Boolean,
      packageName: Option[String],
      argument: String,
      result: String,
      actingParties: Option[Seq[String]],
      interfacePackageId: Option[String],
      interfaceModuleName: Option[String],
      interfaceEntityName: Option[String],
  )

  private case class SelectFromAssignments(
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: Long,
      synchronizerId: SynchronizerId,
      migrationId: Long,
      reassignmentCounter: Long,
      sourceDomain: SynchronizerId,
      reassignmentId: String,
      submitter: PartyId,
      contractId: String,
      eventId: String,
      createdAt: CantonTimestamp,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      packageName: String,
      createArguments: String,
      signatories: Option[Seq[String]],
      observers: Option[Seq[String]],
      contractKey: Option[String],
  )

  private case class SelectFromUnassignments(
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: Long,
      synchronizerId: SynchronizerId,
      migrationId: Long,
      reassignmentCounter: Long,
      targetDomain: SynchronizerId,
      reassignmentId: String,
      submitter: PartyId,
      contractId: String,
  )

  private case class SelectFromImportUpdates(
      recordTime: CantonTimestamp,
      participantOffset: String,
      synchronizerId: String,
      migrationId: Long,
      effectiveAt: CantonTimestamp,
      workflowId: Option[String],
      commandId: Option[String],
      eventId: String,
      contractId: String,
      createdAt: CantonTimestamp,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      packageName: String,
      createArguments: String,
      signatories: Option[Seq[String]],
      observers: Option[Seq[String]],
      contractKey: Option[String],
  )

  private def tid(packageName: String, moduleName: String, entityName: String) =
    new Identifier(packageName, moduleName, entityName)

  private def tid(
      packageNameOpt: Option[String],
      moduleNameOpt: Option[String],
      entityNameOpt: Option[String],
  ): Option[Identifier] = for {
    packageName <- packageNameOpt
    moduleName <- moduleNameOpt
    entityName <- entityNameOpt
  } yield new Identifier(packageName, moduleName, entityName)

  // Some fields were not stored initially in UpdateHistory tables, but were added to the schema before MainNet launch.
  // Missing values for such fields should only exist in databases for clusters that were started before MainNet launch.
  // We don't care much about these missing values and they are non-optional in the Java API classes,
  // so we read them back as an arbitrary value.
  private def missingString: String = ""
  private def missingStringSeq: Seq[String] = Seq.empty
}

final case class TimestampWithMigrationId(
    timestamp: CantonTimestamp,
    migrationId: Long,
)

object TimestampWithMigrationId {
  implicit val ordering: Ordering[TimestampWithMigrationId] =
    Ordering.by(x => (x.migrationId, x.timestamp))
}

final case class TreeUpdateWithMigrationId(
    update: UpdateHistory.UpdateHistoryResponse,
    migrationId: Long,
)

object TreeUpdateWithMigrationId {
  implicit val ordering: Ordering[TreeUpdateWithMigrationId] = Ordering.by(x =>
    (x.migrationId, x.update.update.recordTime, x.update.synchronizerId.toProtoPrimitive)
  )
}
