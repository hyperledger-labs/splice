package com.daml.network.scan.store

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{
  CNNodeAppStoreWithHistory,
  ConfiguredDefaultDomain,
  MultiDomainAcsStore,
}
import MultiDomainAcsStore.{ContractWithState, ReadyContract}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.util.{CoinConfigSchedule, Contract}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStoreWithHistory[
      ScanTxLogParser.TxLogIndexRecord,
      ScanTxLogParser.TxLogEntry,
    ]
    with ConfiguredDefaultDomain {

  override protected def txLogParser = new ScanTxLogParser(loggerFactory)

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def scanConfig: ScanAppBackendConfig

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter =
    ScanStore.contractFilter(svcParty, scanConfig)

  override final def defaultAcsDomain = scanConfig.domains.global.alias

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ReadyContract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]

  def lookupCoinRulesV1Test()(implicit tc: TraceContext): Future[
    Option[ReadyContract[ccV1Test.coin.CoinRulesV1Test.ContractId, ccV1Test.coin.CoinRulesV1Test]]
  ]

  def getTotalCoinBalance()(implicit tc: TraceContext): Future[(BigDecimal, BigDecimal)]

  def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal]
  def getRewardsCollectedInRound(round: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry]

  def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)]

  def verifyDataExistsForEndOfRound(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[Unit]

  def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorPurchasedTraffic]]

  def lookupValidatorTraffic(validatorParty: PartyId)(implicit tc: TraceContext): Future[
    Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]
  ]

  def getTotalPaidValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Long]

  def getBaseRateTrafficLimitsAsOf(t: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[cc.globaldomain.BaseRateTrafficLimits] =
    lookupCoinRules().map(
      _.map(cr =>
        CoinConfigSchedule(cr)
          .getConfigAsOf(t)
          .globalDomain
          .fees
          .baseRateTrafficLimits
      )
        .getOrElse(
          throw Status.NOT_FOUND.withDescription("No active SvcRules contract").asRuntimeException()
        )
    )

  def listImportCrates(receiver: String)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]]

  def findFeaturedAppRight(
      domainId: DomainId,
      providerPartyId: PartyId,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]

}

object ScanStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      scanConfig: ScanAppBackendConfig,
      loggerFactory: NamedLoggerFactory,
      connection: CNLedgerConnection,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): ScanStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryScanStore(
          svcParty = svcParty,
          scanConfig,
          loggerFactory,
          connection,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  def contractFilter(
      svcParty: PartyId,
      scanConfig: ScanAppBackendConfig,
  ): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
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
        mkFilter(cc.globaldomain.ValidatorTraffic.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coinimport.ImportCrate.COMPANION)(co => co.payload.svc == svc),
      ) ++
        (if (scanConfig.enableCoinRulesUpgrade)
           Map(mkFilter(ccV1Test.coin.CoinRulesV1Test.COMPANION)(co => co.payload.svc == svc))
         else Map.empty),
    )
  }
}
