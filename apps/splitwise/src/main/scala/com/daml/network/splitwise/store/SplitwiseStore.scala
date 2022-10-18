package com.daml.network.splitwise.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.splitwise.store.memory.InMemorySplitwiseStore
import com.daml.network.store.AcsStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

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

  def streamInstallRequests(): Source[Contract[splitwiseCodegen.SplitwiseInstallRequest], NotUsed] =
    acsStore.streamContracts(splitwiseCodegen.SplitwiseInstallRequest)
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
      ),
    )
  }
}
