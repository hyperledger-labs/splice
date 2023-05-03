package com.daml.network.scan.store

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1 as ccV1Api
import com.daml.network.codegen.java.cc.api.v1.validatortraffic.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test as ccV1Test
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{CNNodeAppStoreWithHistory, MultiDomainAcsStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.environment.CNLedgerConnection
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStoreWithHistory[
      ScanTxLogParser.TxLogIndexRecord,
      ScanTxLogParser.TxLogEntry,
    ] {

  override protected def txLogParser = new ScanTxLogParser(loggerFactory)

  def defaultAcsDomainIdF(implicit tc: TraceContext) = domains.signalWhenConnected(defaultAcsDomain)

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def scanConfig: ScanAppBackendConfig

  override final def defaultAcsDomain = scanConfig.domains.global

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cc.coin.CoinRules.COMPANION)(_, (_: Any) => true)
    )

  def lookupCoinRulesV1Test()(implicit tc: TraceContext): Future[
    Option[Contract[ccV1Test.coin.CoinRulesV1Test.ContractId, ccV1Test.coin.CoinRulesV1Test]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore
        .findContractOnDomain(ccV1Test.coin.CoinRulesV1Test.COMPANION)(_, (_: Any) => true)
    )

  def getTotalCoinBalance()(implicit tc: TraceContext): Future[(BigDecimal, BigDecimal)]

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

  def lookupValidatorTraffic(validatorParty: PartyId)(implicit tc: TraceContext): Future[
    Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(ValidatorTraffic.COMPANION)(
        _,
        contract => contract.payload.validator == validatorParty.toProtoPrimitive,
      )
    )

  def getTotalPaidValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[BigDecimal]
}

object ScanStore {
  def apply(
      svcParty: PartyId,
      storage: Storage,
      scanConfig: ScanAppBackendConfig,
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
          scanConfig,
          loggerFactory,
          futureSupervisor,
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
        mkFilter(ccV1Api.validatortraffic.ValidatorTraffic.COMPANION)(co => co.payload.svc == svc),
      ) ++
        (if (scanConfig.enableCoinRulesUpgrade)
           Map(mkFilter(ccV1Test.coin.CoinRulesV1Test.COMPANION)(co => co.payload.svc == svc))
         else Map.empty),
    )
  }
}
