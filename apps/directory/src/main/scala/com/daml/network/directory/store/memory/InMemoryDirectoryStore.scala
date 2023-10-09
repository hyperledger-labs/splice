package com.daml.network.directory.store.memory

import com.daml.network.codegen.java.cn.directory.{DirectoryEntry, DirectoryInstall}
import com.daml.network.directory.config.DirectoryDomainConfig
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStoreWithoutHistory, MultiDomainAcsStore}
import com.daml.network.util.{Contract, ContractWithState}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.digitalasset.canton.data.CantonTimestamp
import cats.syntax.traverse.*

import scala.concurrent.*

class InMemoryDirectoryStore(
    override val providerParty: PartyId,
    override val svcParty: PartyId,
    override protected[this] val domainConfig: DirectoryDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with DirectoryStore {

  override def lookupInstallByUserWithOffset(user: PartyId)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryInstall.ContractId, DirectoryInstall]]]
  ] = {
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomainWithOffset(directoryCodegen.DirectoryInstall.COMPANION)(
          _,
          co => co.payload.user == user.toProtoPrimitive,
        )
    )
  }

  override def lookupEntryByNameWithOffset(name: String)(implicit tc: TraceContext): Future[
    MultiDomainAcsStore.QueryResult[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]]
  ] = {
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(directoryCodegen.DirectoryEntry.COMPANION)(
        _,
        co => co.payload.name == name,
      )
    )
  }

  override def lookupEntryByParty(partyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] = {
    for {
      domainId <- defaultAcsDomainIdF
      entryContracts <- multiDomainAcsStore.filterContractsOnDomain(
        directoryCodegen.DirectoryEntry.COMPANION,
        domainId,
        (entry: Contract[
          directoryCodegen.DirectoryEntry.ContractId,
          directoryCodegen.DirectoryEntry,
        ]) => entry.payload.user == partyId.toProtoPrimitive,
      )
      res = entryContracts.sortBy(_.payload.name).headOption
    } yield res
  }

  override def listEntries(namePrefix: String, pageSize: Int)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[DirectoryEntry.ContractId, DirectoryEntry]]] = for {
    domainId <- defaultAcsDomainIdF
    list <- multiDomainAcsStore.filterContractsOnDomain(
      directoryCodegen.DirectoryEntry.COMPANION,
      domainId,
      (entry: Contract[
        directoryCodegen.DirectoryEntry.ContractId,
        directoryCodegen.DirectoryEntry,
      ]) => entry.payload.name.startsWith(namePrefix),
    )
  } yield list.take(pageSize)

  override def listExpiredDirectorySubscriptions(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[DirectoryStore.IdleDirectorySubscription]] = for {
    domainId <- defaultAcsDomainIdF
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
        lookupDirectoryEntryContext(subscription.payload.reference)
          .map(context => (subscription, context.map(_.contract)))
      }
    // Only deliver the ones referencing an active directory entry context
    // TODO(tech-debt): consider whether this kind of join might be leading to stale subscriptions not being expired.
    result = subscriptionsWithContext
      .sortBy(_._1.payload.nextPaymentDueAt)
      .iterator
      .collect { case (subscription, Some(context)) =>
        DirectoryStore.IdleDirectorySubscription(subscription, context)
      }
      .take(limit)
      .toSeq
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
