package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{
  CNNodeAppStoreWithHistory,
  ConfiguredDefaultDomain,
  MultiDomainAcsStore,
  TxLogStore,
}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{CoinConfigSchedule, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.config.CantonRequireTypes.String3
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
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
    ScanStore.contractFilter(svcParty)

  override final def defaultAcsDomain = scanConfig.domains.global.alias

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]

  def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]]

  def getTotalCoinBalance(asOfEndOfRound: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal]
  def getRewardsCollectedInRound(round: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry]

  def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)]

  def verifyDataExistsForEndOfRound(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[Unit] = {
    if (asOfEndOfRound < 0) {
      Future.failed(
        Status.OUT_OF_RANGE
          .withDescription("Round numbers cannot be negative")
          .asRuntimeException()
      )
    } else {
      // TODO(#2930): For now, we support querying data for any round up to the latest closed one. This should
      // be revisited once we add some backfilling (historical or ACS-based) in the scan bootstrap.
      getRoundOfLatestData().flatMap { case (round, _) =>
        if (asOfEndOfRound > round) {
          Future.failed(
            Status.NOT_FOUND
              .withDescription(s"Data for round ${asOfEndOfRound} not yet computed")
              .asRuntimeException()
          )
        } else {
          Future.successful(())
        }
      }
    }
  }

  def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorPurchasedTraffic]]

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

  def listImportCrates(receiverParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]]

  def findFeaturedAppRight(
      domainId: DomainId,
      providerPartyId: PartyId,
  )(implicit
      tc: TraceContext
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listRecentActivity(
      beginAfterEventId: Option[String],
      limit: Int,
  )(implicit
      tc: TraceContext
  ): Future[Seq[ScanTxLogParser.TxLogEntry.RecentActivityLogEntry]]

  protected def loadTxLogEntry(
      txLogReader: TxLogStore.Reader[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry],
      eventId: String,
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      dbType: String3,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ScanTxLogParser.TxLogEntry] =
    txLogReader.loadTxLogEntry(eventId, domainId, acsContractId, filterUnique(dbType))

  protected def filterUnique(dbType: String3)(
      entries: Seq[ScanTxLogParser.TxLogEntry],
      eventId: String,
  ): ScanTxLogParser.TxLogEntry = {
    val res = entries.filter(e =>
      e.indexRecord.eventId == eventId && e.indexRecord.companion.dbType == dbType
    )
    res match {
      case entry +: Seq() =>
        entry
      case Seq() =>
        throw new IllegalStateException(
          s"ScanStore.filterUnique did not return any entry for event $eventId and dbType $dbType. "
        )
      case x =>
        throw new IllegalStateException(
          s"ScanStore.filterUnique returned ${x.size} entries for event $eventId and dbType $dbType."
        )
    }
  }
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
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): ScanStore = {
    val treeSource = TransactionTreeSource.LedgerConnection(svcParty, connection)
    storage match {
      case _: MemoryStorage =>
        new InMemoryScanStore(
          svcParty = svcParty,
          scanConfig,
          loggerFactory,
          treeSource,
          retryProvider,
        )
      case db: DbStorage =>
        new DbScanStore(svcParty, db, scanConfig, loggerFactory, treeSource, retryProvider)
    }
  }

  def contractFilter(
      svcParty: PartyId
  ): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.cns.CnsRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
        mkFilter(cc.coinimport.ImportCrate.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }
}
