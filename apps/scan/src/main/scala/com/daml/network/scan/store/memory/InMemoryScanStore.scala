package com.daml.network.scan.store.memory

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.environment.CoinRetries
import com.daml.network.scan.config.ScanDomainConfig
import com.daml.network.scan.store.{ScanStore, ScanTxLogParser}
import com.daml.network.store.InMemoryCoinAppStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.*
import com.daml.network.environment.CoinLedgerConnection
import io.grpc.Status
import com.digitalasset.canton.tracing.TraceContext

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected[this] val domainConfig: ScanDomainConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val connection: CoinLedgerConnection,
    override protected val retryProvider: CoinRetries,
)(implicit
    ec: ExecutionContext
) extends InMemoryCoinAppStore[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry]
    with ScanStore {

  override lazy val acsContractFilter = ScanStore.contractFilter(svcParty)

  override def getTotalCoinBalance(): Future[(BigDecimal, BigDecimal)] = {
    for {
      // TODO(#2930): This is a very naive preliminary implementation that will be completely replaced soon
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

  override def getCoinConfigForRound(
      round: Long
  )(implicit tc: TraceContext): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry] = {
    for {
      reader <- defaultTxLogReader
      roundConfig <- reader.getTxLogEntry((indexRecord) =>
        indexRecord match {
          case roundConfig: ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord =>
            roundConfig.round == round
          case _ => false
        }
      )
    } yield {
      roundConfig match {
        case r: ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry => r
        case _ =>
          throw Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
      }
    }
  }

  override def close(): Unit = {
    super.close()
  }
}
