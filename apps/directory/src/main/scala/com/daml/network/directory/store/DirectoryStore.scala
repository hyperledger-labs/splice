package com.daml.network.directory.store

import cats.syntax.traverse.*
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.store.AcsStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
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

  implicit protected def ec: ExecutionContext

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
  def lookupInstallByUserWithOffset(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryInstall.ContractId, directoryCodegen.DirectoryInstall]
  ]]] =
    acs.findContractWithOffset(directoryCodegen.DirectoryInstall.COMPANION)(co =>
      co.payload.user == user.toProtoPrimitive
    )

  /** Lookup a directory entry by name. */
  def lookupEntryByNameWithOffset(
      name: String
  ): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] =
    acs.findContractWithOffset(directoryCodegen.DirectoryEntry.COMPANION)(co =>
      co.payload.name == name
    )

  def lookupEntryByName(name: String): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    lookupEntryByNameWithOffset(name).map(_.value)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  ): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    acs.findContract(directoryCodegen.DirectoryEntry.COMPANION)(co =>
      co.payload.user == partyId.toProtoPrimitive
    )

  /** List all directory entries that are active as of a specific revision, up to a certain number. */
  // TODO(#300): allow submitting the page token to receive the next page
  // TODO(#300): at the moment, trimming the list to the right size is performed here, that should be moved to the acsStore
  def listEntries(namePrefix: String, pageSize: Int): Future[
    Seq[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    for {
      list <- acs.listContracts(
        directoryCodegen.DirectoryEntry.COMPANION,
        (entry: Contract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ]) => entry.payload.name.startsWith(namePrefix),
      )
    } yield list.take(pageSize)

  def listExpiredDirectoryEntries(now: CantonTimestamp, limit: Int): Future[
    Seq[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    acs
      .listContracts(directoryCodegen.DirectoryEntry.COMPANION)
      .map(
        _.iterator
          .filter(e => now.toInstant.isAfter(e.payload.expiresAt))
          .take(limit)
          .toSeq
      )

  def listExpiredDirectorySubscriptions(
      now: CantonTimestamp,
      limit: Int,
  ): Future[Seq[DirectoryStore.IdleDirectorySubscription]] =
    for {
      allSubscriptions <- acs.listContracts(subsCodegen.SubscriptionIdleState.COMPANION)
      dueSubscriptions = allSubscriptions.filter(e =>
        now.toInstant.isAfter(e.payload.nextPaymentDueAt)
      )
      // Join with the DirectoryEntryContexts
      subscriptionsWithContext <- dueSubscriptions.toList
        .traverse { subscription =>
          acs
            .lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(
              directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
                subscription.payload.subscriptionData.context
              )
            )
            .map(context => (subscription, context))
        }
      // Only deliver the ones referencing an active directory entry context
      // TODO(tech-debt): consider whether this kind of join might be leading to stale subscriptions not being expired.
      result = subscriptionsWithContext.iterator
        .collect { case (subscription, Some(context)) =>
          DirectoryStore.IdleDirectorySubscription(subscription, context)
        }
        .take(limit)
        .toSeq
    } yield result
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

  case class IdleDirectorySubscription(
      state: Contract[
        subsCodegen.SubscriptionIdleState.ContractId,
        subsCodegen.SubscriptionIdleState,
      ],
      context: Contract[
        directoryCodegen.DirectoryEntryContext.ContractId,
        directoryCodegen.DirectoryEntryContext,
      ],
  ) extends PrettyPrinting {

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("state", _.state), param("context", _.context))
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
