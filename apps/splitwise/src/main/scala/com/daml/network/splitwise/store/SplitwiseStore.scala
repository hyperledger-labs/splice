package com.daml.network.splitwise.store

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.splitwise.config.SplitwiseDomainConfig
import com.daml.network.splitwise.store.memory.InMemorySplitwiseStore
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

trait SplitwiseStore extends CoinAppStoreWithoutHistory {
  import AcsStore.QueryResult

  def providerParty: PartyId

  protected[this] def domainConfig: SplitwiseDomainConfig
  override final def defaultAcsDomain = domainConfig.splitwise

  def lookupInstallWithOffset(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[splitwiseCodegen.SplitwiseInstall.ContractId, splitwiseCodegen.SplitwiseInstall]
  ]]] =
    acs.findContractWithOffset(splitwiseCodegen.SplitwiseInstall.COMPANION)(co =>
      co.payload.user == user.toProtoPrimitive
    )

  def lookupInstall(user: PartyId): Future[Option[
    Contract[splitwiseCodegen.SplitwiseInstall.ContractId, splitwiseCodegen.SplitwiseInstall]
  ]] =
    lookupInstallWithOffset(user).map(_.value)

  def lookupGroupWithOffset(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[
    QueryResult[Option[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]]]
  ] =
    acs.findContractWithOffset(splitwiseCodegen.Group.COMPANION)(co =>
      co.payload.owner == owner.toProtoPrimitive && co.payload.id == id
    )

  def lookupGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[Option[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]]] =
    lookupGroupWithOffset(owner, id).map(_.value)

  def getGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]] =
    lookupGroup(owner, id).map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(
            s"No active Group contract for owner $owner and id $id"
          )
        )
      )
    )
}

object SplitwiseStore {
  def apply(
      providerParty: PartyId,
      storage: Storage,
      domainConfig: SplitwiseDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
  )(implicit
      ec: ExecutionContext
  ): SplitwiseStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySplitwiseStore(providerParty, domainConfig, loggerFactory, futureSupervisor)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(providerPartyId: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val provider = providerPartyId.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(splitwiseCodegen.SplitwiseInstallRequest.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(splitwiseCodegen.SplitwiseInstall.COMPANION)(co =>
          co.payload.provider == provider
        ),
        mkFilter(splitwiseCodegen.TransferInProgress.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(splitwiseCodegen.Group.COMPANION)(co => co.payload.provider == provider),
        mkFilter(splitwiseCodegen.GroupRequest.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(splitwiseCodegen.BalanceUpdate.COMPANION)(co =>
          co.payload.group.provider == provider
        ),
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co => co.payload.provider == provider),
      ),
    )
  }
}
