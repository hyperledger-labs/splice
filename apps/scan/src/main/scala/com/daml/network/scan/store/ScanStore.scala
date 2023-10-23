package com.daml.network.scan.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{CNLedgerConnection, PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{CNNodeAppStoreWithHistory, MultiDomainAcsStore, TxLogStore}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.scan.store.db.DbScanStore
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.util.{CoinConfigSchedule, ContractWithState, TemplateJsonDecoder}
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

sealed trait SortOrder

object SortOrder {
  case object Ascending extends SortOrder
  case object Descending extends SortOrder
}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStoreWithHistory[
      TxLogIndexRecord,
      TxLogEntry,
    ]
    with PackageIdResolver.HasCoinRulesPayload {

  override protected def txLogParser = new ScanTxLogParser(loggerFactory)

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  def serviceUserPrimaryParty: PartyId

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter =
    ScanStore.contractFilter(svcParty)

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]

  private def getCoinRules()(implicit
      tc: TraceContext
  ): Future[ContractWithState[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active CoinRules contract")
          .asRuntimeException()
      )
    )

  def getCoinRulesPayload()(implicit tc: TraceContext): Future[cc.coin.CoinRules] =
    getCoinRules().map(_.contract.payload)

  def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]]

  def lookupSvcRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]]

  def getTotalCoinBalance(asOfEndOfRound: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal]
  def getRewardsCollectedInRound(round: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[TxLogEntry.OpenMiningRoundLogEntry]

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
    getCoinRulesPayload().map(cr =>
      CoinConfigSchedule(cr)
        .getConfigAsOf(t)
        .globalDomain
        .fees
        .baseRateTrafficLimits
    )

  def listImportCrates(receiverParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]]

  def findFeaturedAppRight(providerPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: Int,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionLogEntry]]

  protected def loadTxLogEntry(
      txLogReader: TxLogStore.Reader[TxLogIndexRecord, TxLogEntry],
      eventId: String,
      domainId: DomainId,
      acsContractId: Option[ContractId[?]],
      dbType: String3,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[TxLogEntry] =
    txLogReader.loadTxLogEntry(eventId, domainId, acsContractId, filterUnique(dbType))

  protected def filterUnique(dbType: String3)(
      entries: Seq[TxLogEntry],
      eventId: String,
  ): TxLogEntry = {
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
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
      storage: Storage,
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
          serviceUserPrimaryParty = serviceUserPrimaryParty,
          svcParty = svcParty,
          loggerFactory,
          treeSource,
          retryProvider,
        )
      case db: DbStorage =>
        new DbScanStore(
          serviceUserPrimaryParty = serviceUserPrimaryParty,
          svcParty = svcParty,
          db,
          loggerFactory,
          treeSource,
          retryProvider,
        )
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
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
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
