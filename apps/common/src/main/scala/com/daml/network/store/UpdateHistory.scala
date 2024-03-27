package com.daml.network.store

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  ExercisedEvent,
  Identifier,
  ParticipantOffset,
  TransactionTree,
}
import com.daml.network.environment.ParticipantAdminConnection.IMPORT_ACS_WORKFLOW_ID_PREFIX
import com.daml.network.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  LedgerClient,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
}
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.MultiDomainAcsStore.{HasIngestionSink, IngestionFilter}
import com.daml.network.store.db.AcsJdbcTypes
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.{GetResult, JdbcProfile}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

final class UpdateHistory(
    storage: DbStorage,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    val updateStreamParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    closeContext: CloseContext,
) extends HasIngestionSink
    with AcsJdbcTypes
    with NamedLogging {

  override lazy val profile: JdbcProfile = storage.api.jdbcProfile
  import profile.api.jdbcActionExtensionMethods
  import UpdateHistory.*

  private[this] def domainMigrationId = domainMigrationInfo.currentMigrationId

  private val state = new AtomicReference[State](State.empty())
  private def historyId: Long =
    state
      .get()
      .historyId
      .getOrElse(throw new RuntimeException("Using historyId before it was assigned"))

  lazy val ingestionSink: MultiDomainAcsStore.IngestionSink =
    new MultiDomainAcsStore.IngestionSink {
      override def ingestionFilter: IngestionFilter = IngestionFilter(
        primaryParty = updateStreamParty,
        // Note: the template ids only determine which create events should include data
        // for explicit contract disclosure. We don't store that data in the update history.
        templateIds = Set.empty,
      )

      override def initialize()(implicit traceContext: TraceContext): Future[Option[String]] = {
        logger.info(s"Initializing update history ingestion sink for party $updateStreamParty")

        // Notes:
        // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
        for {
          _ <- storage
            .update(
              sql"""
            insert into update_history_descriptors (party, participant_id)
            values ($updateStreamParty, $participantId)
            on conflict do nothing
           """.asUpdate,
              "initialize.1",
            )

          newHistoryId <- storage
            .querySingle(
              sql"""
             select id
             from update_history_descriptors
             where party = $updateStreamParty and participant_id = $participantId
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

          _ <- cleanUpDataAfterDomainMigration(newHistoryId)
        } yield {
          state.updateAndGet(
            _.copy(
              historyId = Some(newHistoryId)
            )
          )
          lastIngestedOffset match {
            case Some(offset) =>
              logger.info(s"${description()} resumed at offset $offset")
            case None =>
              logger.info(s"${description()} initialized")
          }
          lastIngestedOffset
        }
      }

      /** A description of this update history instance, to be used in log messages */
      private def description() =
        s"UpdateHistory(party=$updateStreamParty, participantId=$participantId, migrationId=$domainMigrationId, historyId=$historyId)"

      override def ingestAcs(
          offset: String,
          acs: Seq[ActiveContract],
          incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
          incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
      )(implicit traceContext: TraceContext): Future[Unit] = {
        if (acs.nonEmpty || incompleteIn.nonEmpty || incompleteOut.nonEmpty) {
          logger.info(
            s"${description()} started from the ACS at offset $offset, " +
              s"but the ACS already contains (acs=${acs.size}, incompleteOut=${incompleteOut.size} incompleteIn=${incompleteIn.size}) elements at that point. " +
              "This is only fine in the following cases:\n" +
              "- This is an SV node that joined late, and has thus missed past updates for the multi-hosted SV party. " +
              "In this case, the node needs to download the missing updates from other SV nodes.\n" +
              "- This is a participant starting after a hard domain migration. " +
              "In this case, all items in the ACS must come from the previous domain migration."
          )
        }

        // The update history only stores actual updates,
        // it doesn't try to reconstruct past updates from the initial state.
        Future.unit
      }

      override def ingestUpdate(domain: DomainId, update: TreeUpdate)(implicit
          traceContext: TraceContext
      ): Future[Unit] = {
        val offset = update match {
          case ReassignmentUpdate(reassignment) => reassignment.offset.getOffset
          case TransactionTreeUpdate(tree) => tree.getOffset
        }
        val recordTime = update match {
          case ReassignmentUpdate(reassignment) => reassignment.recordTime
          case TransactionTreeUpdate(tree) => CantonTimestamp.assertFromInstant(tree.getRecordTime)
        }

        // Note: in theory, it's enough if this action is atomic - there should only be a single
        // ingestion sink storing updates for the given (participant, party, migrationId) tuple,
        // so there should be no concurrent updates.
        // In practice, we still want to have some protection against duplicate inserts, in case
        // the ingestion service is buggy or there are two misconfigured apps trying to ingest the same updates.
        // This is implemented with a unique index in the database schema.
        val action = readOffset()
          .flatMap({
            case None =>
              logger.debug(
                s"History $historyId migration $domainMigrationId ingesting None => $offset @ $recordTime"
              )
              ingestUpdate_(update).andThen(updateOffset(offset))
            case Some(lastIngestedOffset) =>
              if (offset <= lastIngestedOffset) {
                logger.warn(
                  s"Update offset $offset <= last ingested offset $lastIngestedOffset for ${description()}, skipping database actions. " +
                    "This is expected if the SQL query was automatically retried after a transient database error. " +
                    "Otherwise, this is unexpected and most likely caused by two identical UpdateIngestionService instances " +
                    "ingesting into the same logical database."
                )
                DBIO.successful(())
              } else {
                logger.debug(
                  s"History $historyId migration $domainMigrationId ingesting $lastIngestedOffset => $offset @ $recordTime"
                )
                ingestUpdate_(update).andThen(updateOffset(offset))
              }
          })
          .map(_ => ())
          .transactionally

        storage.queryAndUpdate(action, "ingestUpdate")
      }

      private def updateOffset(offset: String): DBIOAction[?, NoStream, Effect.Write] =
        sqlu"""
        update update_history_last_ingested_offsets
        set last_ingested_offset = ${lengthLimited(offset)}
        where history_id = $historyId and migration_id = $domainMigrationId
      """

      private def readOffset(): DBIOAction[Option[String], NoStream, Effect.Read] =
        sql"""
        select last_ingested_offset
        from update_history_last_ingested_offsets
        where history_id = $historyId and migration_id = $domainMigrationId
      """
          .as[Option[String]]
          .head

      private def ingestUpdate_(
          update: TreeUpdate
      )(implicit tc: TraceContext): DBIOAction[?, NoStream, Effect.Read & Effect.Write] = {
        update match {
          case ReassignmentUpdate(reassignment) =>
            ingestReassignment(reassignment)
          case TransactionTreeUpdate(tree) =>
            if (tree.getWorkflowId.startsWith(IMPORT_ACS_WORKFLOW_ID_PREFIX)) {
              logger.debug(
                s"Skipping update ${tree.getUpdateId} at offset ${tree.getOffset} for ${description()} because it is an ACS import workflow update."
              )
              DBIOAction.successful(())
            } else {
              ingestTransactionTree(tree)
            }
        }
      }

      private def ingestReassignment(
          reassignment: Reassignment[ReassignmentEvent]
      ): DBIOAction[?, NoStream, Effect.Write] = {
        reassignment match {
          case Reassignment(_, _, _, event: ReassignmentEvent.Assign) =>
            ingestAssignment(reassignment, event)
          case Reassignment(_, _, _, event: ReassignmentEvent.Unassign) =>
            ingestUnassignment(reassignment, event)
        }
      }

      private def ingestUnassignment(
          reassignment: Reassignment[?],
          event: ReassignmentEvent.Unassign,
      ): DBIOAction[?, NoStream, Effect.Write] = {
        val safeUpdateId = lengthLimited(reassignment.updateId)
        val safeRecordTime = reassignment.recordTime
        val safeParticipantOffset = lengthLimited(reassignment.offset.getOffset)
        val safeUnassignId = lengthLimited(event.unassignId)
        val safeContractId = lengthLimited(event.contractId.contractId)
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
          $safeParticipantOffset, ${event.source}, $domainMigrationId,
          ${event.counter}, ${event.target},
          $safeUnassignId, ${event.submitter},
          $safeContractId
        )
      """
      }

      private def ingestAssignment(
          reassignment: Reassignment[?],
          event: ReassignmentEvent.Assign,
      ): DBIOAction[?, NoStream, Effect.Write] = {
        val safeUpdateId = lengthLimited(reassignment.updateId)
        val safeRecordTime = reassignment.recordTime
        val safeParticipantOffset = lengthLimited(reassignment.offset.getOffset)
        val safeUnassignId = lengthLimited(event.unassignId)
        val safeContractId = lengthLimited(event.createdEvent.getContractId)
        val safeEventId = lengthLimited(event.createdEvent.getEventId)
        val templateId = event.createdEvent.getTemplateId
        val templateIdModuleName = lengthLimited(templateId.getModuleName)
        val templateIdEntityName = lengthLimited(templateId.getEntityName)
        val templateIdPackageId = lengthLimited(templateId.getPackageId)
        val safePackageName = lengthLimited(event.createdEvent.getPackageName)
        val createArguments = serializeValue(event.createdEvent.getArguments)
        val safeCreatedAt = CantonTimestamp.assertFromInstant(event.createdEvent.createdAt)

        import storage.DbStorageConverters.setParameterByteArray
        sqlu"""
        insert into update_history_assignments(
          history_id,update_id,record_time,
          participant_offset,domain_id,migration_id,
          reassignment_counter,source_domain,
          reassignment_id,submitter,
          contract_id, event_id, created_at,
          template_id_package_id, template_id_module_name, template_id_entity_name,
          package_name, create_arguments
        )
        values (
          $historyId, $safeUpdateId, $safeRecordTime,
          $safeParticipantOffset, ${event.target}, $domainMigrationId,
          ${event.counter}, ${event.source},
          $safeUnassignId, ${event.submitter},
          $safeContractId, $safeEventId, $safeCreatedAt,
          $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
          $safePackageName, $createArguments
        )
      """
      }

      private def ingestTransactionTree(
          tree: TransactionTree
      ): DBIOAction[?, NoStream, Effect.Read & Effect.Write] = {
        insertTransactionUpdateRow(tree).flatMap(updateRowId => {
          // Note: the order of elements in the eventsById map doesn't matter, and is not preserved here.
          // The order of elements in the rootEventIds and childEventIds lists DOES matter, and needs to be preserved.
          DBIOAction.seq[Effect.Write](
            tree.getEventsById.values().asScala.toSeq.map {
              case created: CreatedEvent =>
                insertCreateEventRow(created, updateRowId)
              case exercised: ExercisedEvent =>
                insertExerciseEventRow(exercised, updateRowId)
              case _ =>
                throw new RuntimeException("Unsupported event type")
            }*
          )
        })
      }

      private def insertTransactionUpdateRow(
          tree: TransactionTree
      ): DBIOAction[Long, NoStream, Effect.Read & Effect.Write] = {
        val safeUpdateId = lengthLimited(tree.getUpdateId)
        val safeRecordTime = CantonTimestamp.assertFromInstant(tree.getRecordTime)
        val safeParticipantOffset = lengthLimited(tree.getOffset)
        val safeDomainId = lengthLimited(tree.getDomainId)
        val safeEffectiveAt = CantonTimestamp.assertFromInstant(tree.getEffectiveAt)
        val safeRootEventIds = tree.getRootEventIds.asScala.toSeq.map(lengthLimited)

        (sql"""
          insert into update_history_transactions(
            history_id, update_id, record_time,
            participant_offset, domain_id, migration_id,
            effective_at, root_event_ids
          )
          values (
            $historyId, $safeUpdateId, $safeRecordTime,
            $safeParticipantOffset, $safeDomainId, $domainMigrationId,
            $safeEffectiveAt, $safeRootEventIds
          )
          returning row_id
        """.asUpdateReturning[Long].head)
      }

      private def insertCreateEventRow(
          event: CreatedEvent,
          updateRowId: Long,
      ): DBIOAction[?, NoStream, Effect.Write] = {
        val safeEventId = lengthLimited(event.getEventId)
        val safeContractId = lengthLimited(event.getContractId)
        val templateId = event.getTemplateId
        val templateIdModuleName = lengthLimited(templateId.getModuleName)
        val templateIdEntityName = lengthLimited(templateId.getEntityName)
        val templateIdPackageId = lengthLimited(templateId.getPackageId)
        val safePackageName = lengthLimited(event.getPackageName)
        val createArguments = serializeValue(event.getArguments)
        val safeCreatedAt = CantonTimestamp.assertFromInstant(event.createdAt)

        import storage.DbStorageConverters.setParameterByteArray
        sqlu"""
          insert into update_history_creates(
            history_id, event_id, update_row_id,
            contract_id, created_at,
            template_id_package_id, template_id_module_name, template_id_entity_name,
            package_name, create_arguments
          )
          values (
            $historyId, $safeEventId, $updateRowId,
            $safeContractId, $safeCreatedAt,
            $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
            $safePackageName, $createArguments
          )
        """
      }

      private def insertExerciseEventRow(
          event: ExercisedEvent,
          updateRowId: Long,
      ): DBIOAction[?, NoStream, Effect.Write] = {
        val safeEventId = lengthLimited(event.getEventId)
        val safeChoice = lengthLimited(event.getChoice)
        val safeContractId = lengthLimited(event.getContractId)
        val safeChildEventIds = event.getChildEventIds.asScala.toSeq.map(lengthLimited)
        val templateId = event.getTemplateId
        val templateIdModuleName = lengthLimited(templateId.getModuleName)
        val templateIdEntityName = lengthLimited(templateId.getEntityName)
        val templateIdPackageId = lengthLimited(templateId.getPackageId)
        val choiceArguments = serializeValue(event.getChoiceArgument)
        val exerciseResult = serializeValue(event.getExerciseResult)

        import storage.DbStorageConverters.setParameterByteArray
        sqlu"""
          insert into update_history_exercises(
            history_id, event_id, update_row_id,
            child_event_ids, choice,
            template_id_package_id, template_id_module_name, template_id_entity_name,
            contract_id, consuming,
            argument, result
          )
          values (
            $historyId, $safeEventId, $updateRowId,
            $safeChildEventIds, $safeChoice,
            $templateIdPackageId, $templateIdModuleName, $templateIdEntityName,
            $safeContractId, ${event.isConsuming},
            $choiceArguments, $exerciseResult
          )
        """
      }
    }

  private[this] def cleanUpDataAfterDomainMigration(
      historyId: Long
  )(implicit tc: TraceContext): Future[Unit] = {
    val previousMigrationId = domainMigrationInfo.currentMigrationId - 1
    domainMigrationInfo.acsRecordTime match {
      case Some(acsRecordTime) =>
        if (domainMigrationInfo.domainWasPaused) {
          verifyNoRolledBackData(historyId, previousMigrationId, acsRecordTime)
        } else {
          deleteRolledBackUpdateHistory(historyId, previousMigrationId, acsRecordTime)
        }
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
            where update_row_id in (
              select row_id
              from update_history_transactions
              where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
            )
          """.as[Long].head,
          sql"""
            select count(*) from update_history_exercises
            where update_row_id in (
              select row_id
              from update_history_transactions
              where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
            )
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
          logger.error(
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
    storage.query(action, "deleteRolledBackUpdateHistory")
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
            where update_row_id in (
              select row_id
              from update_history_transactions
              where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
            )
          """,
          sqlu"""
            delete from update_history_exercises
            where update_row_id in (
              select row_id
              from update_history_transactions
              where history_id = $historyId and migration_id = $migrationId and record_time > $recordTime
            )
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
          logger.warn(
            s"Deleted $rows rows for $updateStreamParty where migration_id = $migrationId and record_time > $recordTime. " +
              "This is expected during a disaster recovery, where we are rolling back the domain to a previous state. " +
              "In is NOT expected during regular hard domain migrations."
          )
        } else {
          logger.debug(s"No rows deleted for $updateStreamParty")
        }
      )
    storage.update(action, "deleteRolledBackUpdateHistory")
  }

  private def queryTransactions(
      beginOffset: String,
      endOffset: String,
      count: Int,
  )(implicit tc: TraceContext) = {
    val safeBegin = lengthLimited(beginOffset)
    val safeEnd = lengthLimited(endOffset)
    for {
      rows <- storage
        .query(
          sql"""
      select
        row_id,
        update_id,
        record_time,
        participant_offset,
        domain_id,
        migration_id,
        effective_at,
        root_event_ids
      from update_history_transactions
      where
        history_id = $historyId and
        migration_id = $domainMigrationId and
        participant_offset > $safeBegin and
        participant_offset <= $safeEnd
      order by participant_offset
      limit $count
    """.as[SelectFromTransactions],
          "queryTransactions",
        )
    } yield {
      rows.lastOption.map(last => (last.participantOffset, rows))
    }
  }

  private def queryCreateEvents(
      transactionRowId: Long
  )(implicit tc: TraceContext) = {
    storage
      .query(
        sql"""
      select
        event_id,
        contract_id,
        created_at,
        template_id_package_id,
        template_id_module_name,
        template_id_entity_name,
        package_name,
        create_arguments
      from update_history_creates
      where update_row_id = $transactionRowId
    """.as[SelectFromCreateEvents],
        "queryCreateEvents",
      )
  }

  private def queryExerciseEvents(
      transactionRowId: Long
  )(implicit tc: TraceContext) = {
    storage
      .query(
        sql"""
      select
        event_id,
        child_event_ids,
        choice,
        template_id_package_id,
        template_id_module_name,
        template_id_entity_name,
        contract_id,
        consuming,
        argument,
        result
      from update_history_exercises
      where update_row_id = $transactionRowId
    """.as[SelectFromExerciseEvents],
        "queryExerciseEvents",
      )
  }

  private def queryAssignments(
      beginOffset: String,
      endOffset: String,
      count: Int,
  )(implicit tc: TraceContext) = {
    val safeBegin = lengthLimited(beginOffset)
    val safeEnd = lengthLimited(endOffset)
    for {
      rows <- storage
        .query(
          sql"""
      select
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
        create_arguments
      from update_history_assignments
      where
        history_id = $historyId and
        migration_id = $domainMigrationId and
        participant_offset > $safeBegin and
        participant_offset <= $safeEnd
      order by participant_offset
      limit $count
    """.as[SelectFromAssignments],
          "queryAssignments",
        )
    } yield {
      rows.lastOption.map(last => (last.participantOffset, rows))
    }
  }

  private def queryUnassignments(
      beginOffset: String,
      endOffset: String,
      count: Int,
  )(implicit tc: TraceContext) = {
    val safeBegin = lengthLimited(beginOffset)
    val safeEnd = lengthLimited(endOffset)
    for {
      rows <- storage
        .query(
          sql"""
      select
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
        history_id = $historyId and
        migration_id = $domainMigrationId and
        participant_offset > $safeBegin and
        participant_offset <= $safeEnd
      order by participant_offset
      limit $count
    """.as[SelectFromUnassignments],
          "queryUnassignments",
        )
    } yield {
      rows.lastOption.map(last => (last.participantOffset, rows))
    }
  }

  def updateStream(
      beginOffset: String,
      endOffset: String,
  )(implicit
      tc: TraceContext
  ): Source[LedgerClient.GetTreeUpdatesResponse, NotUsed] = {
    val transactions = Source
      // Fetch transactions in batches of 10
      .unfoldAsync(beginOffset)(queryTransactions(_, endOffset, 10))
      .mapConcat(x => x.iterator)
      // For each transaction, fetch all corresponding events
      .mapAsync(parallelism = 4)(updateRow =>
        for {
          creates <- queryCreateEvents(updateRow.rowId)
          exercises <- queryExerciseEvents(updateRow.rowId)
        } yield decodeTransaction(updateRow, creates, exercises)
      )

    val assignments = Source
      // Fetch assignments in batches of 10
      .unfoldAsync(beginOffset)(queryAssignments(_, endOffset, 10))
      .mapConcat(x => x.iterator)
      // For each transaction, fetch all corresponding events
      .map(decodeAssignment)

    val unassignments = Source
      // Fetch assignments in batches of 10
      .unfoldAsync(beginOffset)(queryUnassignments(_, endOffset, 10))
      .mapConcat(x => x.iterator)
      // For each transaction, fetch all corresponding events
      .map(decodeUnassignment)

    // Merge transactions and assignments by offset
    transactions
      .mergeSorted(assignments)(updateOrdering)
      .mergeSorted(unassignments)(updateOrdering)
  }

  private val updateOrdering = Ordering.by[LedgerClient.GetTreeUpdatesResponse, String] {
    case LedgerClient.GetTreeUpdatesResponse(TransactionTreeUpdate(tree), _) => tree.getOffset
    case LedgerClient.GetTreeUpdatesResponse(ReassignmentUpdate(update), _) =>
      update.offset.getOffset
  }

  private def tid(packageName: String, moduleName: String, entityName: String) =
    new Identifier(packageName, moduleName, entityName)

  private def decodeTransaction(
      updateRow: SelectFromTransactions,
      createRows: Seq[SelectFromCreateEvents],
      exerciseRows: Seq[SelectFromExerciseEvents],
  ): LedgerClient.GetTreeUpdatesResponse = {

    val createEventsById = createRows
      .map(row =>
        row.eventId -> new CreatedEvent(
          /*witnessParties = */ java.util.Collections.emptyList(),
          /*eventId = */ row.eventId,
          /*templateId = */ tid(
            row.templatePackageId,
            row.templateModuleName,
            row.templateEntityName,
          ),
          /* packageName = */ row.packageName,
          /*contractId = */ row.contractId,
          /*arguments = */ deserializeValue(row.createArguments).asRecord().get(),
          /*createdEventBlob = */ ByteString.EMPTY,
          /*interfaceViews = */ java.util.Collections.emptyMap(),
          /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
          /*contractKey = */ java.util.Optional.empty(),
          /*signatories = */ java.util.Collections.emptyList(),
          /*observers = */ java.util.Collections.emptyList(),
          /*createdAt = */ row.createdAt.toInstant,
        )
      )
      .toMap
    val exerciseEventsById = exerciseRows
      .map(row =>
        row.eventId -> new ExercisedEvent(
          /*witnessParties = */ java.util.Collections.emptyList(),
          /*eventId = */ row.eventId,
          /*templateId = */ tid(
            row.templatePackageId,
            row.templateModuleName,
            row.templateEntityName,
          ),
          /*packageName = */ "dummyPackageName", // TODO(#10925): pipe this value through the update history
          /*interfaceId = */ java.util.Optional.empty(),
          /*contractId = */ row.contractId,
          /*choice = */ row.choice,
          /*choiceArgument = */ deserializeValue(row.argument),
          /*actingParties = */ java.util.Collections.emptyList(),
          /*consuming = */ row.consuming,
          /*childEventIds = */ row.childEventIds.asJava,
          /*exerciseResult = */ deserializeValue(row.result),
        )
      )
      .toMap
    val rootEventsIds = updateRow.rootEventIds
    val eventsById = createEventsById ++ exerciseEventsById

    LedgerClient.GetTreeUpdatesResponse(
      update = TransactionTreeUpdate(
        new TransactionTree(
          /*updateId = */ updateRow.updateId,
          /*commandId = */ "UpdateHistory does not store commandId",
          /*workflowId = */ "UpdateHistory does not store workflowId",
          /*effectiveAt = */ updateRow.effectiveAt.toInstant,
          /*offset = */ updateRow.participantOffset,
          /*eventsById = */ eventsById.asJava,
          /*rootEventIds = */ rootEventsIds.asJava,
          /*domainId = */ updateRow.domainId,
          /*traceContext = */ TraceContextOuterClass.TraceContext.getDefaultInstance,
          /*recordTime = */ updateRow.recordTime.toInstant,
        )
      ),
      domainId = DomainId.tryFromString(updateRow.domainId),
    )
  }

  private def decodeAssignment(
      row: SelectFromAssignments
  ): LedgerClient.GetTreeUpdatesResponse = {
    LedgerClient.GetTreeUpdatesResponse(
      ReassignmentUpdate(
        Reassignment[Assign](
          updateId = row.updateId,
          offset = new ParticipantOffset.Absolute(row.participantOffset),
          recordTime = row.recordTime,
          event = Assign(
            submitter = row.submitter,
            source = row.sourceDomain,
            target = row.domainId,
            unassignId = row.reassignmentId,
            createdEvent = new CreatedEvent(
              /*witnessParties = */ java.util.Collections.emptyList(),
              /*eventId = */ row.eventId,
              /*templateId = */ tid(
                row.templatePackageId,
                row.templateModuleName,
                row.templateEntityName,
              ),
              /*packageName = */ row.packageName,
              /*contractId = */ row.contractId,
              /*arguments = */ deserializeValue(row.createArguments).asRecord().get(),
              /*createdEventBlob = */ ByteString.EMPTY,
              /*interfaceViews = */ java.util.Collections.emptyMap(),
              /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
              /*contractKey = */ java.util.Optional.empty(),
              /*signatories = */ java.util.Collections.emptyList(),
              /*observers = */ java.util.Collections.emptyList(),
              /*createdAt = */ row.createdAt.toInstant,
            ),
            counter = row.reassignmentCounter,
          ),
        )
      ),
      row.domainId,
    )
  }

  private def decodeUnassignment(
      row: SelectFromUnassignments
  ): LedgerClient.GetTreeUpdatesResponse = {
    LedgerClient.GetTreeUpdatesResponse(
      ReassignmentUpdate(
        Reassignment[Unassign](
          updateId = row.updateId,
          offset = new ParticipantOffset.Absolute(row.participantOffset),
          recordTime = row.recordTime,
          event = Unassign(
            submitter = row.submitter,
            source = row.domainId,
            target = row.targetDomain,
            unassignId = row.reassignmentId,
            counter = row.reassignmentCounter,
            contractId = new ContractId(row.contractId),
          ),
        )
      ),
      row.domainId,
    )
  }

  // Note: for now, storing values in binary protobuf format.
  // JSON decoding requires type information. To read the value of a field,
  // you need to look up the type of the field, in order to distinguish between party
  // and string primitives, for example.
  // TODO(#10488): Store values in JSON format instead.
  private def serializeValue(x: com.daml.ledger.javaapi.data.Value): Array[Byte] = {
    x.toProto.toByteArray
  }
  private def deserializeValue(x: Array[Byte]): com.daml.ledger.javaapi.data.Value = {
    com.daml.ledger.javaapi.data.Value
      .fromProto(com.daml.ledger.api.v2.ValueOuterClass.Value.parseFrom(x))
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
        )
      )
    }

  private implicit lazy val GetResultSelectFromCreateEvents: GetResult[SelectFromCreateEvents] =
    GetResult { prs =>
      import prs.*
      (SelectFromCreateEvents.apply _).tupled(
        (
          <<[String],
          <<[String],
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Array[Byte]],
        )
      )
    }

  private implicit lazy val GetResultSelectFromExerciseEvents: GetResult[SelectFromExerciseEvents] =
    GetResult { prs =>
      import prs.*
      (SelectFromExerciseEvents.apply _).tupled(
        (
          <<[String],
          <<[Seq[String]],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Boolean],
          <<[Array[Byte]],
          <<[Array[Byte]],
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
          <<[String],
          <<[DomainId],
          <<[Long],
          <<[Long],
          <<[DomainId],
          <<[String],
          <<[PartyId],
          <<[String],
          <<[String],
          <<[CantonTimestamp],
          <<[String],
          <<[String],
          <<[String],
          <<[String],
          <<[Array[Byte]],
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
          <<[String],
          <<[DomainId],
          <<[Long],
          <<[Long],
          <<[DomainId],
          <<[String],
          <<[PartyId],
          <<[String],
        )
      )
    }
}

object UpdateHistory {
  case class State(
      historyId: Option[Long]
  ) {}
  object State {
    def empty(): State = State(None)
  }

  private case class SelectFromTransactions(
      rowId: Long,
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: String,
      domainId: String,
      migrationId: Long,
      effectiveAt: CantonTimestamp,
      rootEventIds: Seq[String],
  )

  private case class SelectFromCreateEvents(
      eventId: String,
      contractId: String,
      createdAt: CantonTimestamp,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      packageName: String,
      createArguments: Array[Byte],
  )

  private case class SelectFromExerciseEvents(
      eventId: String,
      childEventIds: Seq[String],
      choice: String,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      contractId: String,
      consuming: Boolean,
      argument: Array[Byte],
      result: Array[Byte],
  )

  private case class SelectFromAssignments(
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: String,
      domainId: DomainId,
      migrationId: Long,
      reassignmentCounter: Long,
      sourceDomain: DomainId,
      reassignmentId: String,
      submitter: PartyId,
      contractId: String,
      eventId: String,
      createdAt: CantonTimestamp,
      templatePackageId: String,
      templateModuleName: String,
      templateEntityName: String,
      packageName: String,
      createArguments: Array[Byte],
  )

  private case class SelectFromUnassignments(
      updateId: String,
      recordTime: CantonTimestamp,
      participantOffset: String,
      domainId: DomainId,
      migrationId: Long,
      reassignmentCounter: Long,
      targetDomain: DomainId,
      reassignmentId: String,
      submitter: PartyId,
      contractId: String,
  )

}
