// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.store

import com.digitalasset.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.splice
import com.daml.network.environment.{PackageIdResolver, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.store.{
  AppStore,
  DsoRulesStore,
  Limit,
  MiningRoundsStore,
  MultiDomainAcsStore,
  PageLimit,
  VotesStore,
}
import com.daml.network.codegen.java.splice.amulet.FeaturedAppRight
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.scan.store.db.{DbScanStore, ScanAggregatesReader, ScanAggregator}
import com.daml.network.scan.store.db.ScanTables.ScanAcsStoreRowData
import com.daml.network.store.db.AcsJdbcTypes
import com.daml.network.util.{
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
final case class ScanInfo(publicUrl: String, svName: String)

/** Utility class grouping the two kinds of stores managed by the DsoApp. */
trait ScanStore
    extends AppStore
    with PackageIdResolver.HasAmuletRules
    with DsoRulesStore
    with MiningRoundsStore
    with VotesStore {

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

  def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[
    Option[
      ContractWithState[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
    ]
  ]

  def getAmuletRulesWithState()(implicit
      tc: TraceContext
  ): Future[
    ContractWithState[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]
  ] =
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
      f: splice.dso.svstate.SvNodeState => Vector[(String, T)]
  )(implicit tc: TraceContext): Future[Vector[(String, Vector[T])]] = {
    for {
      dsoRules <- getDsoRulesWithState()
      nodeStates <- Future.traverse(dsoRules.payload.svs.asScala.keys) { svPartyId =>
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
        (domainId, domainConfig) <- nodeState.state.synchronizerNodes.asScala.toVector
        scan <- domainConfig.scan.toScala
      } yield domainId -> ScanInfo(scan.publicUrl, nodeState.svName)
    }
  }
  def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[splice.amuletrules.AmuletRules.ContractId, splice.amuletrules.AmuletRules]] =
    getAmuletRulesWithState().map(_.contract)

  def getDecentralizedSynchronizerId()(implicit
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
  ): Future[Option[ContractWithState[splice.ans.AnsRules.ContractId, splice.ans.AnsRules]]]

  def getTotalAmuletBalance(asOfEndOfRound: Long): Future[BigDecimal]
  protected def getUncachedTotalAmuletBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal]

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
    Contract[
      splice.validatorlicense.ValidatorLicense.ContractId,
      splice.validatorlicense.ValidatorLicense,
    ]
  ]]

  def getBaseRateTrafficLimitsAsOf(t: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[splice.decentralizedsynchronizer.BaseRateTrafficLimits] =
    getAmuletRulesWithState().map(cr =>
      AmuletConfigSchedule(cr)
        .getConfigAsOf(t)
        .decentralizedSynchronizer
        .fees
        .baseRateTrafficLimits
    )

  def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long]

  def findFeaturedAppRight(providerPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listEntries(namePrefix: String, now: CantonTimestamp, limit: Limit = Limit.DefaultLimit)(
      implicit tc: TraceContext
  ): Future[
    Seq[ContractWithState[splice.ans.AnsEntry.ContractId, splice.ans.AnsEntry]]
  ]

  def lookupEntryByParty(
      partyId: PartyId,
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[splice.ans.AnsEntry.ContractId, splice.ans.AnsEntry]]
  ]

  def lookupEntryByName(name: String, now: CantonTimestamp)(implicit tc: TraceContext): Future[
    Option[ContractWithState[splice.ans.AnsEntry.ContractId, splice.ans.AnsEntry]]
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
      isFirstSv: Boolean,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      createScanAggregatesReader: DbScanStore => ScanAggregatesReader,
      domainMigrationInfo: DomainMigrationInfo,
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
          isFirstSv,
          loggerFactory,
          retryProvider,
          createScanAggregatesReader,
          domainMigrationInfo,
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
        mkFilter(splice.amuletrules.AmuletRules.COMPANION)(co => co.payload.dso == dso)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(splice.ans.AnsRules.COMPANION)(co => co.payload.dso == dso)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(splice.dsorules.DsoRules.COMPANION)(co => co.payload.dso == dso)(
          ScanAcsStoreRowData(_)
        ),
        mkFilter(splice.round.OpenMiningRound.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
            round = Some(contract.payload.round.number),
          )
        },
        mkFilter(splice.round.ClosedMiningRound.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              round = Some(contract.payload.round.number),
            )
        },
        mkFilter(splice.round.IssuingMiningRound.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt =
                Some(Timestamp.assertFromInstant(contract.payload.targetClosesAt)),
              round = Some(contract.payload.round.number),
            )
        },
        mkFilter(splice.round.SummarizingMiningRound.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              round = Some(contract.payload.round.number),
            )
        },
        mkFilter(splice.amulet.FeaturedAppRight.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              featuredAppRightProvider =
                Some(PartyId.tryFromProtoPrimitive(contract.payload.provider)),
            )
        },
        mkFilter(splice.amulet.Amulet.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            amount = Some(contract.payload.amount.initialAmount),
          )
        },
        mkFilter(splice.amulet.LockedAmulet.COMPANION)(co => co.payload.amulet.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract = contract,
              contractExpiresAt =
                Some(Timestamp.assertFromInstant(contract.payload.lock.expiresAt)),
              amount = Some(contract.payload.amulet.amount.initialAmount),
            )
        },
        mkFilter(splice.ans.AnsEntry.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract = contract,
            ansEntryName = Some(contract.payload.name),
            ansEntryOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.expiresAt)),
          )
        },
        mkFilter(splice.decentralizedsynchronizer.MemberTraffic.COMPANION)(vt =>
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
        mkFilter(splice.validatorlicense.ValidatorLicense.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            val roundsCollected = contract.payload.faucetState.map { faucetState =>
              faucetState.lastReceivedFor.number - faucetState.firstReceivedFor.number - faucetState.numCouponsMissed + 1L
            }
            ScanAcsStoreRowData(
              contract = contract,
              validatorLicenseRoundsCollected = Some(roundsCollected.orElse(0L)),
            )
        },
        mkFilter(splice.dso.svstate.SvNodeState.COMPANION)(co => co.payload.dso == dso) {
          contract =>
            ScanAcsStoreRowData(
              contract,
              svParty = Some(PartyId.tryFromProtoPrimitive(contract.payload.sv)),
            )
        },
        mkFilter(splice.dsorules.VoteRequest.COMPANION)(co => co.payload.dso == dso) { contract =>
          ScanAcsStoreRowData(
            contract,
            contractExpiresAt = Some(Timestamp.assertFromInstant(contract.payload.voteBefore)),
            voteActionRequiringConfirmation =
              Some(AcsJdbcTypes.payloadJsonFromDefinedDataType(contract.payload.action)),
            voteRequesterName = Some(contract.payload.requester),
            voteRequestTrackingCid =
              Some(contract.payload.trackingCid.toScala.getOrElse(contract.contractId)),
          )
        },
      ),
    )
  }
}
