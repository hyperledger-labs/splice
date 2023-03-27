package com.daml.network.splitwell.store

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.splitwell.config.SplitwellDomainConfig
import com.daml.network.splitwell.store.memory.InMemorySplitwellStore
import com.daml.network.store.{AcsStore, MultiDomainAcsStore, CNNodeAppStoreWithoutHistory}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}

import scala.concurrent.{ExecutionContext, Future}

trait SplitwellStore extends CNNodeAppStoreWithoutHistory {
  import MultiDomainAcsStore.{ContractWithState, QueryResult}

  def providerParty: PartyId

  protected[this] def domainConfig: SplitwellDomainConfig
  override final def defaultAcsDomain = domainConfig.splitwell.preferred

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
}

object SplitwellStore {
  def apply(
      providerParty: PartyId,
      storage: Storage,
      domainConfig: SplitwellDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
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
          futureSupervisor,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(providerPartyId: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val provider = providerPartyId.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
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
