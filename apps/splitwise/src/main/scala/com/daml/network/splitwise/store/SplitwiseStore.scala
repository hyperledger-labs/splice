package com.daml.network.splitwise.store

import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.splitwise.store.memory.InMemorySplitwiseStore
import com.daml.network.store.AcsStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

trait SplitwiseStore extends AutoCloseable {
  import AcsStore.QueryResult

  val acsIngestionSink: AcsStore.IngestionSink

  val acs: AcsStore

  def providerParty: PartyId

  def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[splitwiseCodegen.SplitwiseInstall.ContractId, splitwiseCodegen.SplitwiseInstall]
  ]]] =
    acs.findContract(splitwiseCodegen.SplitwiseInstall.COMPANION)(co =>
      co.payload.user == user.toProtoPrimitive
    )

  def lookupGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[
    QueryResult[Option[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]]]
  ] =
    acs.findContract(splitwiseCodegen.Group.COMPANION)(co =>
      co.payload.owner == owner.toProtoPrimitive && co.payload.id == id
    )

  def getGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  )(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]]] =
    lookupGroup(owner, id).map(
      _.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription(
              s"No active Group contract for owner $owner and id $id"
            )
          )
        )
      )
    )
}

object SplitwiseStore {
  def apply(providerParty: PartyId, storage: Storage, loggerFactory: NamedLoggerFactory)(implicit
      ec: ExecutionContext
  ): SplitwiseStore =
    storage match {
      case _: MemoryStorage => new InMemorySplitwiseStore(providerParty, loggerFactory)
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
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co => co.payload.provider == provider),
      ),
    )
  }
}
