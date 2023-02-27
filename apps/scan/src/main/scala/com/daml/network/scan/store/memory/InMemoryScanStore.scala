package com.daml.network.scan.store.memory

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.environment.CoinRetries
import com.daml.network.scan.config.ScanDomainConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.store.InMemoryCoinAppStoreWithoutHistory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected[this] val domainConfig: ScanDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStoreWithoutHistory
    with ScanStore {

  override lazy val acsContractFilter = ScanStore.contractFilter(svcParty)

  override def getTotalCoinBalance(): Future[(BigDecimal, BigDecimal)] = {
    for {
      // TODO(#2985): subtract holding fees
      // TODO(#2985): Make this computation round-based
      acs <- defaultAcs
      coins <- acs.listContracts(coinCodegen.Coin.COMPANION)
      totalCoins = coins.foldLeft(BigDecimal(0.0))((b, coin) =>
        b + coin.payload.amount.initialAmount
      )
      lockedCoins <- acs.listContracts(coinCodegen.LockedCoin.COMPANION)
      totalLockedCoins = lockedCoins.foldLeft(BigDecimal(0.0))((b, lockedCoin) =>
        b + lockedCoin.payload.coin.amount.initialAmount
      )
    } yield {
      (
        totalCoins,
        totalLockedCoins,
      )
    }
  }

  override def close(): Unit = {
    super.close()
  }
}
