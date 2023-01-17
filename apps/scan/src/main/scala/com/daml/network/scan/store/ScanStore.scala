package com.daml.network.scan.store

import com.daml.network.codegen.java.cc
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{AcsStore, CoinAppStore, StoreWithOpenMiningRounds}
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore extends CoinAppStore with StoreWithOpenMiningRounds {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  def lookupCoinRules()(implicit
      ec: ExecutionContext
  ): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    acs.findContract(cc.coin.CoinRules.COMPANION)(_ => true)
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
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }
}
