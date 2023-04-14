package com.daml.network.validator.store.memory

import com.daml.network.environment.RetryProvider
import com.daml.network.store.InMemoryCNNodeAppStoreWithoutHistory
import com.daml.network.validator.config.ValidatorDomainConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.concurrent.ExecutionContext

class InMemoryValidatorStore(
    override val key: ValidatorStore.Key,
    override protected[this] val domainConfig: ValidatorDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with ValidatorStore {

  override val walletKey = WalletStore.Key(
    key.walletServiceParty,
    key.validatorParty,
    key.svcParty,
  )

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)
}
