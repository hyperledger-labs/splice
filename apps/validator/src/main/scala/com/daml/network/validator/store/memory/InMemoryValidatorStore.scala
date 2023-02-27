package com.daml.network.validator.store.memory

import com.daml.network.environment.CoinRetries
import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.daml.network.validator.config.ValidatorDomainConfig
import com.daml.network.validator.store.ValidatorStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.ExecutionContext

class InMemoryValidatorStore(
    override val key: ValidatorStore.Key,
    override protected[this] val domainConfig: ValidatorDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: CoinRetries,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCoinAppStoreWithoutHistory
    with ValidatorStore {

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)
}
