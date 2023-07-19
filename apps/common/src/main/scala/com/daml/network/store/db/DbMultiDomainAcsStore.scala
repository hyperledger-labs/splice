package com.daml.network.store.db

import akka.NotUsed
import akka.stream.scaladsl.Source
import cats.implicits.*
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier, Template}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  IncompleteTransferEvent,
  TransactionTreeUpdate,
  TransferEvent,
  TransferUpdate,
  TreeUpdate,
}
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.*
import com.daml.network.util.{
  Contract,
  ContractWithState,
  ReadyContract,
  TemplateJsonDecoder,
  Trees,
}
import com.digitalasset.canton.config.CantonRequireTypes.String256M
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.unused
import scala.collection.immutable.{SortedMap, VectorMap}
import scala.concurrent.{ExecutionContext, Future, Promise}
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import slick.dbio.{DBIO, DBIOAction, Effect, NoStream}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    storage: DbStorage,
    acsTableName: String,
    txLogTableName: String,
    storeDescriptor: io.circe.Json,
    resolveDomainId: TraceContext => Future[DomainId], // no support for multi-domain yet
    override protected val loggerFactory: NamedLoggerFactory,
    contractFilter: MultiDomainAcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    @unused retryProvider: RetryProvider,
    ingestAcsInsert: (CreatedEvent, TraceContext) => Either[String, slick.dbio.DBIO[?]],
    ingestTxLogInsert: (TXI, TraceContext) => Either[String, slick.dbio.DBIO[?]],
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with AcsTables
    with AcsQueries
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

  def lastIngestedOffset(
      storage: DbStorage
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
      closeContext: CloseContext,
  ): Future[String] = waitUntilAcsIngested {
    storage
      .querySingle(
        sql"""
              select last_ingested_offset from store_descriptors
              where id = ${storeId}
           """.as[String].headOption,
        "minimumLastOffset",
      )
      .value
      .map(
        _.getOrElse(
          throw new IllegalStateException("Offset must be defined, as the ACS was ingested")
        )
      )
  }

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        (selectFromAcsTable(acsTableName) ++
          sql"""
          where store_id = $storeId
            and contract_id = ${lengthLimited(id.contractId)}
           """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
        "lookupContractById",
      )
      .semiflatMap(contractWithStateFromRow(companion)(_))
      .value
  }

  override def lookupContractStateById(id: ContractId[?])(implicit
      traceContext: TraceContext
  ): Future[Option[ContractState]] = waitUntilAcsIngested {
    storage
      .querySingle( // index: acs_store_template_sid_cid
        (selectFromAcsTable(acsTableName) ++
          sql"""
            where store_id = $storeId
              and contract_id = ${lengthLimited(id.contractId)}
             """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
        "lookupContractStateById",
      )
      .semiflatMap(contractStateFromRow(_))
      .value
  }

  def allKnownAndNotArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean] = {
    // TODO(#6458): implement this as a single DB query
    Future.sequence(ids.map(lookupContractStateById)).map(_.forall(_.isDefined))
  }

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
        (selectFromAcsTable(acsTableName) ++
          sql"""
          where store_id = $storeId
            and template_id = $templateId
          order by event_number
          limit ${sqlLimit(limit)}
           """).toActionBuilder.as[AcsStoreRowTemplate],
        "listContracts",
      )
      limited = applyLimit(limit, result)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
    } yield withState
  }

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ReadyContract[TCid, T]]] = waitUntilAcsIngested {
    // TODO (#5314): do a query instead of in-memory filtering via toReadyContract
    listContracts(companion, limit).map(_.flatMap(_.toReadyContract))
  }

  // TODO (#3822) `expiresAt` is unused here, but necessary for the in-memory version to work.
  // With an ExpiredContract interface it can be dropped from both.
  override private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[
    T
  ], T <: Template](companion: C)(expiresAt: T => Instant)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): ListExpiredContracts[TCid, T] = { (now, rawLimit) => implicit traceContext =>
    val limit = PageLimit(rawLimit.toLong)
    val templateId = companionClass.typeId(companion)
    for {
      _ <- waitUntilAcsIngested()
      result <- storage
        .query( // index: acs_store_template_sid_tid_ce
          (selectFromAcsTable(acsTableName) ++
            sql"""
          where store_id = $storeId
            and template_id = $templateId
            and contract_expires_at < $now
          order by event_number
          limit ${sqlLimit(limit)}
           """).toActionBuilder.as[AcsStoreRowTemplate],
          "listExpiredFromPayloadExpiry",
        )
      limited = applyLimit(limit, result)
      withState <- limited.traverse(contractWithStateFromRow(companion)(_))
      // TODO (#5314): adjust the query instead of in-memory filtering via toReadyContract
    } yield withState.flatMap(_.toReadyContract)
  }

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]] = waitUntilAcsIngested {
    // TODO (#5314): the DbMultiDomainAcsStore is currently tied to a single domain,
    //  so this method doesn't make that much sense atm
    resolveDomainId(traceContext).flatMap { thisDomain =>
      if (thisDomain != domain) {
        logger.warn(
          "Tried to list contracts on domain {} but this DB ACS Store is for domain {}.",
          domain,
          thisDomain,
        )
        Future.successful(Seq.empty)
      } else {
        listContracts(companion, limit).map(_.map(_.contract))
      }
    }
  }

  override def streamReadyContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    streamReadyContracts(companion, PageLimit(100L))
  }

  def streamReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      pageSize: PageLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[ReadyContract[TCid, T], NotUsed] = {
    val templateId = companionClass.typeId(companion)
    Source
      .future(
        // TODO(#5534): this is currently waiting until the whole ACS has been ingested.
        //  After switching to streaming ACS ingestion, we could start streaming contracts while
        //  the ACS is being ingested.
        waitUntilAcsIngested(resolveDomainId(traceContext))
      ) // TODO (#5314): this probably won't apply anymore
      .flatMapConcat { domainId =>
        Source
          .unfoldAsync(0L) { fromEventNumber =>
            val offsetPromise = state.get().offsetChanged
            storage
              .query( // index: acs_store_template_sid_tid_en
                (selectFromAcsTable(acsTableName) ++
                  sql"""
                    where store_id = $storeId
                      and template_id = $templateId
                      and event_number >= $fromEventNumber
                    order by event_number
                    limit ${sqlLimit(pageSize)}
                     """).toActionBuilder.as[AcsStoreRowTemplate],
                "streamReadyContracts",
              )
              .flatMap { rows =>
                rows.lastOption.map(_.eventNumber) match {
                  case Some(lastEventNumber) =>
                    Future.successful(
                      (
                        lastEventNumber + 1,
                        rows.map(row => ReadyContract(contractFromRow(companion)(row), domainId)),
                      )
                    )
                  case None =>
                    // to avoid polling the DB, we wait for a new offset to have been ingested
                    offsetPromise.future.map(_ => fromEventNumber -> Vector.empty)
                }
              }
              .map(Some(_))
          }
      }
      .mapConcat(identity)
  }

  override def streamReadyForTransferIn(): Source[TransferEvent.Out, NotUsed] = ???

  override def isReadyForTransferIn(contractId: ContractId[_], out: TransferId): Future[Boolean] =
    ???

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
           """.asUpdate.map(_ => ())

    override def ingestAcs(
        offset: String,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteTransferEvent.Out],
        incompleteIn: Seq[IncompleteTransferEvent.In],
    )(implicit traceContext: TraceContext): Future[Unit] = {
      assert(
        finishedAcsIngestion.isCompleted == false,
        s"ACS was already ingested for store $storeId",
      )
      for {
        // TODO(#6547): Get initial txLogEntries from txLogParser.parseAcs()
        txLogEntries <- Future.successful(Seq.empty)
        // TODO(#5643): batch inserts
        toInsert = acs
          .filter(contract => contractFilter.contains(contract.createdEvent))
          .map(contract => contract.createdEvent.getContractId -> Insert(contract.createdEvent))
        workTodo = WorkTodo(
          None,
          offset,
          VectorMap.from(toInsert),
          numFilteredCreatedEvents = acs.size - toInsert.size,
        )
        _ <- ingestWork(workTodo, txLogEntries)
      } yield {
        state
          .getAndUpdate(
            _.withUpdate(acs.size, offset)
          )
          .signalOffsetChanged(offset)

        val summary =
          WorkDone(None, offset, acs.map(_.createdEvent).toVector, Vector.empty, 0)
            .toSummary(workTodo, Vector.empty)
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
      for {
        (todo, txEntries) <- getIngestionWork(transfer)
        workDone <- ingestWork(todo, txEntries)
      } yield {
        state
          .getAndUpdate(s =>
            s.withUpdate(
              s.acsSize + workDone.inserts.size - workDone.deletes.size,
              workDone.offset,
            )
          )
          .signalOffsetChanged(workDone.offset)

        val summary = workDone.toSummary(todo, txEntries)
        logger.debug(show"Ingested transaction $summary")
      }
    }

    private def getIngestionWork(
        transfer: TreeUpdate
    )(implicit tc: TraceContext): Future[(WorkTodo, Seq[TXE])] = {
      transfer match {
        case TransferUpdate(_) =>
          // TODO (#5314): support transfers
          Future.failed(new UnsupportedOperationException("Transfers are unsupported."))
        case TransactionTreeUpdate(tree) =>
          Future {
            val workTodo = Trees.foldTree(
              tree,
              WorkTodo(
                Some(tree.getTransactionId),
                tree.getOffset,
                VectorMap.empty,
                numFilteredCreatedEvents = 0,
              ),
            )(
              onCreate = (st, ev, _) => {
                if (contractFilter.contains(ev)) {
                  st.copy(todo = st.todo + (ev.getContractId -> Insert(ev)))
                } else {
                  st.copy(numFilteredCreatedEvents = st.numFilteredCreatedEvents + 1)
                }
              },
              onExercise = (st, ev, _) => {
                if (ev.isConsuming && contractFilter.mightContain(ev.getTemplateId)) {
                  // optimization: a delete on a contract cancels-out with the corresponding insert
                  if (st.todo.contains(ev.getContractId)) {
                    st.copy(todo = st.todo - ev.getContractId)
                  } else {
                    st.copy(todo = st.todo + (ev.getContractId -> Delete(ev)))
                  }
                } else {
                  st
                }
              },
            )
            (
              workTodo,
              txLogParser.parse(tree, logger),
            )
          }
      }
    }

    private def ingestWork(workTodo: WorkTodo, txEntries: Seq[TXE])(implicit
        tc: TraceContext
    ): Future[WorkDone] = {
      storage
        .queryAndUpdate(
          DBIO
            .sequence(
              // TODO (#5643): batch inserts
              workTodo.todo.toVector.map {
                case (_, insert @ Insert(evt)) =>
                  ingestAcsInsert(evt, tc) match {
                    case Left(err) =>
                      val errMsg =
                        s"Item at offset ${workTodo.offset} with contract id ${evt.getContractId} cannot be ingested: $err"
                      logger.error(errMsg)
                      throw new IllegalArgumentException(errMsg)
                    case Right(action) =>
                      action.map(_ => Some(insert))
                  }
                case (_, delete @ Delete(exerciseEvent)) =>
                  val contractId = exerciseEvent.getContractId
                  sqlu"""
                         delete from #$acsTableName
                         where store_id = $storeId
                           and contract_id = ${lengthLimited(contractId)}
                        """.map {
                    case 1 =>
                      Some(delete)
                    case _ =>
                      // there was actually no contract with that id. This can happen because:
                      // `contractFilter.mightContain` in `getIngestionWork` can return true for a template,
                      // but that might still satisfy some other filter, so the contract was never inserted
                      Option.empty[OperationToDo]
                  }
              } ++ txEntries.map(txe =>
                ingestTxLogInsert(txe.indexRecord, tc) match {
                  case Left(err) =>
                    val errMsg =
                      s"Tx at offset ${workTodo.offset} with event id ${txe.indexRecord.eventId} cannot be ingested: $err"
                    logger.error(errMsg)
                    throw new IllegalArgumentException(errMsg)
                  case Right(action) =>
                    action.map(_ => Option.empty[OperationToDo])
                }
              ) :+ updateOffset(workTodo.offset).map(_ => Option.empty[OperationToDo])
            )
            .transactionally
            .map { operationsDone =>
              val (deletes, inserts) = operationsDone.flatten.partitionMap {
                case Delete(evt) => Left(evt)
                case Insert(evt) => Right(evt)
              }
              WorkDone(
                workTodo.txId,
                workTodo.offset,
                inserts,
                deletes,
                workTodo.numFilteredCreatedEvents,
              )
            },
          "ingest tree",
        )
    }

    sealed trait OperationToDo
    case class Insert(evt: CreatedEvent) extends OperationToDo
    case class Delete(evt: ExercisedEvent) extends OperationToDo
    case class WorkTodo(
        txId: Option[String],
        offset: String,
        todo: VectorMap[String, OperationToDo],
        numFilteredCreatedEvents: Int,
    )

    case class WorkDone(
        txId: Option[String],
        offset: String,
        inserts: Vector[CreatedEvent],
        deletes: Vector[ExercisedEvent],
        numFilteredCreatedEvents: Int,
    ) {
      def toSummary(
          workTodo: WorkTodo,
          txEntries: Seq[TXE],
      ): IngestionSummary[TXE] = {
        val numDeletesWanted = workTodo.todo.count {
          case (_, _: Delete) => true
          case _ => false
        }
        val numDeletesDone = deletes.size
        IngestionSummary(
          txId = txId,
          offset = Some(offset),
          // Note: accessing shared mutable state here, but it's fine since all operations that
          // modify the ACS size are executed sequentially.
          newAcsSize = state.get().acsSize,
          ingestedCreatedEvents = inserts,
          numFilteredCreatedEvents = numFilteredCreatedEvents,
          ingestedArchivedEvents = deletes,
          ingestedTxLogEntries = txEntries,
          numFilteredArchivedEvents = numDeletesWanted - numDeletesDone,
          // TODO (#5314): all these below are multi-domain
          updatedContractStates = Vector.empty,
          addedTransferInEvents = Vector.empty,
          numFilteredTransferInEvents = 0,
          removedTransferInEvents = Vector.empty,
          addedTransferOutEvents = Vector.empty,
          numFilteredTransferOutEvents = 0,
          removedTransferOutEvents = Vector.empty,
          prunedContracts = Vector.empty,
        )
      }
    }
  }

  override def close(): Unit = ()

  def contractWithStateFromRow[C, TCid <: ContractId[_], T](companion: C)(
      row: AcsStoreRowTemplate
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[ContractWithState[TCid, T]] = {
    contractStateFromRow(row).map { state =>
      val contract = contractFromRow(companion)(row)
      ContractWithState(contract, state)
    }
  }

  @annotation.nowarn(
    "cat=unused&msg=value row.*is never used"
  ) // TODO (#5314) domain must come from row
  private def contractStateFromRow(
      row: AcsStoreRowTemplate
  )(implicit traceContext: TraceContext): Future[ContractState] =
    resolveDomainId(traceContext).map { domainId =>
      // TODO (#5314): handle InFlight
      ContractState.Assigned(domainId)
    }

  /* Returns the event ids of the first `limit` TxLog entries that were inserted after the entry
     with the given event id, in reverse insertion order (newest first). */
  def getTxLogEventIdsInReverseOrder(
      beginAfterEventIdO: Option[String],
      limit: Int,
  )(implicit lc: TraceContext): Future[Seq[String]] = {
    for {
      eventIds <- storage
        .query(
          beginAfterEventIdO.fold(
            sql"""
                select event_id
                from #${txLogTableName}
                where store_id = $storeId
                order by entry_number desc
                limit $limit
              """.as[String]
          )(beginAfterEventId => sql"""
                select event_id
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
              """.as[String]),
          "getTxLogEventIdsInReverseOrder",
        )
    } yield eventIds
  }

  override def getJsonAcsSnapshot(ignoredContracts: Set[Identifier]): Future[JsonAcsSnapshot] =
    // TODO(#6400): implement snapshot reading for the DB store
    ???

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
}
