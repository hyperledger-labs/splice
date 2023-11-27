package com.daml.network.store.db

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.data.OptionT
import cats.implicits.*
import com.daml.ledger.api.v1.TransactionOuterClass
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Template, TransactionTree}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
  TreeUpdate,
}
import com.daml.network.store.*
import com.daml.network.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  QualifiedName,
  TemplateJsonDecoder,
  Trees,
}
import com.digitalasset.canton.config.CantonRequireTypes.{String255, String256M}
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.{SortedMap, VectorMap}
import scala.concurrent.{ExecutionContext, Future, Promise}
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.daml.network.store.MultiDomainAcsStore.{ContractStateEvent, ReassignmentId}
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableWithStateResult
import com.daml.network.store.db.AcsTables.ContractStateRowData
import com.daml.nonempty.NonEmpty
import com.google.protobuf.ByteString

import scala.collection.mutable

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: io.circe.Json,
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter[_ <: AcsRowData],
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    retryProvider: RetryProvider,
    ingestTxLogInsert: (TXI, TraceContext) => Either[String, DBIOAction[?, NoStream, Effect.Write]],
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with AcsTables
    with AcsQueries
    with StoreErrors
    with NamedLogging
    with LimitHelpers {

  import MultiDomainAcsStore.*
  import DbMultiDomainAcsStore.*
  import profile.api.jdbcActionExtensionMethods

  private val state = new AtomicReference[State](State.empty())

  def storeId: Int =
    state
      .get()
      .storeId
      .getOrElse(throw new RuntimeException("Using storeId before it was assigned"))

  // Some callers depend on all queries always returning sensible data, but may perform queries
  // before the ACS is fully ingested. We therefore delay all queries until the ACS is ingested.
  private val finishedAcsIngestion: Promise[Unit] = Promise()
  def waitUntilAcsIngested[T](f: => Future[T]): Future[T] =
    finishedAcsIngestion.future.flatMap(_ => f)
  def waitUntilAcsIngested(): Future[Unit] =
    finishedAcsIngestion.future

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where = sql"""acs.contract_id = ${lengthLimited(id.contractId)}""",
        ).headOption,
        "lookupContractById",
      )
      .map(result => contractWithStateFromRow(companion)(result))
      .value
  }

  /** Returns any contract of the same template as the passed companion.
    */
  override def findAnyContractWithOffset[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(templateId)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "findAnyContractWithOffset",
        )
        .getOrRaise(offsetExpectedError())
      contractWithState = resultWithOffset.row.map(contractWithStateFromRow(companion)(_))
    } yield {
      QueryResult(
        resultWithOffset.offset,
        contractWithState,
      )
    }
  }

  override def lookupContractStateById(id: ContractId[?])(implicit
      traceContext: TraceContext
  ): Future[Option[ContractState]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where = sql"""acs.contract_id = ${lengthLimited(id.contractId)}""",
        ).headOption,
        "lookupContractStateById",
      )
      .map(result => contractStateFromRow(result.stateRow))
      .value
  }

  def hasArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean] =
    // TODO(#6458): implement this as a single DB query
    Future.sequence(ids.map(lookupContractStateById)).map(_.exists(_.isEmpty))

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query( // index: acs_store_template_sid_tid_en
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where = sql"""template_id_qualified_name = ${QualifiedName(templateId)}""",
          orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
        ),
        "listContracts",
      )
      limited = applyLimit(limit, result)
      withState = limited.map(contractWithStateFromRow(companion)(_))
    } yield withState
  }

  override def listAssignedContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[AssignedContract[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query( // index: acs_store_template_sid_tid_en
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              templateId
            )} and assigned_domain is not null""",
          orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
        ),
        "listAssignedContracts",
      )
      limited = applyLimit(limit, result)
      assigned = limited.map(assignedContractFromRow(companion)(_))
    } yield assigned
  }

  // TODO (#3822) `expiresAt` is unused here, but necessary for the in-memory version to work.
  // With an ExpiredContract interface it can be dropped from both.
  override private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(expiresAt: T => Instant)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): ListExpiredContracts[TCid, T] = { (now, limit) => implicit traceContext =>
    val templateId = companionClass.typeId(companion)
    for {
      _ <- waitUntilAcsIngested()
      result <- storage
        .query( // index: acs_store_template_sid_tid_ce
          selectFromAcsTableWithState(
            acsTableName,
            storeId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                templateId
              )} and acs.contract_expires_at < $now""",
            orderLimit = sql"""limit ${sqlLimit(limit)}""",
          ),
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit(limit, result)
      assigned = limited.map(assignedContractFromRow(companion)(_))
    } yield assigned
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] = waitUntilAcsIngested {
    val templateId = companionClass.typeId(companion)
    for {
      result <- storage.query(
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              templateId
            )} and assigned_domain = $domain""",
          orderLimit = sql"""limit ${sqlLimit(limit)}""",
        ),
        "listContractsOnDomain",
      )
      limited = applyLimit(limit, result)
      contracts = limited.map(row => contractFromRow(companion)(row.acsRow))
    } yield contracts
  }

  override def listAssignedContractsNotOnDomainN(
      excludedDomain: DomainId,
      participantIdSource: ParticipantAdminConnection.HasParticipantId,
      companions: Seq[ConstrainedTemplate],
      limit: notOnDomainsTotalLimit.type,
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] = waitUntilAcsIngested {
    val templateIdMap = companions
      .map(c => QualifiedName(c.TEMPLATE_ID) -> c)
      .toMap
    val templateIds = templateIdMap.keys.mkString("('", "','", "')")
    for {
      participantId <- participantIdSource.getParticipantId()
      result <- storage.query(
        selectFromAcsTableWithState(
          acsTableName,
          storeId,
          where =
            sql"""template_id_qualified_name IN #${templateIds} and assigned_domain is not null and assigned_domain != $excludedDomain""",
          // bytea comparison in PG is left-to-right unsigned ascending, shorter
          // array is lesser if bytes are otherwise equal; there's an equivalent
          // soft implementation in
          // InMemoryMultiDomainAcsStore.reassignmentContractOrder
          orderLimit = sql"""order by digest((contract_id || $participantId)::bytea, 'md5'::text)
              limit ${(limit: PageLimit).limit}""",
        ),
        "listAssignedContractsNotOnDomainN",
      )
    } yield result.map { row =>
      assignedContractFromRow(
        templateIdMap(row.acsRow.templateIdQualifiedName)
      )(row)
    }
  }

  override def streamAssignedContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[AssignedContract[TCid, T], NotUsed] = {
    val templateId = companionClass.typeId(companion)
    streamContractsWithState(
      pageSize = defaultPageSizeForContractStream,
      where = sql"""assigned_domain is not null and template_id_qualified_name = ${QualifiedName(
          templateId
        )}""",
    )
      .map(assignedContractFromRow(companion)(_))
  }

  private val defaultPageSizeForContractStream = PageLimit.tryCreate(100)

  /** Returns a stream of contracts with their current state.
    * The same contract may appear multiple times in the stream if the contract state changes.
    */
  private def streamContractsWithState(
      pageSize: PageLimit,
      where: SQLActionBuilder,
  )(implicit
      traceContext: TraceContext
  ): Source[SelectFromAcsTableWithStateResult, NotUsed] = {
    Source
      .future(
        // TODO(#5534): this is currently waiting until the whole ACS has been ingested.
        //  After switching to streaming ACS ingestion, we could start streaming contracts while
        //  the ACS is being ingested.
        waitUntilAcsIngested()
      )
      .flatMapConcat { _ =>
        Source
          .unfoldAsync(0L) { fromNumber =>
            val offsetPromise = state.get().offsetChanged
            storage
              .query(
                selectFromAcsTableWithState(
                  acsTableName,
                  storeId,
                  where = (where ++ sql" and state_number >= $fromNumber").toActionBuilder,
                  orderLimit =
                    (sql"order by state_number limit ${sqlLimit(pageSize)}").toActionBuilder,
                ),
                "streamContractsWithState",
              )
              .flatMap { rows =>
                rows.lastOption.map(_.stateRow.stateNumber) match {
                  case Some(lastNumber) =>
                    Future.successful(
                      (
                        lastNumber + 1,
                        rows,
                      )
                    )
                  case None =>
                    // to avoid polling the DB, we wait for a new offset to have been ingested
                    offsetPromise.future.map(_ => fromNumber -> Vector.empty)
                }
              }
              .map(Some(_))
          }
      }
      .mapConcat(identity)
  }

  override def streamReadyForAssign()(implicit
      tc: TraceContext
  ): Source[ReassignmentEvent.Unassign, NotUsed] = {
    streamContractsWithState(
      pageSize = defaultPageSizeForContractStream,
      where = sql"""assigned_domain is null""",
    )
      .map(reassignmentEventUnassignFromRow)
  }

  override def isReadyForAssign(contractId: ContractId[_], out: ReassignmentId)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    waitUntilAcsIngested {
      storage
        .querySingle(
          selectFromAcsTableWithState(
            acsTableName,
            storeId,
            where = sql"""acs.contract_id = ${contractId}""",
          ).headOption,
          "isReadyForAssign",
        )
        .value
        .map {
          case Some(SelectFromAcsTableWithStateResult(_, state)) =>
            state.assignedDomain.isEmpty &&
            state.reassignmentSourceDomain.contains(out.source) &&
            state.reassignmentUnassignId.contains(out.id)
          case _ => false
        }
    }
  }

  override private[store] def listIncompleteReassignments()(implicit
      tc: TraceContext
  ): Future[Map[ContractId[_], NonEmpty[Set[ReassignmentId]]]] = {
    for {
      rows <- storage
        .query(
          sql"""
             select contract_id, source_domain, unassign_id
             from incomplete_reassignments
             where store_id = $storeId
             """.as[(String, String, String)],
          "listIncompleteReassignments",
        )
    } yield rows
      .map(row => row._1 -> new ReassignmentId(DomainId.tryFromString(row._2), row._3))
      .groupBy(_._1)
      .map { case (key, values) =>
        new ContractId(key) -> NonEmpty
          .from(values.map(_._2).toSet)
          .getOrElse(sys.error("Impossible"))
      }
  }

  override def signalWhenIngestedOrShutdown(offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    state
      .updateAndGet(_.withOffsetToSignal(offset))
      .offsetIngestionsToSignal
      .get(offset) match {
      case None => Future.unit
      case Some(offsetIngestedPromise) =>
        val name = s"signalWhenIngested($offset)"
        val ingestedOrShutdown = retryProvider
          .waitUnlessShutdown(offsetIngestedPromise.future)
          .onShutdown(
            logger.debug(s"Aborted $name, as we are shutting down")
          )
        retryProvider.futureSupervisor.supervised(name)(ingestedOrShutdown)
    }
  }

  override def ingestionSink: IngestionSink = new MultiDomainAcsStore.IngestionSink {
    override def ingestionFilter: IngestionFilter = contractFilter.ingestionFilter

    override def initialize()(implicit traceContext: TraceContext): Future[Option[String]] = {
      // Notes:
      // - Postgres JSONB does not preserve white space, does not preserve the order of object keys, and does not keep duplicate object keys
      // - Postgres JSONB columns have a maximum size of 255MB
      // - We are using noSpacesSortKeys to insert a canonical serialization of the JSON object, even though this is not necessary for Postgres
      // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
      val descriptorStr = String256M.tryCreate(storeDescriptor.noSpacesSortKeys)
      for {
        _ <- storage
          .update(
            sql"""
            insert into store_descriptors (descriptor)
            values (${descriptorStr}::jsonb)
            on conflict do nothing
           """.asUpdate,
            "initialize.1",
          )
        (newStoreId, lastIngestedOffset) <- storage
          .querySingle(
            sql"""
             select id, last_ingested_offset
             from store_descriptors
             where descriptor = ${descriptorStr}::jsonb
             """.as[(Int, Option[String])].headOption,
            "initialize.2",
          )
          .getOrRaise(
            new RuntimeException(s"No row for $storeDescriptor found, which was just inserted!")
          )
        alreadyIngestedAcs = lastIngestedOffset.isDefined
        acsSizeInDb <-
          if (alreadyIngestedAcs) {
            storage
              .querySingle(
                sql"""
                   select count(*)
                   from #$acsTableName
                   where store_id = $newStoreId
                 """.as[Int].headOption,
                "initialize.getAcsCount",
              )
              .getOrElse(0)
          } else {
            Future.successful(0)
          }
      } yield {
        // Note: IngestionSink.initialize() may be called multiple times for the same store instance,
        // if for example the ingestion loop restarts.
        val oldState = state.getAndUpdate(
          _.withInitialState(
            storeId = newStoreId,
            acsSizeInDb = acsSizeInDb,
            lastIngestedOffset = lastIngestedOffset,
          )
        )
        lastIngestedOffset.foreach(oldState.signalOffsetChanged)

        if (alreadyIngestedAcs) {
          logger.info(s"Store $storeDescriptor resumed with storeId $newStoreId")
          finishedAcsIngestion.trySuccess(()).discard
        } else {
          logger.info(s"Store $storeDescriptor initialized with storeId $newStoreId")
        }

        lastIngestedOffset
      }
    }

    // Note: returns a DBIOAction, as updating the offset needs to happen in the same SQL transaction
    // that modifies the ACS/TxLog.
    private def updateOffset(offset: String): DBIOAction[Unit, NoStream, Effect.Write] =
      sql"""
        update store_descriptors
        set last_ingested_offset = ${lengthLimited(offset)}
        where id = $storeId
      """.asUpdate.andThen(DBIO.successful(()))

    private def readOffset(): DBIOAction[Option[String], NoStream, Effect.Read] =
      sql"""
        select last_ingested_offset
        from store_descriptors
        where id = $storeId
      """
        .as[Option[String]]
        .head

    /** Runs the given action to update the database with changes caused at the given offset.
      * The resulting action is guaranteed to be idempotent, even if the given action is not.
      *
      * Note: our storage layer automatically retries database actions that have failed with transient errors.
      * In some cases, it is not known whether the failed action was committed to the database. We therefore have
      * to inspect the last ingested offset, run any updates, and update the last ingested offset, all within one
      * SQL transaction.
      */
    private def ingestUpdateAtOffset[E <: Effect](
        offset: String,
        action: DBIOAction[?, NoStream, Effect.Read & Effect.Write],
    )(implicit
        tc: TraceContext
    ): DBIOAction[Unit, NoStream, Effect.Read & Effect.Write & Effect.Transactional] = {
      readOffset()
        .flatMap({
          case Some(`offset`) =>
            logger.info(s"Update at offset $offset already ingested, skipping database actions")
            DBIO.successful(())
          case None =>
            action.andThen(updateOffset(offset))
          case Some(_) =>
            action.andThen(updateOffset(offset))
        })
        .transactionally
    }
    override def ingestAcs(
        offset: String,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit traceContext: TraceContext): Future[Unit] = {
      assert(
        finishedAcsIngestion.isCompleted == false,
        s"ACS was already ingested for store $storeId",
      )
      val txLogEntries = txLogParser.parseAcs(acs, incompleteOut, incompleteIn).map(_._2)

      // Filter out all contracts we are not interested in
      val todoAcs = acs
        .filter(contract => contractFilter.contains(contract.createdEvent))
      todoAcs.foreach { contract =>
        contractFilter.ensureStakeholderOf(contract.createdEvent)
      }
      val todoIncompleteOut = incompleteOut
        .filter(event => contractFilter.contains(event.createdEvent))
      todoIncompleteOut.foreach { event =>
        contractFilter.ensureStakeholderOf(event.createdEvent)
      }
      val todoIncompleteIn = incompleteIn
        .filter(event => contractFilter.contains(event.reassignmentEvent.createdEvent))
      todoIncompleteIn.foreach { event =>
        contractFilter.ensureStakeholderOf(event.reassignmentEvent.createdEvent)
      }

      val summaryState = MutableIngestionSummary.empty[TXE]
      for {
        _ <- storage
          .queryAndUpdate(
            ingestUpdateAtOffset(
              offset,
              DBIO
                .sequence(
                  // TODO (#5643): batch inserts
                  todoAcs.map { ac =>
                    for {
                      _ <- doIngestAcsInsert(
                        offset,
                        ac.createdEvent,
                        ac.createdEventBlob,
                        stateRowDataFromActiveContract(ac.domainId, ac.reassignmentCounter),
                        summaryState,
                      )
                    } yield ()
                  }
                    ++ todoIncompleteOut.map { evt =>
                      for {
                        _ <- doIngestAcsInsert(
                          offset,
                          evt.createdEvent,
                          evt.createdEventBlob,
                          stateRowDataFromUnassign(evt.reassignmentEvent),
                          summaryState,
                        )
                        _ <- doRegisterIncompleteReassignment(
                          evt.createdEvent.getContractId,
                          evt.reassignmentEvent.source,
                          evt.reassignmentEvent.unassignId,
                          isAssignment = false,
                          summaryState,
                        )
                      } yield ()
                    }
                    ++ todoIncompleteIn.map { evt =>
                      for {
                        _ <- doIngestAcsInsert(
                          offset,
                          evt.reassignmentEvent.createdEvent,
                          evt.reassignmentEvent.createdEventBlob,
                          stateRowDataFromAssign(evt.reassignmentEvent),
                          summaryState,
                        )
                        _ <- doRegisterIncompleteReassignment(
                          evt.reassignmentEvent.createdEvent.getContractId,
                          evt.reassignmentEvent.source,
                          evt.reassignmentEvent.unassignId,
                          isAssignment = true,
                          summaryState,
                        )
                      } yield ()
                    }
                    ++ txLogEntries.map { txe => doIngestTxLogInsert(offset, txe, summaryState) }
                ),
            ),
            "ingestAcs",
          )
      } yield {
        val newAcsSize = summaryState.acsSizeDiff
        val summary = summaryState.toIngestionSummary(
          txId = None,
          offset = offset,
          newAcsSize = newAcsSize,
        )
        state
          .getAndUpdate(
            _.withUpdate(newAcsSize, offset)
          )
          .signalOffsetChanged(offset)

        logger.debug(show"Ingested complete ACS at offset $offset: $summary")

        finishedAcsIngestion.success(())
        logger.info(
          s"Store $storeId ingested the ACS and switched to ingesting updates at $offset"
        )
      }
    }

    override def ingestUpdate(domain: DomainId, transfer: TreeUpdate)(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      transfer match {
        case ReassignmentUpdate(reassignment) =>
          ingestReassignment(reassignment.offset.getOffset, reassignment).map { summaryState =>
            state
              .getAndUpdate(s =>
                s.withUpdate(
                  s.acsSize + summaryState.acsSizeDiff,
                  reassignment.offset.getOffset,
                )
              )
              .signalOffsetChanged(reassignment.offset.getOffset)
            val summary =
              summaryState.toIngestionSummary(
                txId = None,
                offset = reassignment.offset.getOffset,
                newAcsSize = state.get().acsSize,
              )
            logger.debug(show"Ingested reassignment $summary")
          }
        case TransactionTreeUpdate(tree, treeProto) =>
          ingestTransactionTree(domain, tree, treeProto).map { summaryState =>
            state
              .getAndUpdate(s =>
                s.withUpdate(
                  s.acsSize + summaryState.acsSizeDiff,
                  tree.getOffset,
                )
              )
              .signalOffsetChanged(tree.getOffset)
            val summary =
              summaryState.toIngestionSummary(
                txId = Some(tree.getTransactionId),
                offset = tree.getOffset,
                newAcsSize = state.get().acsSize,
              )
            logger.debug(show"Ingested transaction $summary")
          }
      }
    }

    private def ingestReassignment(
        offset: String,
        reassignment: Reassignment[ReassignmentEvent],
    )(implicit tc: TraceContext): Future[MutableIngestionSummary[TXE]] = {
      val summary = MutableIngestionSummary.empty[TXE]
      for {
        _ <- storage
          .queryAndUpdate(
            ingestUpdateAtOffset(
              offset,
              DBIO
                .seq(
                  reassignment.event match {
                    case assign: ReassignmentEvent.Assign
                        if !contractFilter.contains(assign.createdEvent) =>
                      summary.numFilteredAssignEvents += 1
                      DBIO.successful(())
                    case assign: ReassignmentEvent.Assign =>
                      contractFilter.ensureStakeholderOf(assign.createdEvent)
                      for {
                        case Seq(_hasIncompleteReassignments, _hasAcsEntry) <- DBIO.sequence(
                          Seq(
                            hasIncompleteReassignments(assign.createdEvent.getContractId),
                            hasAcsEntry(assign.createdEvent.getContractId),
                          )
                        )
                        alreadyArchived = !_hasAcsEntry & _hasIncompleteReassignments
                        _ <-
                          if (alreadyArchived) {
                            doRegisterIncompleteReassignment(
                              assign.createdEvent.getContractId,
                              assign.source,
                              assign.unassignId,
                              true,
                              summary,
                            )
                          } else if (_hasAcsEntry) {
                            DBIO.seq(
                              doSetContractStateActive(
                                assign.createdEvent.getContractId,
                                assign.target,
                                assign.counter,
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                assign.createdEvent.getContractId,
                                assign.source,
                                assign.unassignId,
                                true,
                                summary,
                              ),
                            )
                          } else {
                            DBIO.seq(
                              doIngestAcsInsert(
                                reassignment.offset.getOffset,
                                assign.createdEvent,
                                assign.createdEventBlob,
                                stateRowDataFromAssign(assign),
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                assign.createdEvent.getContractId,
                                assign.source,
                                assign.unassignId,
                                true,
                                summary,
                              ),
                            )
                          }
                      } yield ()
                    case unassign: ReassignmentEvent.Unassign =>
                      for {
                        case Seq(_hasIncompleteReassignments, _hasAcsEntry) <- DBIO.sequence(
                          Seq(
                            hasIncompleteReassignments(unassign.contractId.contractId),
                            hasAcsEntry(unassign.contractId.contractId),
                          )
                        )
                        filteredOut = !_hasAcsEntry & !_hasIncompleteReassignments
                        alreadyArchived = !_hasAcsEntry & _hasIncompleteReassignments
                        _ <-
                          if (filteredOut) {
                            summary.numFilteredUnassignEvents += 1
                            DBIO.successful(())
                          } else if (alreadyArchived) {
                            doRegisterIncompleteReassignment(
                              unassign.contractId.contractId,
                              unassign.source,
                              unassign.unassignId,
                              false,
                              summary,
                            )
                          } else {
                            DBIO.seq(
                              doSetContractStateInFlight(
                                unassign,
                                summary,
                              ),
                              doRegisterIncompleteReassignment(
                                unassign.contractId.contractId,
                                unassign.source,
                                unassign.unassignId,
                                false,
                                summary,
                              ),
                            )
                          }
                      } yield ()
                  }
                ),
            ),
            "ingestReassignment",
          )
      } yield summary
    }

    private def ingestTransactionTree(
        domainId: DomainId,
        tree: TransactionTree,
        treeProto: TransactionOuterClass.TransactionTree,
    )(implicit tc: TraceContext): Future[MutableIngestionSummary[TXE]] = {
      val summary = MutableIngestionSummary.empty[TXE]

      val workTodo = Trees
        .foldTree(
          tree,
          VectorMap.empty[String, OperationToDo],
        )(
          onCreate = (st, ev, _) => {
            if (contractFilter.contains(ev)) {
              contractFilter.ensureStakeholderOf(ev)
              st + (ev.getContractId -> Insert(
                ev,
                treeProto.getEventsByIdOrThrow(ev.getEventId).getCreated.getCreatedEventBlob,
              ))
            } else {
              summary.numFilteredCreatedEvents += 1
              st
            }
          },
          onExercise = (st, ev, _) => {
            if (ev.isConsuming && contractFilter.mightContain(ev.getTemplateId)) {
              // optimization: a delete on a contract cancels-out with the corresponding insert
              if (st.contains(ev.getContractId)) {
                st - ev.getContractId
              } else {
                st + (ev.getContractId -> Delete(ev))
              }
            } else {
              st
            }
          },
        )
        .toVector
        .map(_._2)
      val txLogEntries = txLogParser.parse(tree, domainId, logger)

      for {
        _ <- storage
          .queryAndUpdate(
            ingestUpdateAtOffset(
              tree.getOffset,
              DBIO
                .sequence(
                  // TODO (#5643): batch inserts
                  workTodo.map {
                    case Insert(createdEvent, createdEventBlob) =>
                      for {
                        alreadyArchived <- hasIncompleteReassignments(createdEvent.getContractId)
                        _ <-
                          if (alreadyArchived) {
                            DBIO.successful(())
                          } else {
                            DBIO.seq(
                              doIngestAcsInsert(
                                tree.getOffset,
                                createdEvent,
                                createdEventBlob,
                                stateRowDataFromActiveContract(domainId, 0L),
                                summary,
                              )
                            )
                          }
                      } yield ()
                    case Delete(exercisedEvent) =>
                      doDeleteContract(exercisedEvent, summary)
                  }
                    ++ txLogEntries.map(txe => doIngestTxLogInsert(tree.getOffset, txe, summary))
                ),
            ),
            "ingestTransactionTree",
          )
      } yield summary
    }

    private def hasAcsEntry(contractId: String) = (sql"""
           select count(*) from #$acsTableName
           where store_id = $storeId and contract_id = ${lengthLimited(contractId)}
          """).as[Int].head.map(_ > 0)

    private def hasIncompleteReassignments(contractId: String) = (sql"""
           select count(*) from incomplete_reassignments
           where store_id = $storeId and contract_id = ${lengthLimited(contractId)}
          """).as[Int].head.map(_ > 0)

    private def stateRowDataFromActiveContract(
        domainId: DomainId,
        reassignmentCounter: Long,
    ) = ContractStateRowData(
      assignedDomain = Some(domainId),
      reassignmentCounter = reassignmentCounter,
      reassignmentTargetDomain = None,
      reassignmentSourceDomain = None,
      reassignmentSubmitter = None,
      reassignmentUnassignId = None,
    )

    private def stateRowDataFromAssign(
        event: ReassignmentEvent.Assign
    ) = ContractStateRowData(
      assignedDomain = Some(event.target),
      reassignmentCounter = event.counter,
      reassignmentTargetDomain = None,
      reassignmentSourceDomain = None,
      reassignmentSubmitter = None,
      reassignmentUnassignId = None,
    )

    private def stateRowDataFromUnassign(
        event: ReassignmentEvent.Unassign
    ) = ContractStateRowData(
      assignedDomain = None,
      reassignmentCounter = event.counter,
      reassignmentTargetDomain = Some(event.target),
      reassignmentSourceDomain = Some(event.source),
      reassignmentSubmitter = Some(event.submitter),
      reassignmentUnassignId = Some(String255.tryCreate(event.unassignId)),
    )

    private def doIngestAcsInsert(
        offset: String,
        createdEvent: CreatedEvent,
        createdEventBlob: ByteString,
        stateData: ContractStateRowData,
        summary: MutableIngestionSummary[TXE],
    )(implicit
        tc: TraceContext
    ) = {
      contractFilter.matchingContractToRow(createdEvent, createdEventBlob) match {
        case None =>
          val errMsg =
            s"Item at offset $offset with contract id ${createdEvent.getContractId} cannot be ingested."
          logger.error(errMsg)
          throw new IllegalArgumentException(errMsg)
        case Some(rowData) =>
          summary.ingestedCreatedEvents.addOne(createdEvent)

          val contract = rowData.contract
          val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
          val templateId = contract.identifier
          val templateIdQualifiedName = QualifiedName(templateId)
          val templateIdPackageId = lengthLimited(contract.identifier.getPackageId)
          val createArguments = payloadJsonFromContract(contract.payload)
          val createdAt = Timestamp.assertFromInstant(contract.createdAt)
          val contractExpiresAt = rowData.contractExpiresAt
          val ContractStateRowData(
            assignedDomain,
            reassignmentCounter,
            reassignmentTargetDomain,
            reassignmentSourceDomain,
            reassignmentSubmitter,
            reassignmentUnassignId,
          ) = stateData

          // the column names are hardcoded so they're safe to interpolate raw
          val indexColumnNames =
            if (rowData.indexColumns.isEmpty) ""
            else rowData.indexColumns.map(_._1).mkString(",", ", ", "")

          val indexColumnNameValues = rowData.indexColumns
            .map(_._2)
            .map(v => sql"$v")
            .reduceOption { (acc, next) =>
              (acc ++ sql"," ++ next).toActionBuilder
            }
            .map(s => (sql"," ++ s).toActionBuilder)
            .getOrElse(sql"")

          import storage.DbStorageConverters.setParameterByteArray
          (sql"""
                insert into #$acsTableName(store_id, contract_id, template_id_package_id, template_id_qualified_name,
                                           create_arguments, created_event_blob, created_at, contract_expires_at,
                                           assigned_domain, reassignment_counter, reassignment_target_domain,
                                           reassignment_source_domain, reassignment_submitter, reassignment_unassign_id
                                           #$indexColumnNames)
                values ($storeId, $contractId, $templateIdPackageId, $templateIdQualifiedName,
                        $createArguments, $createdEventBlob, $createdAt, $contractExpiresAt,
                        $assignedDomain, $reassignmentCounter, $reassignmentTargetDomain,
                        $reassignmentSourceDomain, $reassignmentSubmitter, $reassignmentUnassignId
              """ ++ indexColumnNameValues ++ sql") on conflict do nothing").toActionBuilder.asUpdate
      }
    }

    private def doIngestTxLogInsert(
        offset: String,
        txe: TXE,
        summary: MutableIngestionSummary[TXE],
    )(implicit
        tc: TraceContext
    ) = {
      ingestTxLogInsert(txe.indexRecord, tc) match {
        case Left(err) =>
          val errMsg =
            s"Tx at offset $offset with event id ${txe.indexRecord.eventId} cannot be ingested: $err"
          logger.error(errMsg)
          throw new IllegalArgumentException(errMsg)
        case Right(action) =>
          summary.ingestedTxLogEntries.addOne(txe)
          action
      }
    }

    private def doDeleteContract(event: ExercisedEvent, summary: MutableIngestionSummary[TXE]) = {
      sqlu"""
        delete from #$acsTableName
        where store_id = $storeId
          and contract_id = ${lengthLimited(event.getContractId)}
      """.map {
        case 1 =>
          summary.ingestedArchivedEvents.addOne(event)
        case _ =>
          // there was actually no contract with that id. This can happen because:
          // `contractFilter.mightContain` in `getIngestionWork` can return true for a template,
          // but that might still satisfy some other filter, so the contract was never inserted
          summary.numFilteredArchivedEvents += 1
      }
    }

    private def doSetContractStateInFlight(
        event: ReassignmentEvent.Unassign,
        summary: MutableIngestionSummary[TXE],
    ) = {
      val safeUnassignId = lengthLimited(event.unassignId)
      summary.updatedContractStates.addOne(
        ContractStateEvent(
          event.contractId,
          event.counter,
          StoreContractState.InFlight(event),
        )
      )
      // Only overwrite the current contract state if existing row is "older", i.e., it has
      // a reassignment counter smaller than the state we are trying to insert.
      // Note: reassignment counters increase with Unassign events. Corresponding Assign and Unassign events
      // have the same reassignment counter.
      sqlu"""
        update #$acsTableName
            set
                state_number = default, -- generates a new identity value
                assigned_domain = NULL,
                reassignment_counter = ${event.counter},
                reassignment_target_domain = ${event.target},
                reassignment_source_domain = ${event.source},
                reassignment_submitter = ${event.submitter},
                reassignment_unassign_id = $safeUnassignId
            where
                store_id = $storeId and contract_id = ${event.contractId} and
                #$acsTableName.reassignment_counter < ${event.counter}
      """
    }

    private def doSetContractStateActive(
        contractId: String,
        domainId: DomainId,
        reassignmentCounter: Long,
        summary: MutableIngestionSummary[TXE],
    ) = {
      val safeContractId = lengthLimited(contractId)
      summary.updatedContractStates.addOne(
        ContractStateEvent(
          new ContractId(contractId),
          reassignmentCounter,
          StoreContractState.Assigned(domainId),
        )
      )
      // Only overwrite the current contract state if existing row is "older", i.e., it has
      // a reassignment counter smaller or equal to the state we are trying to insert.
      // Note: reassignment counters increase with Unassign events. Corresponding Assign and Unassign events
      // have the same reassignment counter.
      sqlu"""
        update #$acsTableName
            set
                state_number = default, -- generates a new identity value
                assigned_domain = $domainId,
                reassignment_counter = $reassignmentCounter,
                reassignment_target_domain = NULL,
                reassignment_source_domain = NULL,
                reassignment_submitter = NULL,
                reassignment_unassign_id = NULL
            where
                store_id = $storeId and contract_id = $safeContractId and
                #$acsTableName.reassignment_counter <= $reassignmentCounter
      """
    }

    private def doRegisterIncompleteReassignment(
        contractId: String,
        source: DomainId,
        unassignId: String,
        isAssignment: Boolean,
        summary: MutableIngestionSummary[TXE],
    ) = {
      val safeContractId = lengthLimited(contractId)
      val safeUnassignId = lengthLimited(unassignId)
      // If there is a matching "unassign" row, remove it (the reassignment is now complete).
      // Otherwise, add a new "assign" row (register the incomplete reassignment)
      sql"""
        select count(*) from incomplete_reassignments
        where store_id = $storeId and contract_id = $safeContractId and unassign_id = $safeUnassignId and is_assignment = ${!isAssignment}
          """
        .as[Int]
        .head
        .flatMap(existingUnassignRows => {
          if (existingUnassignRows > 0) {
            if (isAssignment) {
              summary.removedAssignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            } else {
              summary.removedUnassignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            }
            sqlu"""
            delete from incomplete_reassignments
            where store_id = $storeId and contract_id = $safeContractId and unassign_id = $safeUnassignId and is_assignment = ${!isAssignment}
              """
          } else {
            if (isAssignment) {
              summary.addedAssignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            } else {
              summary.addedUnassignEvents
                .addOne(new ContractId(contractId) -> ReassignmentId(source, unassignId))
            }
            sqlu"""
            insert into incomplete_reassignments(store_id, contract_id, source_domain, unassign_id, is_assignment)
            values ($storeId, $safeContractId, $source, $safeUnassignId, $isAssignment)
            on conflict do nothing
              """
          }
        })
    }

    sealed trait OperationToDo
    case class Insert(evt: CreatedEvent, createdEventBlob: ByteString) extends OperationToDo
    case class Delete(evt: ExercisedEvent) extends OperationToDo
  }

  override def close(): Unit = ()

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  private def reassignmentEventUnassignFromRow(
      row: SelectFromAcsTableWithStateResult
  ): ReassignmentEvent.Unassign = {
    assert(row.stateRow.assignedDomain.isEmpty)
    ReassignmentEvent.Unassign(
      submitter = row.stateRow.reassignmentSubmitter.get,
      source = row.stateRow.reassignmentSourceDomain.get,
      target = row.stateRow.reassignmentTargetDomain.get,
      unassignId = row.stateRow.reassignmentUnassignId.get,
      contractId = row.acsRow.contractId,
      counter = row.stateRow.reassignmentCounter,
    )
  }

  /* Returns the first `limit` TxLog entries that were inserted after the entry
     with the given event id, in reverse insertion order (newest first). */
  def getTxLogEventsInReverseOrder(
      beginAfterEventIdO: Option[String],
      limit: Int,
  )(implicit lc: TraceContext): Future[Seq[TxLogEvent]] = {
    for {
      eventIds <- storage
        .query(
          beginAfterEventIdO.fold(
            sql"""
                select event_id, domain_id, acs_contract_id
                from #${txLogTableName}
                where store_id = $storeId
                order by entry_number desc
                limit $limit
              """.as[(String, DomainId, Option[ContractId[Any]])]
          )(beginAfterEventId => sql"""
                select event_id, domain_id, acs_contract_id
                from #${txLogTableName}
                where store_id = $storeId
                  and entry_number < (
                      select entry_number
                      from #${txLogTableName}
                      where store_id = $storeId
                      and event_id = ${lengthLimited(beginAfterEventId)}
                  )
                order by entry_number desc
                limit $limit
              """.as[(String, DomainId, Option[ContractId[Any]])]),
          "getTxLogEventIdsInReverseOrder",
        )
    } yield eventIds.map((TxLogEvent.apply _).tupled)
  }

  override def getJsonAcsSnapshot(
      ignoredContracts: Set[QualifiedName]
  )(implicit tc: TraceContext): Future[JsonAcsSnapshot] = {
    val qualifiedNamesSql = ignoredContracts
      .map(q => sql"$q")
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      }
      .getOrElse(sql"")

    waitUntilAcsIngested {
      for {
        rows <- storage.query(
          selectFromAcsTableWithOffset(
            acsTableName,
            storeId,
            (sql"template_id_qualified_name not in (" ++ qualifiedNamesSql ++ sql")").toActionBuilder,
          ),
          "getJsonAcsSnapshot",
        )
        offset <- OptionT
          .fromOption[Future](rows.headOption)
          .getOrRaise(offsetExpectedError())
          .map(_.offset)
        contracts <- rows.flatMap(_.row).traverse { row =>
          OptionT
            .fromOption[Future](contractFilter.decodeMatchingContractFromRow(row))
            .getOrRaise(
              new IllegalStateException("Stored a contract that is not in the contract filter.")
            )
        }
      } yield JsonAcsSnapshot(offset, contracts)
    }
  }

}

object DbMultiDomainAcsStore {

  /** @param storeId The primary key of this stores entry in the store_descriptors table
    * @param offset The last ingested offset, if any
    * @param acsSize The number of active contracts in the store
    * @param offsetChanged A promise that is not yet completed, and will be completed the next time the offset changes
    * @param offsetIngestionsToSignal A map from offsets to promises. The keys are offsets that are not ingested yet.
    *                                 The values are promises that are not completed, and will be completed when
    *                                 the corresponding offset is ingested.
    */
  private case class State(
      storeId: Option[Int],
      offset: Option[String],
      acsSize: Int,
      offsetChanged: Promise[Unit],
      offsetIngestionsToSignal: SortedMap[String, Promise[Unit]],
  ) {
    def withInitialState(
        storeId: Int,
        acsSizeInDb: Int,
        lastIngestedOffset: Option[String],
    ): State = {
      assert(
        !offset.exists(inMemoryOffset =>
          lastIngestedOffset.exists(dbOffset => inMemoryOffset > dbOffset)
        ),
        "Cached offset was newer than offset stored in the database",
      )
      val nextOffsetChanged = if (offset == lastIngestedOffset) offsetChanged else Promise[Unit]()
      this.copy(
        storeId = Some(storeId),
        acsSize = acsSizeInDb,
        offset = lastIngestedOffset,
        offsetChanged = nextOffsetChanged,
      )
    }

    def withUpdate(newAcsSize: Int, newOffset: String): State = {
      val nextOffsetChanged = if (offset.contains(newOffset)) offsetChanged else Promise[Unit]()
      this.copy(
        acsSize = newAcsSize,
        offset = Some(newOffset),
        offsetChanged = nextOffsetChanged,
        offsetIngestionsToSignal = offsetIngestionsToSignal.filter { case (offsetToSignal, _) =>
          offsetToSignal > newOffset
        },
      )
    }

    def signalOffsetChanged(newOffset: String): Unit = {
      if (!offset.contains(newOffset)) {
        offsetChanged.success(())
        offsetIngestionsToSignal.foreach { case (offsetToSignal, promise) =>
          if (offsetToSignal <= newOffset) {
            promise.success(())
          }
        }
      }
    }

    /** Update the state by adding another offset whose ingestion should be signalled. If the signalling of that
      * offset has already been requested, don't change the state.
      */
    def withOffsetToSignal(
        offsetToSignal: String
    ): State = {
      if (offset.exists(_ >= offsetToSignal)) {
        this
      } else {
        offsetIngestionsToSignal.get(offsetToSignal) match {
          case None =>
            val p = Promise[Unit]()
            copy(
              offsetIngestionsToSignal = offsetIngestionsToSignal + (offsetToSignal -> p)
            )
          case Some(_) => this
        }
      }
    }
  }
  private object State {
    def empty(): State = State(
      storeId = None,
      offset = None,
      acsSize = 0,
      offsetChanged = Promise(),
      offsetIngestionsToSignal = SortedMap.empty,
    )
  }

  case class TxLogEvent(eventId: String, domainId: DomainId, acsContractId: Option[ContractId[?]])

  /** Like [[IngestionSummary]], but with all fields mutable to simplify collecting the content from helper methods */
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  case class MutableIngestionSummary[TXE <: TxLogStore.Entry[?]](
      ingestedCreatedEvents: mutable.ArrayBuffer[CreatedEvent],
      var numFilteredCreatedEvents: Int,
      ingestedArchivedEvents: mutable.ArrayBuffer[ExercisedEvent],
      var numFilteredArchivedEvents: Int,
      updatedContractStates: mutable.ArrayBuffer[ContractStateEvent],
      addedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      var numFilteredAssignEvents: Int,
      removedAssignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      addedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      var numFilteredUnassignEvents: Int,
      removedUnassignEvents: mutable.ArrayBuffer[(ContractId[?], ReassignmentId)],
      prunedContracts: mutable.ArrayBuffer[ContractId[?]],
      ingestedTxLogEntries: mutable.ArrayBuffer[TXE],
  ) {
    def acsSizeDiff: Int = ingestedCreatedEvents.size - ingestedArchivedEvents.size

    def toIngestionSummary(
        txId: Option[String],
        offset: String,
        newAcsSize: Int,
    ): IngestionSummary[TXE] = IngestionSummary(
      txId = txId,
      offset = Some(offset),
      newAcsSize = newAcsSize,
      ingestedCreatedEvents = this.ingestedCreatedEvents.toVector,
      numFilteredCreatedEvents = this.numFilteredCreatedEvents,
      ingestedArchivedEvents = this.ingestedArchivedEvents.toVector,
      numFilteredArchivedEvents = this.numFilteredArchivedEvents,
      updatedContractStates = this.updatedContractStates.toVector,
      addedAssignEvents = this.addedAssignEvents.toVector,
      numFilteredAssignEvents = this.numFilteredAssignEvents,
      removedAssignEvents = this.removedAssignEvents.toVector,
      addedUnassignEvents = this.addedUnassignEvents.toVector,
      numFilteredUnassignEvents = this.numFilteredUnassignEvents,
      removedUnassignEvents = this.removedUnassignEvents.toVector,
      prunedContracts = Vector.empty,
      ingestedTxLogEntries = this.ingestedTxLogEntries.toSeq,
    )
  }

  object MutableIngestionSummary {
    def empty[TXE <: TxLogStore.Entry[?]]: MutableIngestionSummary[TXE] = MutableIngestionSummary(
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      0,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
      mutable.ArrayBuffer.empty,
    )
  }
}
