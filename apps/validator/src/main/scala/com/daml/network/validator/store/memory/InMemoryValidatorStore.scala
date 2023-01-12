package com.daml.network.validator.store.memory

import com.daml.network.store.{AcsStore, DomainStore, InMemoryAcsStore, InMemoryDomainStore}
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.ExecutionContext

class InMemoryValidatorStore(
    override val key: ValidatorStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit protected val ec: ExecutionContext)
    extends ValidatorStore {

  private val inMemoryAcsStore =
    new InMemoryAcsStore(
      loggerFactory,
      ValidatorStore.contractFilter(key),
      logAllStateUpdates = false,
    )

  override val domains: InMemoryDomainStore =
    new InMemoryDomainStore(loggerFactory)

  val acs: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override val domainIngestionSink: DomainStore.IngestionSink = domains.ingestionSink

  override def close(): Unit = ()
}
