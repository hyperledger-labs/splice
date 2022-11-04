package com.daml.network.wallet.store.memory

import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.store.{JavaAcsStore as AcsStore, JavaInMemoryAcsStore as InMemoryAcsStore}
import com.daml.network.util.{JavaContract as Contract}
import com.daml.network.wallet.store.EndUserWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.AsyncOrSyncCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.concurrent.*

class InMemoryEndUserWalletStore(
    override val key: EndUserWalletStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContext
) extends EndUserWalletStore
    with NamedLogging {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      EndUserWalletStore.contractFilter(key),
      logAllStateUpdates = true,
    )

  // TODO(#790): review tracing strategy for setup steps
  noTracingLogger.debug(s"Created InMemoryEndUserWalletStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def lookupLatestOpenMiningRound(
  ): Future[AcsStore.QueryResult[Option[
    Contract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
  ]]] =
    acsStore
      .listContracts(roundCodegen.OpenMiningRound.COMPANION)
      .map(_.map(contracts => contracts.sortBy(r => r.payload.round.number).lastOption))

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = Seq()
}
