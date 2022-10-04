package com.daml.network.directory.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Directory => directoryCodegen, Wallet => walletCodegen}
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the directory backend's gRPC request handlers and automation.
  *
  * Most methods have default implementations in terms of an [[com.daml.network.store.AcsStore]]
  * to simplify implementing the store. They are all made overridable so that a DB backed store can use
  * custom indices to ensure the scalability of these queries.
  */
trait DirectoryStore extends AutoCloseable {
  import AcsStore.QueryResult

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  protected val acsStore: AcsStore

  /** Get the party-id of the provider.
    * All results from the store are scoped to contracts managed by this provider.
    */
  def providerParty: PartyId

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Lookup the directory install for a user */
  def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryInstall]]]] =
    acsStore.findContract(directoryCodegen.DirectoryInstall)(co => co.payload.user == user.toPrim)

  /** Lookup a directory entry by name. */
  def lookupEntryByName(
      name: String
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]] =
    acsStore.findContract(directoryCodegen.DirectoryEntry)(co => co.payload.name == name)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntry]]]] =
    acsStore.findContract(directoryCodegen.DirectoryEntry)(co => co.payload.user == partyId.toPrim)

  /** Lookup a directory entry offer by its id. */
  def lookupEntryOfferById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryOffer]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryOffer]]]] =
    acsStore.lookupContractById(directoryCodegen.DirectoryEntryOffer)(id)

  /** Lookup a directory entry request by its id. */
  def lookupEntryRequestById(
      id: Primitive.ContractId[directoryCodegen.DirectoryEntryRequest]
  ): Future[QueryResult[Option[Contract[directoryCodegen.DirectoryEntryRequest]]]] =
    acsStore.lookupContractById(directoryCodegen.DirectoryEntryRequest)(id)

  /** List all directory entries that are active as of a specific revision. */
  def listEntries(): Future[QueryResult[Seq[Contract[directoryCodegen.DirectoryEntry]]]] =
    // TODO(#790): add limit
    acsStore.listContracts(directoryCodegen.DirectoryEntry)

  /** All install requests to the provider.
    *
    * '''emits''' whenever the store knows or learns about a create event of a `DirectoryInstallRequest` that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamInstallRequests(): Source[Contract[directoryCodegen.DirectoryInstallRequest], NotUsed] =
    acsStore.streamContracts(directoryCodegen.DirectoryInstallRequest)

  /** All directory entry requests to the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `DirectoryEntryRequest`
    */
  def streamEntryRequests(): Source[Contract[directoryCodegen.DirectoryEntryRequest], NotUsed] =
    acsStore.streamContracts(directoryCodegen.DirectoryEntryRequest)

  /** All accepted app payments whose receiver is the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `DirectoryEntryRequest`
    */
  def streamAcceptedAppPayments(): Source[Contract[walletCodegen.AcceptedAppPayment], NotUsed] =
    acsStore.streamContracts(walletCodegen.AcceptedAppPayment)

}

object DirectoryStore {
  def apply(
      providerParty: PartyId,
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): DirectoryStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryDirectoryStore(
          providerParty = providerParty,
          svcParty = svcParty,
          loggerFactory,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of a directory app store for a specific provider. */
  def contractFilter(providerPartyId: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val provider = providerPartyId.toPrim

    AcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(directoryCodegen.DirectoryEntry)(co => co.payload.provider == provider),
        mkFilter(directoryCodegen.DirectoryEntryRequest)(co =>
          co.payload.entry.provider == provider
        ),
        mkFilter(directoryCodegen.DirectoryEntryOffer)(co => co.payload.entry.provider == provider),
        mkFilter(walletCodegen.AcceptedAppPayment)(co => co.payload.receiver == provider),
        mkFilter(directoryCodegen.DirectoryInstallRequest)(co => co.payload.provider == provider),
        mkFilter(directoryCodegen.DirectoryInstall)(co => co.payload.provider == provider),
      ),
    )
  }

}
