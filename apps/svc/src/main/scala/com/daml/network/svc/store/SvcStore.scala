package com.daml.network.svc.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractCompanion,
  ContractId,
}
import com.daml.network.codegen.java.cc
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore as AcsStore}
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.{JavaContract as Contract}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends AutoCloseable {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Audit log store */
  // TODO(M1-92): build common infrastructure for such audit-log stores and inline its functions
  val events: SvcEventsStore

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  protected val acsStore: AcsStore

  def lookupCoinRules(
  ): Future[
    QueryResult[Option[Contract[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules]]]
  ] =
    acsStore
      .findContract(cc.coinrules.CoinRules.COMPANION)(_ => true)

  def getCoinRules(
  )(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules]]] =
    lookupCoinRules().map(
      _.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )
    )

  def lookupValidatorRightByParty(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]]
  ] =
    acsStore.findContract(cc.coin.ValidatorRight.COMPANION)(co => co.payload.user == party.toPrim)

  def listContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
  ): Future[AcsStore.QueryResult[Seq[Contract[TCid, T]]]] =
    acsStore.listContracts(templateCompanion, filter)

  /** All requests to the SVC for creating validator-specific coin rules. */
  def streamCoinRulesRequests(): Source[
    Contract[cc.coinrules.CoinRulesRequest.ContractId, cc.coinrules.CoinRulesRequest],
    NotUsed,
  ] =
    acsStore.streamContracts(cc.coinrules.CoinRulesRequest.COMPANION)

}

object SvcStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): SvcStore =
    storage match {
      case _: MemoryStorage => new InMemorySvcStore(svcParty = svcParty, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an svc acs store for a specific acs party. */
  def contractFilter(svcParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toPrim

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coinrules.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coinrules.CoinRulesRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.validator == svc && co.payload.user == svc
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosingMiningRound.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }

}
