package com.daml.network.splitwell.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.TransferFollowTrigger
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import com.daml.network.util.{AssignedContract, Contract, ContractWithState}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class InMemorySplitwellStore(
    override val key: SplitwellStore.Key,
    override protected[this] val domainConfig: SplitwellDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with SplitwellStore {
  override def lookupInstallWithOffset(
      domainId: DomainId,
      user: PartyId,
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[splitwellCodegen.SplitwellInstall.ContractId, splitwellCodegen.SplitwellInstall]
  ]]] =
    multiDomainAcsStore.findContractOnDomainWithOffset(splitwellCodegen.SplitwellInstall.COMPANION)(
      domainId,
      co => co.payload.user == user.toProtoPrimitive,
    )

  override def lookupGroupWithOffset(
      owner: PartyId,
      id: splitwellCodegen.GroupId,
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]
    ]
  ] =
    multiDomainAcsStore.findContractWithOffset(splitwellCodegen.Group.COMPANION)(co =>
      co.payload.owner == owner.toProtoPrimitive && co.payload.id == id
    )

  override def listGroups(
      user: PartyId
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.Group.COMPANION,
      c => groupMembers(c.payload).contains(user.toProtoPrimitive),
    )

  override def listGroupInvites(owner: PartyId)(implicit traceContext: TraceContext): Future[
    Seq[ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]]
  ] =
    multiDomainAcsStore.filterContracts(
      splitwellCodegen.GroupInvite.COMPANION,
      c => c.payload.group.owner == owner.toProtoPrimitive,
    )

  override def listAcceptedGroupInvites(owner: PartyId, groupId: String)(implicit
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

  override def listBalanceUpdates(user: PartyId, key: splitwellCodegen.GroupKey)(implicit
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

  override def listTransferrableGroups()(implicit
      tc: TraceContext
  ): Future[Map[DomainId, Seq[splitwellCodegen.Group.ContractId]]] = for {
    // find all groups still on 'others' domains
    othersGroups <- Future
      .traverse(domainConfig.splitwell.others) { otherDomain =>
        for {
          otherDomainId <- domains.waitForDomainConnection(otherDomain.alias)
          groups <- multiDomainAcsStore.listContractsOnDomain(
            splitwellCodegen.Group.COMPANION,
            otherDomainId,
          )
        } yield otherDomainId -> groups
      }
      .map(_.view.filter(_._2.nonEmpty).toMap)
    allGroupMembers = othersGroups.view
      .flatMap(_._2.view.flatMap(co => groupMembers(co.payload)))
      .toSet
    preferredId <- domains.waitForDomainConnection(domainConfig.splitwell.preferred.alias)
    // find members of 'othersGroups' with install contracts on 'preferred'
    preferredInstalledMembers <- multiDomainAcsStore
      .filterContractsOnDomain(
        splitwellCodegen.SplitwellInstall.COMPANION,
        preferredId,
        { (co: Contract[?, splitwellCodegen.SplitwellInstall]) =>
          allGroupMembers(co.payload.user)
        },
      )
      .map(_.view.map(_.payload.user).toSet)
  } yield othersGroups.collect(Function unlift { case (otherId, groups) =>
    val transferrable = groups.collect {
      // only respond with groups where every member is installed on 'preferred'
      case co if groupMembers(co.payload) subsetOf preferredInstalledMembers => co.contractId
    }
    Option.when(transferrable.nonEmpty)(otherId -> transferrable)
  })

  override def listSplitwellInstalls(
      user: PartyId
  )(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellInstall.ContractId,
      splitwellCodegen.SplitwellInstall,
    ]
  ]] =
    multiDomainAcsStore.filterAssignedContracts(
      splitwellCodegen.SplitwellInstall.COMPANION,
      c => c.payload.user == user.toProtoPrimitive,
    )

  override def listSplitwellRules()(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]] =
    multiDomainAcsStore.filterAssignedContracts(
      splitwellCodegen.SplitwellRules.COMPANION,
      _ => true,
    )

  override def lookupSplitwellRules(
      domainId: DomainId
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]]] =
    multiDomainAcsStore.findContractOnDomainWithOffset(splitwellCodegen.SplitwellRules.COMPANION)(
      domainId,
      (_: Any) => true,
    )

  private def listLaggingContracts[LeaderC, LeaderTCid <: ContractId[
    _
  ], LeaderT, FollowerC, FollowerTCid <: ContractId[_], FollowerT, Id](
      leaderCompanion: LeaderC,
      followerCompanion: FollowerC,
      getLeaderId: LeaderT => Id,
      getFollowerId: FollowerT => Id,
  )(implicit
      leaderCompanionClass: ContractCompanion[LeaderC, LeaderTCid, LeaderT],
      followerCompanionClass: ContractCompanion[FollowerC, FollowerTCid, FollowerT],
      traceContext: TraceContext,
  ) =
    for {
      followerContracts <- multiDomainAcsStore.listAssignedContracts(
        followerCompanion
      )
      leaderContracts <- multiDomainAcsStore.listAssignedContracts(
        leaderCompanion
      )
    } yield {
      val leaderContractsById = leaderContracts.map(c => getLeaderId(c.payload) -> c).toMap
      followerContracts.collect(Function.unlift { c =>
        leaderContractsById
          .get(getFollowerId(c.payload))
          .filter(_.domain != c.domain)
          .map(
            TransferFollowTrigger.Task(_, c)
          )
      })
    }

  override def listLaggingBalanceUpdates()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.BalanceUpdate.ContractId,
    splitwellCodegen.BalanceUpdate,
  ]]] =
    listLaggingContracts(
      splitwellCodegen.Group.COMPANION,
      splitwellCodegen.BalanceUpdate.COMPANION,
      _.id,
      _.group.id,
    )

  override def listLaggingGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.GroupInvite.ContractId,
    splitwellCodegen.GroupInvite,
  ]]] = listLaggingContracts(
    splitwellCodegen.Group.COMPANION,
    splitwellCodegen.GroupInvite.COMPANION,
    _.id,
    _.group.id,
  )

  override def listLaggingAcceptedGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]] = listLaggingContracts(
    splitwellCodegen.Group.COMPANION,
    splitwellCodegen.AcceptedGroupInvite.COMPANION,
    _.id,
    _.groupKey.id,
  )

  override def lookupTransferInProgress(
      paymentRequest: walletCodegen.AppPaymentRequest.ContractId
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    splitwellCodegen.TransferInProgress.ContractId,
    splitwellCodegen.TransferInProgress,
  ]]]] =
    multiDomainAcsStore.findContractWithOffset(splitwellCodegen.TransferInProgress.COMPANION)(
      (co: Contract[
        splitwellCodegen.TransferInProgress.ContractId,
        splitwellCodegen.TransferInProgress,
      ]) => co.payload.reference == paymentRequest
    )

  override lazy val acsContractFilter = SplitwellStore.contractFilter(key)
}
