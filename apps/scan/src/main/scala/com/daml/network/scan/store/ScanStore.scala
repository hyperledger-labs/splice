package com.daml.network.scan.store

import com.daml.network.codegen.java.cc
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CCHistoryStore, StoreWithOpenMiningRounds}
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore extends FlagCloseable with StoreWithOpenMiningRounds {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Audit log store */
  // TODO(tech-debt): build common infrastructure for such audit-log stores and inline its functions
  val history: CCHistoryStore

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  val acs: AcsStore

  def lookupCoinRules()
      : Future[QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]] =
    acs.findContract(cc.coin.CoinRules.COMPANION)(_ => true)
}

object ScanStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext
  ): ScanStore =
    storage match {
      case _: MemoryStorage => new InMemoryScanStore(svcParty = svcParty, loggerFactory, timeouts)
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
