package com.daml.network.directory.store

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.directory.store.db.DbDirectoryStore
import com.daml.network.directory.store.db.DirectoryTables.DirectoryAcsStoreRowData
import com.daml.network.directory.store.memory.InMemoryDirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ParticipantAdminConnection.HasParticipantId
import com.daml.network.store.MultiDomainAcsStore.{ConstrainedTemplate, TemplateFilter}
import com.daml.network.store.{CNNodeAppStoreWithoutHistory, Limit, MultiDomainAcsStore}
import com.daml.network.util.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
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

  lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[DirectoryAcsStoreRowData] =
    DirectoryStore.contractFilter(providerParty)

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Lookup the directory install for a user */
  def lookupInstallByUserWithOffset(
      user: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[directoryCodegen.DirectoryInstall.ContractId, directoryCodegen.DirectoryInstall]
  ]]]

  /** Lookup a directory entry by name. */
  def lookupEntryByNameWithOffset(
      name: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]]

  def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ] =
    lookupEntryByNameWithOffset(name).map(_.value)

  /** Lookup a directory entry by party.
    * If there are multiple candidate entries, then the first one lexicographically is returned.
    */
  def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]

  /** List all directory entries that are active as of a specific revision, up to a certain number. */
  // TODO(#300): allow submitting the page token to receive the next page
  // TODO(#300): at the moment, trimming the list to the right size is performed here, that should be moved to the acsStore
  def listEntries(namePrefix: String, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[Contract[directoryCodegen.DirectoryEntry.ContractId, directoryCodegen.DirectoryEntry]]
  ]

  import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts

  def listExpiredDirectoryEntries: ListExpiredContracts[
    directoryCodegen.DirectoryEntry.ContractId,
    directoryCodegen.DirectoryEntry,
  ] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(directoryCodegen.DirectoryEntry.COMPANION)(
      _.expiresAt
    )

  /** List subscriptions ready for expiry. */
  def listExpiredDirectorySubscriptions(
      now: CantonTimestamp,
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[DirectoryStore.IdleDirectorySubscription]]

  def lookupDirectoryEntryContext(
      reference: subsCodegen.SubscriptionRequest.ContractId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    directoryCodegen.DirectoryEntryContext.ContractId,
    directoryCodegen.DirectoryEntryContext,
  ]]]

  final def listLaggingCoinRulesFollowers(
      targetDomain: DomainId,
      participantIdSource: HasParticipantId,
  )(implicit tc: TraceContext): Future[Seq[AssignedContract[?, ?]]] =
    multiDomainAcsStore.listAssignedContractsNotOnDomainN(
      targetDomain,
      participantIdSource,
      DirectoryStore.templatesMovedByMyAutomation,
    )
}

object DirectoryStore {
  def apply(
      providerParty: PartyId,
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): DirectoryStore = {
    storage match {
      case _: MemoryStorage =>
        new InMemoryDirectoryStore(
          providerParty = providerParty,
          svcParty = svcParty,
          loggerFactory,
          retryProvider,
        )
      case db: DbStorage =>
        new DbDirectoryStore(
          providerParty = providerParty,
          svcParty = svcParty,
          db,
          loggerFactory,
          retryProvider,
        )
    }
  }

  case class IdleDirectorySubscription(
      state: AssignedContract[
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

  private[network] val templatesMovedByMyAutomation: Seq[ConstrainedTemplate] =
    Seq[ConstrainedTemplate](
      directoryCodegen.DirectoryEntry.COMPANION,
      directoryCodegen.DirectoryEntryContext.COMPANION,
      directoryCodegen.DirectoryInstall.COMPANION,
      directoryCodegen.DirectoryInstallRequest.COMPANION,
      subsCodegen.SubscriptionInitialPayment.COMPANION,
      subsCodegen.SubscriptionIdleState.COMPANION,
      subsCodegen.SubscriptionPayment.COMPANION,
      subsCodegen.TerminatedSubscription.COMPANION,
    )

  /** Contract filter of a directory app store for a specific provider. */
  def contractFilter(
      providerPartyId: PartyId
  ): MultiDomainAcsStore.ContractFilter[DirectoryAcsStoreRowData] =
    MultiDomainAcsStore.SimpleContractFilter(
      providerPartyId,
      directoryTemplateFilters(providerPartyId),
    )

  def directoryTemplateFilters(
      providerPartyId: PartyId
  ): Map[QualifiedName, TemplateFilter[?, ?, DirectoryAcsStoreRowData]] = {
    import MultiDomainAcsStore.mkFilter
    val provider: String = providerPartyId.toProtoPrimitive

    Map(
      mkFilter(directoryCodegen.DirectoryEntry.COMPANION)(co => co.payload.provider == provider) {
        contract =>
          DirectoryAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
            directoryEntryName = Some(contract.payload.name),
            directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
      },
      mkFilter(directoryCodegen.DirectoryEntryContext.COMPANION)(co =>
        co.payload.provider == provider
      ) { contract =>
        DirectoryAcsStoreRowData(
          contract = contract,
          directoryEntryName = Some(contract.payload.name),
          directoryEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(subsCodegen.SubscriptionInitialPayment.COMPANION)(co =>
        co.payload.subscriptionData.provider == provider
      ) { contract =>
        DirectoryAcsStoreRowData(
          contract = contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(subsCodegen.SubscriptionPayment.COMPANION)(co =>
        co.payload.subscriptionData.provider == provider
      ) { contract =>
        DirectoryAcsStoreRowData(
          contract = contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
        )
      },
      mkFilter(subsCodegen.SubscriptionIdleState.COMPANION)(co =>
        co.payload.subscriptionData.provider == provider
      ) { contract =>
        DirectoryAcsStoreRowData(
          contract = contract,
          subscriptionReferenceContractId = Some(contract.payload.reference),
          subscriptionNextPaymentDueAt =
            Some(Timestamp.assertFromInstant(contract.payload.nextPaymentDueAt)),
        )
      },
      mkFilter(directoryCodegen.DirectoryInstallRequest.COMPANION)(co =>
        co.payload.provider == provider
      ) { contract =>
        DirectoryAcsStoreRowData(
          contract = contract,
          directoryInstallUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
        )
      },
      mkFilter(directoryCodegen.DirectoryInstall.COMPANION)(co => co.payload.provider == provider) {
        contract =>
          DirectoryAcsStoreRowData(
            contract = contract,
            directoryInstallUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
      },
      mkFilter(subsCodegen.TerminatedSubscription.COMPANION)(co =>
        co.payload.subscriptionData.provider == provider
      )(DirectoryAcsStoreRowData(_)),
    )
  }

}
