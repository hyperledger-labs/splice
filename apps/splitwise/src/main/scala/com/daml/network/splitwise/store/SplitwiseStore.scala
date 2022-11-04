package com.daml.network.splitwise.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.client.binding.Primitive
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen, Wallet => walletCodegen}
import com.daml.network.splitwise.store.memory.InMemorySplitwiseStore
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
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
  ): Future[QueryResult[Option[Contract[splitwiseCodegen.SplitwiseInstall]]]] =
    acsStore.findContract(splitwiseCodegen.SplitwiseInstall)(co => co.payload.user == user.toPrim)

  def lookupTransferInProgressById(
      id: Primitive.ContractId[splitwiseCodegen.TransferInProgress]
  ): Future[QueryResult[Option[Contract[splitwiseCodegen.TransferInProgress]]]] =
    acsStore.lookupContractById(splitwiseCodegen.TransferInProgress)(id)

  def lookupGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  ): Future[QueryResult[Option[Contract[splitwiseCodegen.Group]]]] =
    acsStore.findContract(splitwiseCodegen.Group)(co =>
      co.payload.owner == owner.toPrim && co.payload.id == id
    )

  def getGroup(
      owner: PartyId,
      id: splitwiseCodegen.GroupId,
  )(implicit ec: ExecutionContext): Future[QueryResult[Contract[splitwiseCodegen.Group]]] =
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

  def streamInstallRequests(): Source[Contract[splitwiseCodegen.SplitwiseInstallRequest], NotUsed] =
    acsStore.streamContracts(splitwiseCodegen.SplitwiseInstallRequest)

  def streamGroupRequests(): Source[Contract[splitwiseCodegen.GroupRequest], NotUsed] =
    acsStore.streamContracts(splitwiseCodegen.GroupRequest)

  def streamAcceptedAppPayments(): Source[Contract[walletCodegen.AcceptedAppPayment], NotUsed] =
    acsStore.streamContracts(walletCodegen.AcceptedAppPayment)
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
    val provider = providerPartyId.toPrim

    AcsStore.SimpleContractFilter(
      providerPartyId,
      Map(
        mkFilter(splitwiseCodegen.SplitwiseInstallRequest)(co => co.payload.provider == provider),
        mkFilter(splitwiseCodegen.SplitwiseInstall)(co => co.payload.provider == provider),
        mkFilter(splitwiseCodegen.TransferInProgress)(co => co.payload.group.provider == provider),
        mkFilter(splitwiseCodegen.Group)(co => co.payload.provider == provider),
        mkFilter(splitwiseCodegen.GroupRequest)(co => co.payload.group.provider == provider),
        mkFilter(walletCodegen.AcceptedAppPayment)(co => co.payload.provider == provider),
      ),
    )
  }
}
