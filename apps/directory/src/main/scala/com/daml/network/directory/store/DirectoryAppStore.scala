package com.daml.network.directory.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.v1.transaction_filter.TransactionFilter
import com.daml.ledger.api.v1.event.CreatedEvent
import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.codegen.CN.{Directory => directoryCodegen, Wallet => walletCodegen}
import com.daml.network.directory.store.memory.InMemoryDirectoryAppStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A query result computed as-of a specific ledger API offset. */
case class QueryResult[A](
    offset: String,
    value: A,
) {
  // TODO(#790): figure out how to provide these methods more efficiently. We're interested in the earliest offset as of which a result was delivered when composing query results.
  def map[B](f: A => B): QueryResult[B] = QueryResult(offset, f(value))
}

trait DirectoryAppStore extends AutoCloseable {

  /** Get the party-id of the provider.
    * All results from the store are scoped to contracts managed by this provider.
    */
  def getProviderParty(): Future[PartyId]

  /** Lookup a contract by id. */
  def lookupContractById[T](
      templateCompanion: TemplateCompanion[T]
  )(id: Primitive.ContractId[T]): Future[QueryResult[Option[Contract[T]]]]

  /** Lookup the directory install for a user */
  def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryInstall]]]]

  /** Lookup a directory entry by name. */
  def lookupEntryByName(
      name: String
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]]

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]]

  /** Lookup a directory entry offer by its id. */
  def lookupEntryOfferById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryOffer]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryOffer]]]]

  /** Lookup a directory entry request by its id. */
  def lookupEntryRequestById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryRequest]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryRequest]]]]

  /** List all directory entries that are active as of a specific revision. */
  def listEntries(): Future[QueryResult[Seq[Contract[directoryCodegen.DirectoryEntry]]]]

  /** All install requests to the provider.
    *
    * '''emits''' whenever the store knows or learns about a create event of a `DirectoryInstallRequest` that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamInstallRequests(): Source[Contract[directoryCodegen.DirectoryInstallRequest], NotUsed]

  /** All directory entry requests to the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `DirectoryEntryRequest`
    */
  def streamEntryRequests(): Source[Contract[directoryCodegen.DirectoryEntryRequest], NotUsed]

  /** All accepted app payments whose receiver is the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `DirectoryEntryRequest`
    */
  def streamAcceptedAppPayments(): Source[Contract[walletCodegen.AcceptedAppPayment], NotUsed]

  /** Set the provider party once it is known after bootstrapping.
    * Must be called at most once.
    */
  def setProviderParty(partyId: PartyId): Future[Unit]

  // TODO(#790): split ingestion into a separate trait so that readers do not get access to it

  /** The transaction filter required for ingestion into this store. */
  def transactionFilter: Future[TransactionFilter]

  /** Ingest create events that are part of the active contract snapshot
    * from which this store is initialized.
    */
  def ingestActiveContracts(
      events: Seq[CreatedEvent]
  )(implicit traceContext: TraceContext): Future[Unit]

  /** Signal the end of ingesting the active contract snapshot. */
  def switchToIngestingTransactions(
      acsOffset: String
  )(implicit traceContext: TraceContext): Future[Unit]

  /** Ingest a transaction served by the transaction stream. */
  def ingestTransaction(tx: Transaction)(implicit traceContext: TraceContext): Future[Unit]
}

object DirectoryAppStore {
  def apply(storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): DirectoryAppStore =
    storage match {
      case _: MemoryStorage => new InMemoryDirectoryAppStore(loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }
}
