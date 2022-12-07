package com.daml.network.svc.store

import com.daml.network.codegen.java.cc
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.JavaContract as Contract
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
  val acs: AcsStore

  def lookupCoinRules(
  ): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    acs.findContract(cc.coin.CoinRules.COMPANION)(_ => true)

  def getCoinRules(
  )(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
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
    acs.findContract(cc.coin.ValidatorRight.COMPANION)(co =>
      co.payload.user == party.toProtoPrimitive
    )
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
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.CoinRulesRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.validator == svc && co.payload.user == svc
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorReward.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }

}
