package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  Contract as CodegenContract,
}
import com.daml.network.codegen.java.cc
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CCHistoryStore}
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore extends AutoCloseable {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Audit log store */
  // TODO(M1-92): build common infrastructure for such audit-log stores and inline its functions
  val history: CCHistoryStore

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  protected val acsStore: AcsStore

  def lookupCoinRules()
      : Future[QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]] =
    acsStore.findContract(cc.coin.CoinRules.COMPANION)(_ => true)

  def listContracts[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
  ): Future[AcsStore.QueryResult[Seq[Contract[TCid, T]]]] =
    acsStore.listContracts(templateCompanion, filter)
}

object ScanStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): ScanStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanStore(svcParty = svcParty, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(svcParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }
}
