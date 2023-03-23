package com.daml.network.scan.store

import com.daml.network.codegen.java.cc
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.config.ScanDomainConfig
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{AcsStore, CNNodeAppStoreWithHistory, StoreWithOpenMiningRounds}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.tracing.TraceContext

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStoreWithHistory[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry]
    with StoreWithOpenMiningRounds {

  override protected def txLogParser = new ScanTxLogParser(loggerFactory)

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def domainConfig: ScanDomainConfig

  override final def defaultAcsDomain = domainConfig.global

  def lookupCoinRules(): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    defaultAcs.flatMap(_.findContract(cc.coin.CoinRules.COMPANION)(_ => true))

  def getTotalCoinBalance(): Future[(BigDecimal, BigDecimal)]

  def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry]

  def getRoundOfLatestData()(implicit tc: TraceContext): Future[Long]

  def verifyDataExistsForEndOfRound(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[Unit]

  def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]
}

object ScanStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      domainConfig: ScanDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      connection: CNLedgerConnection,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): ScanStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryScanStore(
          svcParty = svcParty,
          domainConfig,
          loggerFactory,
          futureSupervisor,
          connection,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(svcParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
      ),
    )
  }
}
