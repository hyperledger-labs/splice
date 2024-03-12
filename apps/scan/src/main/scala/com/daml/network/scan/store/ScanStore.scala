package com.daml.network.scan.store

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.store.{CNNodeAppStore, Limit, MultiDomainAcsStore, PageLimit, TxLogStore}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.scan.store.db.{
  DbScanStore,
  ScanAggregatesReader,
  ScanAggregator,
  ScanTables,
}
import com.daml.network.scan.store.db.ScanTables.ScanAcsStoreRowData
import com.daml.network.util.{CoinConfigSchedule, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import java.time.Instant

sealed trait SortOrder

object SortOrder {
  case object Ascending extends SortOrder
  case object Descending extends SortOrder
}
final case class ScanInfo(publicUrl: String, memberName: String)

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait ScanStore
    extends CNNodeAppStore[
      TxLogEntry
    ]
    with PackageIdResolver.HasCoinRules {

  def aggregate()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundTotals]]

  def backFillAggregates()(implicit
      tc: TraceContext
  ): Future[Boolean]

  def key: ScanStore.Key

  def domainMigrationId: Long

  override lazy val acsContractFilter: MultiDomainAcsStore.ContractFilter[ScanAcsStoreRowData] =
    ScanStore.contractFilter(key, domainMigrationId)

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

  /** Returns all items extracted by `f` from the SvcRules ensuring that they're sorted by domainId,
    * so that the order is deterministic.
    */
  def listFromSvcRules[T](
      f: ContractWithState[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules] => Vector[
        (String, T)
      ]
  )(implicit tc: TraceContext): Future[Vector[(String, Vector[T])]] = {
    for {
      svcRulesO <- lookupSvcRules()
    } yield {
      val svcRules = svcRulesO getOrElse {
        throw new NoSuchElementException("found no svcRules instance")
      }
      val items = f(svcRules)
      val itemsByDomain = items.groupBy(_._1).view.mapValues(_.map(_._2))
      itemsByDomain.toVector.sortBy(_._1)
    }
  }
  def listSvcScans()(implicit tc: TraceContext): Future[Vector[(String, Vector[ScanInfo])]] = {
    listFromSvcRules { svcRules =>
      for {
        memberInfo <- svcRules.payload.members.asScala.values.toVector
        (domainId, domainConfig) <- memberInfo.domainNodes.asScala
        scan <- domainConfig.scan.toScala
      } yield domainId -> ScanInfo(scan.publicUrl, memberInfo.name)
    }
  }
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
  ): Future[OpenMiningRoundTxLogEntry]

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
  ): Future[Seq[TxLogEntry.TransactionTxLogEntry]]

  def getAggregatedRounds()(implicit tc: TraceContext): Future[Option[ScanAggregator.RoundRange]]
  def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]]
  def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]]
}

object ScanStore {

  case class Key(
      /** The party-id of the SVC whose public data this scan is distributing. */
      svcParty: PartyId
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("svcParty", _.svcParty)
    )
  }

  def apply(
      key: ScanStore.Key,
      storage: Storage,
      ingestFromParticipantBegin: Boolean,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      createScanAggregatesReader: DbScanStore => ScanAggregatesReader,
      // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
      domainMigrationId: Long,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): ScanStore = {
    storage match {
      case db: DbStorage =>
        new DbScanStore(
          key = key,
          db,
          ingestFromParticipantBegin,
          loggerFactory,
          retryProvider,
          createScanAggregatesReader,
          domainMigrationId,
          participantId,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }

  def contractFilter(
      key: ScanStore.Key,
      domainMigrationId: Long,
  ): MultiDomainAcsStore.ContractFilter[ScanAcsStoreRowData] = {
    import MultiDomainAcsStore.mkFilter
    val svc = key.svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.svcParty,
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
        mkFilter(cn.cns.CnsEntry.COMPANION)(co => co.payload.svc == svc) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            cnsEntryName = Some(contract.payload.name),
            cnsEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(cc.globaldomain.MemberTraffic.COMPANION)(vt =>
          vt.payload.svc == svc && vt.payload.migrationId == domainMigrationId
        ) { contract =>
          ScanAcsStoreRowData(
            contract,
            memberTrafficMember = Member
              .fromProtoPrimitive_(contract.payload.memberId)
              .fold(
                // we ignore cases where the member id is invalid instead of throwing an exception
                // to avoid killing the entire ingestion pipeline as a result
                _ => None,
                Some(_),
              ),
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
