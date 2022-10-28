package com.daml.network.svc.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.client.binding.TemplateCompanion
import com.daml.network.codegen.CC
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

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
  ): Future[QueryResult[Option[Contract[CC.CoinRules.CoinRules]]]] =
    acsStore
      .findContract(CC.CoinRules.CoinRules)(_ => true)

  def lookupValidatorRightByParty(
      party: PartyId
  ): Future[QueryResult[Option[Contract[CC.Coin.ValidatorRight]]]] =
    acsStore.findContract(CC.Coin.ValidatorRight)(co => co.payload.user == party.toPrim)

  def listContracts[T](
      templateCompanion: TemplateCompanion[T],
      filter: Contract[T] => Boolean = (_: Contract[T]) => true,
  ): Future[AcsStore.QueryResult[Seq[Contract[T]]]] =
    acsStore.listContracts(templateCompanion, filter)

  /** All requests to the SVC for creating validator-specific coin rules. */
  def streamCoinRulesRequests(): Source[Contract[CC.CoinRules.CoinRulesRequest], NotUsed] =
    acsStore.streamContracts(CC.CoinRules.CoinRulesRequest)

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
        mkFilter(CC.CoinRules.CoinRules)(co => co.payload.svc == svc),
        mkFilter(CC.CoinRules.CoinRulesRequest)(co => co.payload.svc == svc),
        mkFilter(CC.Coin.ValidatorRight)(co =>
          co.payload.svc == svc && co.payload.validator == svc && co.payload.user == svc
        ),
        mkFilter(CC.Round.OpenMiningRound)(co => co.payload.svc == svc),
        mkFilter(CC.Round.ClosedMiningRound)(co => co.payload.svc == svc),
        mkFilter(CC.Round.IssuingMiningRound)(co => co.payload.svc == svc),
        mkFilter(CC.Round.ClosingMiningRound)(co => co.payload.svc == svc),
      ),
    )
  }

}
