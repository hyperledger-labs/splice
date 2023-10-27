package com.daml.network.directory.store.memory

import com.daml.network.codegen.java.cn.directory.{DirectoryEntry, DirectoryInstall}
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStoreWithoutHistory, MultiDomainAcsStore}
import MultiDomainAcsStore.ContractState.Assigned
import MultiDomainAcsStore.QueryResult
import com.daml.network.util.{Contract, ContractWithState}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.digitalasset.canton.data.CantonTimestamp
import cats.syntax.traverseFilter.*

import scala.concurrent.*

class InMemoryDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with DirectoryStore {

  override def lookupInstallByUserWithOffset(user: PartyId)(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[DirectoryInstall.ContractId, DirectoryInstall]]]
  ] =
    multiDomainAcsStore
      .findContractWithOffset(directoryCodegen.DirectoryInstall.COMPANION)(
        (_: Contract[?, directoryCodegen.DirectoryInstall]).payload.user == user.toProtoPrimitive
      ) map (_ map (_ map (_.contract)))

  override def lookupEntryByNameWithOffset(name: String)(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]]
  ] =
    multiDomainAcsStore.findContractWithOffset(directoryCodegen.DirectoryEntry.COMPANION)(
      (_: Contract[?, directoryCodegen.DirectoryEntry]).payload.name == name
    ) map (_ map (_ map (_.contract)))

  override def lookupEntryByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] = for {
    entryContracts <- multiDomainAcsStore.filterContracts(
      directoryCodegen.DirectoryEntry.COMPANION,
      (entry: Contract[?, directoryCodegen.DirectoryEntry]) =>
        entry.payload.user == partyId.toProtoPrimitive,
    )
    res = entryContracts.sortBy(_.payload.name).headOption
  } yield res map (_.contract)

  override def listEntries(namePrefix: String, pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] = for {
    list <- multiDomainAcsStore.filterContracts(
      directoryCodegen.DirectoryEntry.COMPANION,
      (entry: Contract[?, directoryCodegen.DirectoryEntry]) =>
        entry.payload.name.startsWith(namePrefix),
    )
  } yield list.take(pageSize).map(_.contract)

  override def listExpiredDirectorySubscriptions(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[DirectoryStore.IdleDirectorySubscription]] = for {
    dueSubscriptions <- multiDomainAcsStore.filterAssignedContracts(
      subsCodegen.SubscriptionIdleState.COMPANION,
      filter = { e: Contract[?, subsCodegen.SubscriptionIdleState] =>
        now.toInstant.isAfter(e.payload.nextPaymentDueAt)
      },
    )
    // Join with the DirectoryEntryContexts
    subscriptionsWithContext <- dueSubscriptions.toList
      .traverseFilter { subscription =>
        val subscriptionState = Assigned(subscription.domain)
        lookupDirectoryEntryContext(subscription.payload.reference)
          .map(_ collect {
            case context if context.state == subscriptionState =>
              DirectoryStore.IdleDirectorySubscription(subscription, context.contract)
          })
      }
    // Only deliver the ones referencing an active directory entry context
    // TODO(tech-debt): consider whether this kind of join might be leading to stale subscriptions not being expired.
    result = subscriptionsWithContext
      .sortBy(_.state.payload.nextPaymentDueAt)
      .take(limit)
  } yield result

  override def lookupDirectoryEntryContext(
      reference: subsCodegen.SubscriptionRequest.ContractId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    directoryCodegen.DirectoryEntryContext.ContractId,
    directoryCodegen.DirectoryEntryContext,
  ]]] =
    multiDomainAcsStore.findContract(directoryCodegen.DirectoryEntryContext.COMPANION)(c =>
      c.payload.reference == reference
    )
}
