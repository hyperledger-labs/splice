package com.daml.network.store

import com.daml.network.environment.RetryProvider
import com.daml.network.store.AcsStore.ContractFilter
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.DomainId

import scala.concurrent.ExecutionContext

trait AcsWithTxLogStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]]
    extends AcsStore
    with TxLogStore[TXI, TXE] {}

object AcsWithTxLogStore {
  def apply[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      scope: ContractFilter,
      txLogParser: TxLogStore.Parser[TXI, TXE],
      domainId: DomainId,
      futureSupervisor: FutureSupervisor,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): AcsStore = {
    storage match {
      case _: MemoryStorage =>
        new InMemoryAcsWithTxLogStore(
          loggerFactory,
          scope,
          txLogParser,
          domainId,
          futureSupervisor,
          retryProvider,
        )
      case _: DbStorage =>
        throw new RuntimeException("Not implemented")
    }
  }
}
