package com.daml.network.scan.store

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.store.memory.InMemoryScanStore
import com.daml.network.store.{CNNodeAppStore, Limit, MultiDomainAcsStore, PageLimit, TxLogStore}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.scan.store.db.{DbScanStore, ScanTables}
import com.daml.network.scan.store.db.ScanTables.ScanAcsStoreRowData
import com.daml.network.util.{CoinConfigSchedule, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant
import com.daml.network.scan.store.db.ScanAggregator

sealed trait SortOrder

object SortOrder {
  case object Ascending extends SortOrder
  case object Descending extends SortOrder
}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStore[
      TxLogEntry
    ]
    with PackageIdResolver.HasCoinRules {

  def aggregate()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundTotals]]

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  def serviceUserPrimaryParty: PartyId

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[ScanAcsStoreRowData] =
    ScanStore.contractFilter(svcParty)

  override lazy val txLogConfig = new TxLogStore.Config[TxLogEntry] {
    override val parser = new ScanTxLogParser(loggerFactory)
    override def entryToRow = ScanTables.ScanTxLogRowData.fromTxLogEntry
    override def encodeEntry = TxLogEntry.encode
    override def decodeEntry = TxLogEntry.decode
  }

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules]]]

  private def getCoinRulesWithState()(implicit
      tc: TraceContext
  ): Future[ContractWithState[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active CoinRules contract")
          .asRuntimeException()
      )
    )

  def getCoinRules()(implicit
      tc: TraceContext
  ): Future[Contract[cc.coinrules.CoinRules.ContractId, cc.coinrules.CoinRules]] =
    getCoinRulesWithState().map(_.contract)

  def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.cns.CnsRules.ContractId, cn.cns.CnsRules]]]

  def lookupSvcRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]]

  def getTotalCoinBalance(asOfEndOfRound: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal]
  def getRewardsCollectedInRound(round: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal]

  def getCoinConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[TxLogEntry.OpenMiningRoundLogEntry]

  def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)]

  def ensureAggregated[T](asOfEndOfRound: Long)(f: => Future[T])(implicit
      tc: TraceContext
  ): Future[T] = for {
    (lastRound, _) <- getRoundOfLatestData()
    result <-
      if (lastRound >= asOfEndOfRound) f
      else Future.failed(roundNotAggregated())
  } yield result

  def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]]

  def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorPurchasedTraffic]]

  def getTopValidatorLicenses(limit: Limit)(implicit tc: TraceContext): Future[Seq[
    Contract[cc.validatorlicense.ValidatorLicense.ContractId, cc.validatorlicense.ValidatorLicense]
  ]]

  def getBaseRateTrafficLimitsAsOf(t: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[cc.globaldomain.BaseRateTrafficLimits] =
    getCoinRulesWithState().map(cr =>
      CoinConfigSchedule(cr)
        .getConfigAsOf(t)
        .globalDomain
        .fees
        .baseRateTrafficLimits
    )

  def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long]

  def listImportCrates(receiverParty: PartyId, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]]

  def findFeaturedAppRight(providerPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listEntries(namePrefix: String, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]
  ]

  def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]
  ]

  def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[ContractWithState[cn.cns.CnsEntry.ContractId, cn.cns.CnsEntry]]
  ]

  def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: PageLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionLogEntry]]

  def getAggregatedRounds()(implicit tc: TraceContext): Future[Option[ScanAggregator.RoundRange]]
  def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]]
  def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]]
}

object ScanStore {
  def apply(
      serviceUserPrimaryParty: PartyId,
      svcParty: PartyId,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): ScanStore = {
    storage match {
      case _: MemoryStorage =>
        new InMemoryScanStore(
          serviceUserPrimaryParty = serviceUserPrimaryParty,
          svcParty = svcParty,
          loggerFactory,
          retryProvider,
        )
      case db: DbStorage =>
        new DbScanStore(
          serviceUserPrimaryParty = serviceUserPrimaryParty,
          svcParty = svcParty,
          db,
          loggerFactory,
          retryProvider,
        )
    }
  }

  def contractFilter(
      svcParty: PartyId
  ): MultiDomainAcsStore.ContractFilter[ScanAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coinrules.CoinRules.COMPANION)(co => co.payload.svc == svc)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(cn.cns.CnsRules.COMPANION)(co => co.payload.svc == svc)(ScanAcsStoreRowData(_)),
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              round = Some(contract.payload.round.number),
            )
        },
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            featuredAppRightProvider =
              Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          )
        },
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            amount = Some(contract.payload.amount.initialAmount),
          )
        },
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.lock.expiresAt)),
            amount = Some(contract.payload.coin.amount.initialAmount),
          )
        },
        mkFilter(cc.coinimport.ImportCrate.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            importCrateReceiver = Some(contract.payload.receiver),
          )
        },
        mkFilter(cn.cns.CnsEntry.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            cnsEntryName = Some(contract.payload.name),
            cnsEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(cc.globaldomain.MemberTraffic.COMPANION)(vt => vt.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract,
            memberTrafficMember = Member
              .fromProtoPrimitive_(contract.payload.memberId)
              .fold(e => throw new IllegalArgumentException(e), Some(_)),
            totalTrafficPurchased = Some(contract.payload.totalPurchased),
          )
        },
        mkFilter(cc.validatorlicense.ValidatorLicense.COMPANION)(co => co.payload.svc == svc) {
          contract =>
            val roundsCollected = contract.payload.faucetState.map { faucetState =>
              faucetState.lastReceivedFor.number - faucetState.firstReceivedFor.number - faucetState.numCouponsMissed + 1L
            }
            ScanAcsStoreRowData(
              contract = contract,
              validatorLicenseRoundsCollected = Some(roundsCollected.orElse(0L)),
            )
        },
      ),
    )
  }
}
