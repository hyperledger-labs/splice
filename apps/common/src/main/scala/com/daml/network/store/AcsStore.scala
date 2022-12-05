package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.{
  Contract,
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
}
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  Filter,
  FiltersByParty,
  Identifier,
  InclusiveFilter,
  Template,
  Transaction,
  TransactionFilter,
}
import com.daml.network.util.JavaContract
import com.daml.network.util.PrettyInstances.PrettyContractId
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A store for querying active contract sets.
  *
  * Only events contained in the store's [[com.daml.network.store.AcsStore.ContractFilter]] are ingested,
  * which serves to pre-filter contracts and thereby simplifies queries against the store.
  *
  * All functions that return a [[com.daml.network.store.AcsStore.QueryResult]] are logically
  * computed against a snapshot of the ledger's ACS. They also return the offset of that snapshot,
  * which can be used as a deduplication offset in command deduplication.
  */
trait AcsStore extends AutoCloseable {
  import AcsStore.*

  /** Defines which create events are to be ingested into the store. */
  def contractFilter: ContractFilter

  /** Lookup a contract by id. */
  def lookupContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]): Future[QueryResult[Option[JavaContract[TCid, T]]]]

  /** Lookup a contract's interface view by id. */
  def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id): Future[QueryResult[Option[JavaContract[Id, View]]]]

  /** Get a contract by id.
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  def getContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T])(implicit ec: ExecutionContext): Future[QueryResult[JavaContract[TCid, T]]] =
    lookupContractById(templateCompanion)(id).map(result =>
      result.map(
        _.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              PrettyContractId(templateCompanion.TEMPLATE_ID, id).toString
            )
            .asRuntimeException
        )
      )
    )

  /** Get a contract's interface view by id.
    *
    * Throws [[Status.NOT_FOUND]] if no such contract view exists.
    */
  def getContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id)(implicit ec: ExecutionContext): Future[QueryResult[JavaContract[Id, View]]] =
    lookupContractById(interfaceCompanion)(id).map(result =>
      result.map(
        _.getOrElse(
          throw Status.NOT_FOUND
            .withDescription(
              PrettyContractId(interfaceCompanion.TEMPLATE_ID, id).toString
            )
            .asRuntimeException
        )
      )
    )

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContract[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(p: JavaContract[TCid, T] => Boolean): Future[QueryResult[Option[JavaContract[TCid, T]]]]

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContract[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean): Future[QueryResult[Option[JavaContract[Id, View]]]]

  /** List all active contracts of the given template.
    *
    * Beware that for the in-memory implementation, this method iterates through all the created events in the store.
    * TODO(M3-83): add indexes for ^.
    */
  // TODO(M3-83): add a limit parameter
  def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: JavaContract[TCid, T] => Boolean,
  ): Future[QueryResult[Seq[JavaContract[TCid, T]]]]

  /** List all active contracts of the given template. */
  def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Future[QueryResult[Seq[JavaContract[TCid, T]]]] =
    listContracts(templateCompanion, _ => true)

  /** List all active contracts of the given template. */
  def listContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: JavaContract[Id, View] => Boolean,
  ): Future[QueryResult[Seq[JavaContract[Id, View]]]]

  /** List all active contracts of the given template. */
  def listContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Future[QueryResult[Seq[JavaContract[Id, View]]]] =
    listContracts(interfaceCompanion, (_: JavaContract[Id, View]) => true)

  /** A stream of contracts of the given template.
    *
    * The stream starts with all contracts from an ACS snapshot and then emits contracts created in transactions
    * with the special rule that contracts whose archival is already known are not emitted.
    *
    * '''emits''' whenever the store knows or learns about a create event of a contract matching the template that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Source[JavaContract[TCid, T], NotUsed]

  /** A stream of contracts of the given interface.
    *
    * The stream starts with all contracts from an ACS snapshot and then emits contracts created in transactions
    * with the special rule that contracts whose archival is already known are not emitted.
    *
    * '''emits''' whenever the store knows or learns about a create event of a contract matching the interface that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Source[JavaContract[Id, View], NotUsed]

  /** Signal when the store has finished ingesting ledger data from the given offset or a larger one.
    */
  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit]
}

object AcsStore {

  def apply(storage: Storage, loggerFactory: NamedLoggerFactory, scope: ContractFilter)(implicit
      ec: ExecutionContext
  ): AcsStore =
    storage match {
      case _: MemoryStorage => new InMemoryAcsStore(loggerFactory, scope)
      case _: DbStorage =>
        throw new RuntimeException("Not implemented")
    }

  /** A query result computed as-of a specific ledger API offset. */
  case class QueryResult[A](
      offset: String,
      value: A,
  ) {
    def map[B](f: A => B): QueryResult[B] =
      copy(
        value = f(this.value)
      )
  }

  /** A sink for ingesting an initial ACS snapshot and transactions changing it. */
  trait IngestionSink {

    /** The transaction filter required for ingestion. */
    def transactionFilter: TransactionFilter

    /** Ingest create events that are part of the initial active contract snapshot ingestion */
    def ingestActiveContracts(
        events: Seq[CreatedEvent]
    )(implicit traceContext: TraceContext): Future[Unit]

    /** Signal the end of ingesting the active contract snapshot. */
    def switchToIngestingTransactions(
        acsOffset: String
    )(implicit traceContext: TraceContext): Future[Unit]

    /** The last offset that was ingested by this sink.
      *
      * Expected to be used by ingestion services to determine from where they should continue ingesting.
      */
    def getLastIngestedOffset: Future[Option[String]]

    /** Ingest a transaction served by the transaction stream. */
    def ingestTransaction(tx: Transaction)(implicit traceContext: TraceContext): Future[Unit]
  }

  /** Static specification of a set of create events in scope for ingestion into an AcsStore. */
  trait ContractFilter {

    /** The transaction filter required for ingestion into this store. */
    def transactionFilter: TransactionFilter

    /** Whether the event is in scope. */
    def contains(ev: CreatedEvent): Boolean

    /** Whether the scope might contain an event of the given template. */
    def mightContain[TC, TCid, T](templateCompanion: ContractCompanion[TC, TCid, T]): Boolean

    /** Whether the scope might contain an event of the given interface. */
    def mightContain[I, Id, View](interfaceCompanion: InterfaceCompanion[I, Id, View]): Boolean
  }

  /** A helper to easily construct a [[ContractFilter]] for a single party. */
  case class SimpleContractFilter(
      primaryParty: PartyId,
      templateFilters: immutable.Map[Identifier, CreatedEvent => Boolean],
      interfaceFilters: immutable.Map[Identifier, CreatedEvent => Boolean] = Map.empty,
  ) extends ContractFilter {

    override val transactionFilter: TransactionFilter = {
      val templateIds = templateFilters.keys.toSet.asJava
      val interfaceIds =
        interfaceFilters.keys.map(i => i -> Filter.Interface.INCLUDE_VIEW).toMap.asJava
      val partyString: String = primaryParty.toProtoPrimitive
      new FiltersByParty(
        Map[String, Filter](
          partyString -> new InclusiveFilter(templateIds, interfaceIds)
        ).asJava
      )
    }

    override def contains(ev: CreatedEvent): Boolean =
      templateFilters.get(ev.getTemplateId).exists(evPredicate => evPredicate(ev)) ||
        ev.getInterfaceViews.keySet.asScala.exists { i =>
          interfaceFilters.get(i).exists(evPredicate => evPredicate(ev))
        }

    override def mightContain[TC, TCid, T](
        templateCompanion: ContractCompanion[TC, TCid, T]
    ): Boolean =
      templateFilters.contains(templateCompanion.TEMPLATE_ID)

    override def mightContain[I, Id, View](
        interfaceCompanion: InterfaceCompanion[I, Id, View]
    ): Boolean =
      interfaceFilters.contains(interfaceCompanion.TEMPLATE_ID)
  }

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(
      p: JavaContract[TCid, T] => Boolean
  ): (Identifier, CreatedEvent => Boolean) =
    (
      templateCompanion.TEMPLATE_ID,
      ev => p(JavaContract.fromCodegenContract[TCid, T](templateCompanion.fromCreatedEvent(ev))),
    )

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[I, Id <: ContractId[I], View <: DamlRecord[_]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(
      p: JavaContract[Id, View] => Boolean
  ): (Identifier, CreatedEvent => Boolean) =
    (
      interfaceCompanion.TEMPLATE_ID, // wat
      ev => p(JavaContract.fromCodegenContract[Id, View](interfaceCompanion.fromCreatedEvent(ev))),
    )
}
