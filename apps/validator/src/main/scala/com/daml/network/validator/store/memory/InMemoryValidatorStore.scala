package com.daml.network.validator.store.memory
import com.daml.network.store.{AcsStore, InMemoryAcsStore}
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
      logAllStateUpdates = true,
    )

  // TODO(#790): review tracing strategy for setup steps
  noTracingLogger.debug(s"Created InMemoryValidatorStore for $key")

  val acsStore: AcsStore = inMemoryAcsStore

  override val acsIngestionSink: AcsStore.IngestionSink = inMemoryAcsStore.ingestionSink

  override def close(): Unit = ()
}
