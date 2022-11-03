package com.daml.network.directory.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.{directory as directoryCodegen, wallet as walletCodegen}
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.store.JavaAcsStore
import com.daml.network.util.{JavaContract => Contract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the directory backend's gRPC request handlers and automation.
  *
  * Most methods have default implementations in terms of an [[com.daml.network.store.AcsStore]]
  * to simplify implementing the store. They are all made overridable so that a DB backed store can use
  * custom indices to ensure the scalability of these queries.
  */
trait DirectoryStore extends AutoCloseable {
  import JavaAcsStore.QueryResult

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: JavaAcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  protected val acsStore: JavaAcsStore

  /** Get the party-id of the provider.
    * All results from the store are scoped to contracts managed by this provider.
    */
  def providerParty: PartyId

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Lookup the directory install for a user */
  def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryInstall.ContractId, directoryCodegen.DirectoryInstall]
  ]]] =
    acsStore.findContract(directoryCodegen.DirectoryInstall.COMPANION)(co =>
      co.payload.user == user.toPrim
    )

  /** Lookup a directory entry by name. */
  def lookupEntryByName(
      name: String
  ): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    acsStore.findContract(directoryCodegen.DirectoryEntry.COMPANION)(co => co.payload.name == name)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  ): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    acsStore.findContract(directoryCodegen.DirectoryEntry.COMPANION)(co =>
      co.payload.user == partyId.toPrim
    )

  /** Lookup a directory entry offer by its id. */
  def lookupEntryOfferById(
      id: ContractId[directoryCodegen.DirectoryEntryOffer]
  ): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryEntryOffer.ContractId, directoryCodegen.DirectoryEntryOffer]
  ]]] =
    acsStore.lookupContractById(directoryCodegen.DirectoryEntryOffer.COMPANION)(id)

  /** Lookup a directory entry subscription context by its id. */
  def lookupEntryContextById(
      id: ContractId[directoryCodegen.DirectoryEntryContext]
  ): Future[QueryResult[Option[Contract[
    directoryCodegen.DirectoryEntryContext.ContractId,
    directoryCodegen.DirectoryEntryContext,
  ]]]] =
    acsStore.lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(id)

  /** Lookup a directory entry request by its id. */
  def lookupEntryRequestById(
      id: ContractId[directoryCodegen.DirectoryEntryRequest]
  ): Future[QueryResult[Option[Contract[
    directoryCodegen.DirectoryEntryRequest.ContractId,
    directoryCodegen.DirectoryEntryRequest,
  ]]]] =
    acsStore.lookupContractById(directoryCodegen.DirectoryEntryRequest.COMPANION)(id)

  /** List all directory entries that are active as of a specific revision. */
  def listEntries(): Future[QueryResult[
    Seq[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    // TODO(#790): add limit
    acsStore.listContracts(directoryCodegen.DirectoryEntry.COMPANION)

  /** All install requests to the provider.
    *
    * '''emits''' whenever the store knows or learns about a create event of a `DirectoryInstallRequest` that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamInstallRequests(): Source[Contract[
    directoryCodegen.DirectoryInstallRequest.ContractId,
    directoryCodegen.DirectoryInstallRequest,
  ], NotUsed] =
    acsStore.streamContracts(directoryCodegen.DirectoryInstallRequest.COMPANION)

  /** All directory entry requests to the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `DirectoryEntryRequest`
    */
  def streamEntryRequests(): Source[Contract[
    directoryCodegen.DirectoryEntryRequest.ContractId,
    directoryCodegen.DirectoryEntryRequest,
  ], NotUsed] =
    acsStore.streamContracts(directoryCodegen.DirectoryEntryRequest.COMPANION)

  /** All accepted app payments whose receiver is the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `AcceptedAppPayment`
    */
  def streamAcceptedAppPayments(): Source[
    Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment],
    NotUsed,
  ] =
    acsStore.streamContracts(walletCodegen.AcceptedAppPayment.COMPANION)

  /** All accepted initial subscription payments whose receiver is the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `SubscriptionInitialPayment`
    */
  def streamSubscriptionInitialPayments(): Source[Contract[
    subsCodegen.SubscriptionInitialPayment.ContractId,
    subsCodegen.SubscriptionInitialPayment,
  ], NotUsed] =
    acsStore.streamContracts(subsCodegen.SubscriptionInitialPayment.COMPANION)

  /** All accepted subscription payments whose receiver is the provider.
    *
    * Analogous to [[streamInstallRequests]], but for `SubscriptionPayment`
    */
  def streamSubscriptionPayments(): Source[
    Contract[subsCodegen.SubscriptionPayment.ContractId, subsCodegen.SubscriptionPayment],
    NotUsed,
  ] =
    acsStore.streamContracts(subsCodegen.SubscriptionPayment.COMPANION)

  // TODO(M1-92): only added for tests (in DirectoryStoreTest)
  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit] =
    acsStore.signalWhenIngested(offset)

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
  def contractFilter(providerPartyId: PartyId): JavaAcsStore.ContractFilter = {
    import JavaAcsStore.mkFilter
    val provider: String = providerPartyId.toProtoPrimitive

    JavaAcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(directoryCodegen.DirectoryEntry.COMPANION)(co => co.payload.provider == provider),
        mkFilter(directoryCodegen.DirectoryEntryRequest.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(directoryCodegen.DirectoryEntryOffer.COMPANION)(co =>
          co.payload.entryRequest.provider == provider
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co => co.payload.provider == provider),
        mkFilter(directoryCodegen.DirectoryEntryContext.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.provider == provider
        ),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.provider == provider
        ),
        mkFilter(directoryCodegen.DirectoryInstallRequest.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(directoryCodegen.DirectoryInstall.COMPANION)(co => co.payload.provider == provider),
      ),
    )
  }

}
