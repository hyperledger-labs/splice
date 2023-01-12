package com.daml.network.sv.store

import com.daml.network.codegen.java.cc
import com.daml.network.store.{AcsStore, DomainStore}
import com.daml.network.sv.store.memory.InMemorySvStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.ExecutionContext

/** Utility class grouping the two kinds of stores managed by the SvApp. */
trait SvStore extends AutoCloseable {

  /** Get the party-id of the SV issuing CC accepted by this provider. */
  def svParty: PartyId

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  val domainIngestionSink: DomainStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  val acs: AcsStore

  val domains: DomainStore
}

object SvStore {
  def apply(
      svParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): SvStore =
    storage match {
      case _: MemoryStorage => new InMemorySvStore(svParty = svParty, loggerFactory)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(svParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val sv = svParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      svParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == sv),
        mkFilter(cc.coin.CoinRulesRequest.COMPANION)(co => co.payload.svc == sv),
        // TODO(M3-46): copy more of the filter over from SvcStore, as we merge more triggers and console commands
      ),
    )
  }
}
