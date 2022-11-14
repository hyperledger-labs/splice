package com.daml.network.splitwise.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.codegen.java.cn.splitwise as splitwiseCodegen
import com.daml.network.splitwise.store.memory.InMemorySplitwiseStore
import com.daml.network.store.AcsStore
import com.daml.network.util.{JavaContract => Contract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

trait SplitwiseStore extends AutoCloseable {
  import AcsStore.QueryResult

  val acsIngestionSink: AcsStore.IngestionSink

  protected val acsStore: AcsStore

  def providerParty: PartyId

  def lookupInstall(
      user: PartyId
  ): Future[QueryResult[Option[
    Contract[splitwiseCodegen.SplitwiseInstall.ContractId, splitwiseCodegen.SplitwiseInstall]
  ]]] =
    acsStore.findContract(splitwiseCodegen.SplitwiseInstall.COMPANION)(co =>
      co.payload.user == user.toPrim
    )

  def lookupTransferInProgressById(
      id: splitwiseCodegen.TransferInProgress.ContractId
  ): Future[QueryResult[Option[
    Contract[splitwiseCodegen.TransferInProgress.ContractId, splitwiseCodegen.TransferInProgress]
  ]]] =
    acsStore.lookupContractById(splitwiseCodegen.TransferInProgress.COMPANION)(id)

  def lookupGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[
    QueryResult[Option[Contract[splitwiseCodegen.Group.ContractId, splitwiseCodegen.Group]]]
  ] =
    acsStore.findContract(splitwiseCodegen.Group.COMPANION)(co =>
      co.payload.owner == owner.toPrim && co.payload.id == id
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

  def streamInstallRequests(): Source[Contract[
    splitwiseCodegen.SplitwiseInstallRequest.ContractId,
    splitwiseCodegen.SplitwiseInstallRequest,
  ], NotUsed] =
    acsStore.streamContracts(splitwiseCodegen.SplitwiseInstallRequest.COMPANION)

  def streamGroupRequests(): Source[
    Contract[splitwiseCodegen.GroupRequest.ContractId, splitwiseCodegen.GroupRequest],
    NotUsed,
  ] =
    acsStore.streamContracts(splitwiseCodegen.GroupRequest.COMPANION)

  def streamAcceptedAppPayments(): Source[
    Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment],
    NotUsed,
  ] =
    acsStore.streamContracts(walletCodegen.AcceptedAppPayment.COMPANION)
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
