package com.daml.network.splitwell.store

import com.daml.network.automation.TransferFollowTrigger
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.splitwell.store.memory.InMemorySplitwellStore
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  ConfiguredDefaultDomain,
  TxLogStore,
}
import com.daml.network.util.{Contract, ContractWithState, AssignedContract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait SplitwellStore extends CNNodeAppStoreWithoutHistory with ConfiguredDefaultDomain {
  import MultiDomainAcsStore.QueryResult

  def providerParty: PartyId

  protected[this] def domainConfig: SplitwellDomainConfig
  override final def defaultAcsDomain = domainConfig.splitwell.preferred.alias

  override def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  def lookupInstallWithOffset(
      domainId: DomainId,
      user: PartyId,
  ): Future[QueryResult[Option[
    Contract[splitwellCodegen.SplitwellInstall.ContractId, splitwellCodegen.SplitwellInstall]
  ]]] =
    multiDomainAcsStore.findContractOnDomainWithOffset(splitwellCodegen.SplitwellInstall.COMPANION)(
      domainId,
      co => co.payload.user == user.toProtoPrimitive,
    )

  def lookupGroupWithOffset(
      owner: PartyId,
      id: splitwellCodegen.GroupId,
  ): Future[
    QueryResult[
      Option[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]
    ]
  ] =
    multiDomainAcsStore.findContractWithOffset(splitwellCodegen.Group.COMPANION)(co =>
      co.payload.owner == owner.toProtoPrimitive && co.payload.id == id
    )

  def listGroups(
      user: PartyId
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.Group.COMPANION,
      c => groupMembers(c.payload).contains(user.toProtoPrimitive),
    )

  def listGroupInvites(owner: PartyId)(implicit traceContext: TraceContext): Future[
    Seq[ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]]
  ] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.GroupInvite.COMPANION,
      c => c.payload.group.owner == owner.toProtoPrimitive,
    )

  def listAcceptedGroupInvites(owner: PartyId, groupId: String)(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.AcceptedGroupInvite.COMPANION,
      c =>
        c.payload.groupKey.owner == owner.toProtoPrimitive &&
          c.payload.groupKey == groupKey(owner, groupId),
    )

  def listBalanceUpdates(user: PartyId, key: splitwellCodegen.GroupKey)(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
  ]] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.BalanceUpdate.COMPANION,
      c =>
        groupMembers(c.payload.group).contains(user.toProtoPrimitive) &&
          groupKey(c.payload.group) == key,
    )

  /** Contract IDs of groups that can be transferred from
    * [[com.daml.network.splitwell.config.SplitwellDomains#others]] because all
    * of their members are installed on
    * [[com.daml.network.splitwell.config.SplitwellDomains#preferred]], grouped
    * by the domain they are currently installed on.
    *
    * @note Invariant: every key is an ID associated with `others`
    * @note Invariant: every value is non-empty
    */
  def listTransferrableGroups()(implicit
      tc: TraceContext
  ): Future[Map[DomainId, Seq[splitwellCodegen.Group.ContractId]]]

  def listSplitwellInstalls(user: PartyId)(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellInstall.ContractId,
      splitwellCodegen.SplitwellInstall,
    ]
  ]] =
    multiDomainAcsStore.filterAssignedContracts(
      splitwellCodegen.SplitwellInstall.COMPANION,
      c => c.payload.user == user.toProtoPrimitive,
    )

  def listSplitwellRules()(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]] =
    multiDomainAcsStore.filterAssignedContracts(
      splitwellCodegen.SplitwellRules.COMPANION,
      _ => true,
    )

  def lookupSplitwellRules(domainId: DomainId): Future[QueryResult[Option[
    Contract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]]] =
    multiDomainAcsStore.findContractOnDomainWithOffset(splitwellCodegen.SplitwellRules.COMPANION)(
      domainId,
      (_: Any) => true,
    )

  /** List balance updates that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingBalanceUpdates()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.BalanceUpdate.ContractId,
    splitwellCodegen.BalanceUpdate,
  ]]]

  /** List group invites that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.GroupInvite.ContractId,
    splitwellCodegen.GroupInvite,
  ]]]

  /** List accepted group invites that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingAcceptedGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]]

  protected[this] def groupMembers(group: splitwellCodegen.Group): Set[String] =
    group.members.asScala.toSet + group.owner

  private def groupKey(owner: PartyId, id: String): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      providerParty.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )

  private def groupKey(group: splitwellCodegen.Group): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      group.owner,
      group.provider,
      group.id,
    )
}

object SplitwellStore {
  def apply(
      providerParty: PartyId,
      storage: Storage,
      domainConfig: SplitwellDomainConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): SplitwellStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySplitwellStore(
          providerParty,
          domainConfig,
          loggerFactory,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(providerPartyId: PartyId): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val provider = providerPartyId.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(splitwellCodegen.SplitwellRules.COMPANION)(co => co.payload.provider == provider),
        mkFilter(splitwellCodegen.SplitwellInstallRequest.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(splitwellCodegen.SplitwellInstall.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(splitwellCodegen.TransferInProgress.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(splitwellCodegen.Group.COMPANION)(co => co.payload.provider == provider),
        mkFilter(splitwellCodegen.GroupRequest.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(splitwellCodegen.GroupInvite.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(splitwellCodegen.AcceptedGroupInvite.COMPANION)(co =>
          co.payload.groupKey.provider == provider
        ),
        mkFilter(splitwellCodegen.BalanceUpdate.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co => co.payload.provider == provider),
      ),
    )
  }
}
