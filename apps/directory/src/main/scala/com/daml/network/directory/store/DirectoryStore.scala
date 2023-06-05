package com.daml.network.directory.store

import cats.syntax.traverse.*
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.config.DirectoryDomainConfig
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  TxLogStore,
}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
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
trait DirectoryStore extends CNNodeAppStoreWithoutHistory {

  import MultiDomainAcsStore.QueryResult

  /** Get the party-id of the provider.
    * All results from the store are scoped to contracts managed by this provider.
    */
  def providerParty: PartyId

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def domainConfig: DirectoryDomainConfig

  override final def defaultAcsDomain = domainConfig.global.alias

  // TODO (#5162): Remove this override so that multiDomainAcsStore is just the generic MultiDomainAcsStore
  override def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  /** Lookup the directory install for a user */
  def lookupInstallByUserWithOffset(
      user: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryInstall.ContractId, directoryCodegen.DirectoryInstall]
  ]]] = {
    // TODO (#5162): Replace with call to directory-specific query
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(directoryCodegen.DirectoryInstall.COMPANION)(
          _,
          co => co.payload.user == user.toProtoPrimitive,
        )
    )
  }

  /** Lookup a directory entry by name. */
  def lookupEntryByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]] = {
    // TODO (#5162): Replace with call to directory-specific query
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(directoryCodegen.DirectoryEntry.COMPANION)(
        _,
        co => co.payload.name == name,
      )
    )
  }

  def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    lookupEntryByNameWithOffset(name).map(_.value)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then oldest one is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(directoryCodegen.DirectoryEntry.COMPANION)(
        _,
        co => co.payload.user == partyId.toProtoPrimitive,
      )
    )

  /** List all directory entries that are active as of a specific revision, up to a certain number. */
  // TODO(#300): allow submitting the page token to receive the next page
  // TODO(#300): at the moment, trimming the list to the right size is performed here, that should be moved to the acsStore
  def listEntries(namePrefix: String, pageSize: Int)(implicit tc: TraceContext): Future[
    Seq[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    for {
      domainId <- defaultAcsDomainIdF
      // TODO (#5162): This belongs to in-memory implementation, DB should do a directory-specific query
      list <- multiDomainAcsStore.filterContractsOnDomain(
        directoryCodegen.DirectoryEntry.COMPANION,
        domainId,
        (entry: Contract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ]) => entry.payload.name.startsWith(namePrefix),
      )
    } yield list.take(pageSize)

  import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts

  def listExpiredDirectoryEntries: ListExpiredContracts[
    directoryCodegen.DirectoryEntry.ContractId,
    directoryCodegen.DirectoryEntry,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(directoryCodegen.DirectoryEntry.COMPANION)(
      _.expiresAt
    )

  def listExpiredDirectorySubscriptions(
      now: CantonTimestamp,
      limit: Int,
  )(implicit tc: TraceContext): Future[Seq[DirectoryStore.IdleDirectorySubscription]] =
    for {
      domainId <- defaultAcsDomainIdF
      // TODO (#5162): This belongs to in-memory implementation, DB should do a directory-specific query
      dueSubscriptions <- multiDomainAcsStore.filterContractsOnDomain(
        subsCodegen.SubscriptionIdleState.COMPANION,
        domainId,
        filter = { e: Contract[?, subsCodegen.SubscriptionIdleState] =>
          now.toInstant.isAfter(e.payload.nextPaymentDueAt)
        },
      )
      // Join with the DirectoryEntryContexts
      subscriptionsWithContext <- dueSubscriptions.toList
        .traverse { subscription =>
          multiDomainAcsStore
            .lookupContractByIdOnDomain(directoryCodegen.DirectoryEntryContext.COMPANION)(
              domainId,
              directoryCodegen.DirectoryEntryContext.ContractId.unsafeFromInterface(
                subscription.payload.subscriptionData.context
              ),
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
      domains: DirectoryDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): DirectoryStore = {
    val inMemory = new InMemoryDirectoryStore(
      providerParty = providerParty,
      svcParty = svcParty,
      domains,
      loggerFactory,
      futureSupervisor,
      retryProvider,
    )
    storage match {
      case _: MemoryStorage =>
        inMemory
      case _: DbStorage =>
        // TODO (#4423): Replace with persistent version
        inMemory
    }
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
  def contractFilter(providerPartyId: PartyId): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val provider: String = providerPartyId.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
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
