package com.daml.network.scan.store

import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn
import com.daml.network.environment.{PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.store.{CNNodeAppStore, Limit, MultiDomainAcsStore, PageLimit, TxLogStore}
import com.daml.network.codegen.java.cc.amulet.FeaturedAppRight
import com.daml.network.scan.store.db.{
  DbScanStore,
  ScanAggregatesReader,
  ScanAggregator,
  ScanTables,
}
import com.daml.network.scan.store.db.ScanTables.ScanAcsStoreRowData
import com.daml.network.util.{
  AssignedContract,
  AmuletConfigSchedule,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, Member, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
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

/** Utility class grouping the two kinds of stores managed by the DsoApp. */
trait ScanStore
    extends CNNodeAppStore[
      TxLogEntry
    ]
    with PackageIdResolver.HasAmuletRules {

  def aggregate()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundTotals]]

  def backFillAggregates()(implicit
      tc: TraceContext
  ): Future[Option[Long]]

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

  def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[
    Option[ContractWithState[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]]
  ]

  private def getAmuletRulesWithState()(implicit
      tc: TraceContext
  ): Future[ContractWithState[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]] =
    lookupAmuletRules().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active AmuletRules contract")
          .asRuntimeException()
      )
    )

  /** Returns all items extracted by `f` from the DsoRules ensuring that they're sorted by domainId,
    * so that the order is deterministic.
    */
  def listFromSvNodeStates[T](
      f: cn.dso.memberstate.SvNodeState => Vector[(String, T)]
  )(implicit tc: TraceContext): Future[Vector[(String, Vector[T])]] = {
    for {
      dsoRules <- getDsoRules()
      nodeStates <- Future.traverse(dsoRules.payload.members.asScala.keys) { svPartyId =>
        getSvNodeState(PartyId.tryFromProtoPrimitive(svPartyId))
      }
    } yield {
      val items = nodeStates.toVector.flatMap(nodeState => f(nodeState.contract.payload))
      val itemsByDomain = items.groupBy(_._1).view.mapValues(_.map(_._2))
      itemsByDomain.toVector.sortBy(_._1)
    }
  }

  def listDsoScans()(implicit tc: TraceContext): Future[Vector[(String, Vector[ScanInfo])]] = {
    listFromSvNodeStates { nodeState =>
      for {
        (domainId, domainConfig) <- nodeState.state.domainNodes.asScala.toVector
        scan <- domainConfig.scan.toScala
      } yield domainId -> ScanInfo(scan.publicUrl, nodeState.svName)
    }
  }
  def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[cc.amuletrules.AmuletRules.ContractId, cc.amuletrules.AmuletRules]] =
    getAmuletRulesWithState().map(_.contract)

  def getGlobalDomainId()(implicit
      tc: TraceContext
  ): Future[DomainId] =
    getAmuletRulesWithState()
      .flatMap(
        _.state.fold(
          Future.successful,
          Future failed Status.FAILED_PRECONDITION
            .withDescription("AmuletRules is in-flight, no current global domain")
            .asRuntimeException(),
        )
      )

  def lookupAnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.ans.AnsRules.ContractId, cn.ans.AnsRules]]]

  def lookupDsoRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]]]

  private def getDsoRules()(implicit
      tc: TraceContext
  ): Future[ContractWithState[cn.dsorules.DsoRules.ContractId, cn.dsorules.DsoRules]] =
    lookupDsoRules().map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active DsoRules contract")
          .asRuntimeException()
      )
    )

  def getTotalAmuletBalance(asOfEndOfRound: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal]
  def getRewardsCollectedInRound(round: Long)(implicit tc: TraceContext): Future[BigDecimal]

  def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal]

  def getAmuletConfigForRound(round: Long)(implicit
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
    getAmuletRulesWithState().map(cr =>
      AmuletConfigSchedule(cr)
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
    Seq[ContractWithState[cn.ans.AnsEntry.ContractId, cn.ans.AnsEntry]]
  ]

  def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[cn.ans.AnsEntry.ContractId, cn.ans.AnsEntry]]
  ]

  def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[ContractWithState[cn.ans.AnsEntry.ContractId, cn.ans.AnsEntry]]
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

  def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[
    AssignedContract[cn.dso.memberstate.SvNodeState.ContractId, cn.dso.memberstate.SvNodeState]
  ]]

  def getSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[
    AssignedContract[cn.dso.memberstate.SvNodeState.ContractId, cn.dso.memberstate.SvNodeState]
  ] =
    lookupSvNodeState(svPartyId).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(show"No SvNodeState found for $svPartyId")
          .asRuntimeException()
      )
    )
}

object ScanStore {

  case class Key(
      /** The party-id of the DSO whose public data this scan is distributing. */
      dsoParty: PartyId
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("dsoParty", _.dsoParty)
    )
  }

  def apply(
      key: ScanStore.Key,
      storage: Storage,
      isFounder: Boolean,
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
          isFounder,
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
    val dso = key.dsoParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.dsoParty,
      Map(
        mkFilter(cc.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(cn.ans.AnsRules.COMPANION)(co => co.payload.dso == dso)(ScanAcsStoreRowData(_)),
        mkFilter(cn.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              round = Some(contract.payload.round.number),
            )
        },
        mkFilter(cc.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            featuredAppRightProvider =
              Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
          )
        },
        mkFilter(cc.amulet.Amulet.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            amount = Some(contract.payload.amount.initialAmount),
          )
        },
        mkFilter(cc.amulet.LockedAmulet.COMPANION)(co => co.payload.amulet.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.lock.expiresAt)),
            amount = Some(contract.payload.amulet.amount.initialAmount),
          )
        },
        mkFilter(cn.ans.AnsEntry.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            ansEntryName = Some(contract.payload.name),
            ansEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(cc.globaldomain.MemberTraffic.COMPANION)(vt =>
          vt.payload.dso == dso && vt.payload.migrationId == domainMigrationId
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
        mkFilter(cc.validatorlicense.ValidatorLicense.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            val roundsCollected = contract.payload.faucetState.map { faucetState =>
              faucetState.lastReceivedFor.number - faucetState.firstReceivedFor.number - faucetState.numCouponsMissed + 1L
            }
            ScanAcsStoreRowData(
              contract = contract,
              validatorLicenseRoundsCollected = Some(roundsCollected.orElse(0L)),
            )
        },
        mkFilter(cn.dso.memberstate.SvNodeState.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract,
              svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
            )
        },
      ),
    )
  }
}
