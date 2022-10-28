package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

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
  import AcsStore._

  /** Defines which create events are to be ingested into the store. */
  def contractFilter: ContractFilter

  /** Lookup a contract by id. */
  def lookupContractById[T](
      templateCompanion: TemplateCompanion[T]
  )(id: Primitive.ContractId[T]): Future[QueryResult[Option[Contract[T]]]]

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContract[T](
      templateCompanion: TemplateCompanion[T]
  )(p: Contract[T] => Boolean): Future[QueryResult[Option[Contract[T]]]]

  /** List all active contracts of the given template. */
  // TODO(#790): add a limit parameter
  def listContracts[T](
      templateCompanion: TemplateCompanion[T],
      filter: Contract[T] => Boolean = (_: Contract[T]) => true,
  ): Future[QueryResult[Seq[Contract[T]]]]

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
  def streamContracts[T](
      templateCompanion: TemplateCompanion[T]
  ): Source[Contract[T], NotUsed]
}

object AcsStore {

  def apply(storage: Storage, loggerFactory: NamedLoggerFactory, scope: ContractFilter)(implicit
      ec: ExecutionContext
  ): AcsStore =
    storage match {
      case _: MemoryStorage => new InMemoryAcsStore(loggerFactory, scope)
      case _: DbStorage => throw new RuntimeException("Not implemented")
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
    ): Future[Unit]

    /** Signal the end of ingesting the active contract snapshot. */
    def switchToIngestingTransactions(
        acsOffset: String
    ): Future[Unit]

    /** Ingest a transaction served by the transaction stream. */
    def ingestTransaction(tx: Transaction): Future[Unit]

    /** Signal when the sink has finished ingesting ledger data from the given offset or a larger one.
      */
    def signalWhenIngested(offset: String): Future[Unit]
  }

  /** Static specification of a set of create events in scope for ingestion into an AcsStore. */
  trait ContractFilter {

    /** The transaction filter required for ingestion into this store. */
    def transactionFilter: TransactionFilter

    /** Whether the event is in scope. */
    def contains(ev: CreatedEvent): Boolean

    /** Whether the scope might contain an event of the given template. */
    def mightContain[T](templateCompanion: TemplateCompanion[T]): Boolean
  }

  /** A helper to easily construct a [[ContractFilter]] for a single party. */
  case class SimpleContractFilter(
      primaryParty: PartyId,
      contractFilters: immutable.Map[Identifier, CreatedEvent => Boolean],
  ) extends ContractFilter {

    override val transactionFilter: TransactionFilter = {
      val templateIds = contractFilters.keys.toSeq
      val partyString: String = Primitive.Party.unwrap(primaryParty.toPrim)
      TransactionFilter(
        Map(partyString -> Filters(Some(InclusiveFilters().withTemplateIds(templateIds))))
      )
    }

    override def contains(ev: CreatedEvent): Boolean =
      ev.templateId.exists(templateId =>
        contractFilters.get(templateId).exists(evPredicate => evPredicate(ev))
      )

    override def mightContain[T](templateCompanion: TemplateCompanion[T]): Boolean =
      contractFilters.contains(ApiTypes.TemplateId.unwrap(templateCompanion.id))
  }

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[T](templateCompanion: TemplateCompanion[T])(
      p: Contract[T] => Boolean
  ): (Identifier, CreatedEvent => Boolean) =
    (
      ApiTypes.TemplateId.unwrap(templateCompanion.id),
      ev =>
        DecodeUtil
          .decodeCreated(templateCompanion)(ev)
          .exists(co => p(Contract.fromCodegenContract(co))),
    )
}
