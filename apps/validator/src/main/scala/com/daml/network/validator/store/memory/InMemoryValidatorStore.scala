package com.daml.network.validator.store.memory

import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.ExecutionContext

class InMemoryValidatorStore(
    override val key: ValidatorStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStoreWithoutHistory
    with ValidatorStore {

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)
}
