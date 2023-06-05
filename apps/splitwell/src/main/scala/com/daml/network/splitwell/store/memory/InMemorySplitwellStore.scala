package com.daml.network.splitwell.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.automation.TransferFollowTrigger
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.splitwell.store.SplitwellStore
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.store.MultiDomainAcsStore.ContractCompanion
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class InMemorySplitwellStore(
    override val providerParty: PartyId,
    override protected[this] val domainConfig: SplitwellDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with SplitwellStore {

  override def listTransferrableGroups()(implicit
      tc: TraceContext
  ): Future[Map[DomainId, Seq[splitwellCodegen.Group.ContractId]]] = for {
    // find all groups still on 'others' domains
    othersGroups <- Future
      .traverse(domainConfig.splitwell.others) { otherDomain =>
        for {
          otherDomainId <- domains.signalWhenConnected(otherDomain.alias)
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
    preferredId <- domains.signalWhenConnected(domainConfig.splitwell.preferred.alias)
    // find members of 'othersGroups' with install contracts on 'preferred'
    preferredInstalledMembers <- multiDomainAcsStore
      .filterContractsOnDomain(
        splitwellCodegen.SplitwellInstall.COMPANION,
        preferredId,
        { co: Contract[?, splitwellCodegen.SplitwellInstall] =>
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
      followerContracts <- multiDomainAcsStore.listReadyContracts(
        followerCompanion
      )
      leaderContracts <- multiDomainAcsStore.listReadyContracts(
        leaderCompanion
      )
    } yield {
      val leaderContractsById = leaderContracts.map(c => getLeaderId(c.contract.payload) -> c).toMap
      followerContracts.collect(Function.unlift { c =>
        leaderContractsById
          .get(getFollowerId(c.contract.payload))
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

  override lazy val acsContractFilter = SplitwellStore.contractFilter(providerParty)
}
