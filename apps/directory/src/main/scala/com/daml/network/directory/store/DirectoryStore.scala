package com.daml.network.directory.store

import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.store.AcsStore
import com.daml.network.util.JavaContract as Contract
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

  /** The [[com.daml.network.store.AcsStore]] to use for listing contracts and retrieving them by contract-id. */
  val acs: AcsStore

  /** Get the party-id of the provider.
    * All results from the store are scoped to contracts managed by this provider.
    */
  def providerParty: PartyId

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Lookup the directory install for a user */
  def lookupInstallByUser(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryInstall.ContractId, directoryCodegen.DirectoryInstall]
  ]]] =
    acs.findContract(directoryCodegen.DirectoryInstall.COMPANION)(co =>
      co.payload.user == user.toProtoPrimitive
    )

  /** Lookup a directory entry by name. */
  def lookupEntryByName(
      name: String
  ): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    acs.findContract(directoryCodegen.DirectoryEntry.COMPANION)(co => co.payload.name == name)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  ): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    acs.findContract(directoryCodegen.DirectoryEntry.COMPANION)(co =>
      co.payload.user == partyId.toProtoPrimitive
    )
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
    val provider: String = providerPartyId.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(directoryCodegen.DirectoryEntry.COMPANION)(co => co.payload.provider == provider),
        mkFilter(directoryCodegen.DirectoryEntryContext.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
          co.payload.subscriptionData.provider == provider
        ),
        mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
          co.payload.subscriptionData.provider == provider
        ),
        mkFilter(subsCodegen.SubscriptionIdleState.COMPANION)(co =>
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
