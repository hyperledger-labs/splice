package com.daml.network.scan.store.memory

import cats.implicits.*
import cats.kernel.Monoid
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.codegen.java.cc.globaldomain.MemberTraffic
import com.daml.network.codegen.java.cc.validatorlicense.ValidatorLicense
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.store.{
  AppRewardTxLogEntry,
  BalanceChangeTxLogEntry,
  ClosedMiningRoundTxLogEntry,
  ExtraTrafficPurchaseTxLogEntry,
  OpenMiningRoundTxLogEntry,
  ScanStore,
  SortOrder,
  TxLogEntry,
  ValidatorRewardTxLogEntry,
}
import com.daml.network.scan.store.db.ScanAggregator
import com.daml.network.store.{
  HardLimit,
  InMemoryCNNodeAppStore,
  Limit,
  LimitHelpers,
  PageLimit,
  TxLogStore,
}
import com.daml.network.util.{Contract, ContractWithState}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, Member, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.*
import java.time.Instant
import com.daml.network.scan.store.SortOrder.Ascending
import com.daml.network.scan.store.SortOrder.Descending
import com.daml.network.scan.store.TxLogEntry.RewardTxLogEntry

class InMemoryScanStore(
    override val serviceUserPrimaryParty: PartyId,
    override val svcParty: PartyId,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[TxLogEntry]
    with ScanStore
    with LimitHelpers {

  def aggregate()(implicit
      tc: TraceContext
  ) = Future.successful(None)

  override def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CoinRules.ContractId, CoinRules]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(CoinRules.COMPANION, HardLimit.tryCreate(1))
    } yield contracts.headOption

  override def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CnsRules.ContractId, CnsRules]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(CnsRules.COMPANION, HardLimit.tryCreate(1))
    } yield contracts.headOption

  override def lookupSvcRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvcRules.ContractId, SvcRules]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(SvcRules.COMPANION, HardLimit.tryCreate(1))
    } yield contracts.headOption

  override def getTotalCoinBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = {
    for {
      totalCoinBalance <- multiDomainAcsStore
        .collectTxLogEntries {
          case balanceChange: BalanceChangeTxLogEntry if balanceChange.round <= asOfEndOfRound =>
            balanceChange
        }
        .map(txLogEntries =>
          txLogEntries.foldLeft(BigDecimal.valueOf(0.0))((sum, e) =>
            sum + e.changeToInitialAmountAsOfRoundZero - e.changeToHoldingFeesRate * (asOfEndOfRound + 1)
          )
        )
    } yield totalCoinBalance
  }

  override def getWalletBalance(partyId: PartyId, asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = for {
    txLogEntries <- ensureAggregated(asOfEndOfRound) {
      multiDomainAcsStore.collectTxLogEntries(Function unlift {
        case balanceChange: BalanceChangeTxLogEntry if balanceChange.round <= asOfEndOfRound =>
          balanceChange.partyBalanceChanges get partyId
        case _ => None
      })
    }
  } yield txLogEntries.foldMap(pbc =>
    pbc.changeToInitialAmountAsOfRoundZero
      - pbc.changeToHoldingFeesRate * (asOfEndOfRound + 1)
  )

  override def getCoinConfigForRound(
      round: Long
  )(implicit tc: TraceContext): Future[OpenMiningRoundTxLogEntry] = {
    for {
      entry <- multiDomainAcsStore.getLatestTxLogEntry {
        case roundConfig: OpenMiningRoundTxLogEntry =>
          roundConfig.round == round
        case _ => false
      }
    } yield entry match {
      case r: OpenMiningRoundTxLogEntry => r
      case _ =>
        throw Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
    }
  }

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] = {
    type Closed = ClosedMiningRoundTxLogEntry

    multiDomainAcsStore
      .getLatestTxLogEntry {
        case _: Closed =>
          true
        case _ => false
      }
      .collect { case r: Closed => r.round -> r.effectiveAt.getOrElse(throw txMissingField()) }
      .recoverWith { _ =>
        Future.failed(roundNotAggregated())
      }
  }

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    getRewardsCollected(None)

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = getRewardsCollected(Some(round))

  private def getRewardsCollected(round: Option[Long]): Future[BigDecimal] = {
    for {
      ret <- multiDomainAcsStore
        .collectTxLogEntries {
          case reward: RewardTxLogEntry if round.fold(true)(_ == reward.round) =>
            reward
        }
        .map(rewards => rewards.foldLeft(BigDecimal.valueOf(0.0))((sum, r) => sum + r.amount))
    } yield ret
  }

  private def sumRewardsCollectedInRound(
      round: Long,
      rewardTypeFilter: (RewardTxLogEntry) => Boolean,
  ) =
    for {
      ret <- multiDomainAcsStore
        .collectTxLogEntries {
          case reward: RewardTxLogEntry if reward.round == round && rewardTypeFilter(reward) =>
            reward
        }
        .map(rewards =>
          rewards.foldLeft(Map[PartyId, BigDecimal]())((m, r) => m |+| Map((r.party -> r.amount)))
        )
    } yield ret

  private def getTopRewardRecipients(
      asOfEndOfRound: Long,
      limit: Int,
      rewardTypeFilter: (RewardTxLogEntry) => Boolean,
  )(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    for {
      perRound <- ensureAggregated(asOfEndOfRound) {
        // TODO(#2930): for now we assume that the number of rewards per round is small enough that querying the log by round
        // provides small enough partitioning of the result, thus no further pagination of the tx log query is required.
        (0L to asOfEndOfRound).toList.traverse(
          sumRewardsCollectedInRound(_, rewardTypeFilter)
        )
      }
    } yield {
      Monoid.combineAll(perRound).toSeq.sortWith(_._2 > _._2).slice(0, limit)
    }

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    getTopRewardRecipients(
      asOfEndOfRound,
      limit,
      (_ match {
        case _: AppRewardTxLogEntry => true
        case _ => false
      }),
    )

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    getTopRewardRecipients(
      asOfEndOfRound,
      limit,
      (_ match {
        case _: ValidatorRewardTxLogEntry => true
        case _ => false
      }),
    )

  override def listEntries(namePrefix: String, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = for {
    list <- multiDomainAcsStore.filterContracts(
      CnsEntry.COMPANION,
      (entry: Contract[?, CnsEntry]) => entry.payload.name.startsWith(namePrefix),
      limit,
    )
  } yield applyLimit("listEntries", limit, list)

  override def lookupEntryByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = for {
    entryContracts <- multiDomainAcsStore.filterContracts(
      CnsEntry.COMPANION,
      (entry: Contract[?, CnsEntry]) => entry.payload.user == partyId.toProtoPrimitive,
    )
  } yield entryContracts.sortBy(_.payload.name).headOption

  override def lookupEntryByName(name: String)(implicit tc: TraceContext): Future[
    Option[ContractWithState[CnsEntry.ContractId, CnsEntry]]
  ] = multiDomainAcsStore.findContract(CnsEntry.COMPANION)((entry: Contract[?, CnsEntry]) =>
    entry.payload.name == name
  )

  override def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: PageLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionTxLogEntry]] = Future.successful {
    val fromEnd = multiDomainAcsStore.getQueue.view
    val fromBeginning = multiDomainAcsStore.getQueue.view.reverse
    val entries = sortOrder match {
      case Descending =>
        pageEndEventId.fold(
          TxLogStore.firstPage[TxLogEntry, TxLogEntry.TransactionTxLogEntry](fromEnd, limit)
        )(endId =>
          TxLogStore.nextPage[TxLogEntry, TxLogEntry.TransactionTxLogEntry](fromEnd, endId, limit)(
            _.eventId
          )
        )
      case Ascending =>
        pageEndEventId.fold(
          TxLogStore.firstPage[TxLogEntry, TxLogEntry.TransactionTxLogEntry](fromBeginning, limit)
        )(endId =>
          TxLogStore.nextPage[TxLogEntry, TxLogEntry.TransactionTxLogEntry](
            fromBeginning,
            endId,
            limit,
          )(_.eventId)
        )
    }
    entries.take(limit.limit)

  }

  private def trafficPurchasesByValidatorInRound(
      round: Long
  ): Future[Map[PartyId, ValidatorPurchasedTraffic]] =
    multiDomainAcsStore
      .collectTxLogEntries {
        case indexRecord: ExtraTrafficPurchaseTxLogEntry if indexRecord.round == round =>
          indexRecord
      }
      .map(
        _.foldLeft(Map.empty[PartyId, ValidatorPurchasedTraffic]) { (acc, e) =>
          acc.updatedWith(e.validator) {
            case None =>
              Some(
                ValidatorPurchasedTraffic(
                  e.validator,
                  1,
                  e.trafficPurchased,
                  e.ccSpent,
                  e.round,
                )
              )
            case Some(t) =>
              Some(
                ValidatorPurchasedTraffic(
                  t.validator,
                  t.numPurchases + 1,
                  t.totalTrafficPurchased + e.trafficPurchased,
                  t.totalCcSpent + e.ccSpent,
                  Math.max(t.lastPurchasedInRound, e.round),
                )
              )
          }
        }
      )

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorPurchasedTraffic]] = {
    def combine(t1: ValidatorPurchasedTraffic, t2: ValidatorPurchasedTraffic) = {
      require(t1.validator == t2.validator)
      ValidatorPurchasedTraffic(
        t1.validator,
        t1.numPurchases + t2.numPurchases,
        t1.totalTrafficPurchased + t2.totalTrafficPurchased,
        t1.totalCcSpent + t2.totalCcSpent,
        Math.max(t1.lastPurchasedInRound, t2.lastPurchasedInRound),
      )
    }

    for {
      perRound <- ensureAggregated(asOfEndOfRound) {
        (0L to asOfEndOfRound).toList.traverse(trafficPurchasesByValidatorInRound)
      }
    } yield {
      perRound
        .foldLeft(Map.empty[PartyId, ValidatorPurchasedTraffic])((acc, forRound) => {
          acc ++ forRound.map { case (k, v) =>
            k -> combine(acc.getOrElse(k, ValidatorPurchasedTraffic(k, 0, 0, 0, 0)), v)
          }
        })
        .toSeq
        .map(_._2)
        .sortWith(_.totalTrafficPurchased > _.totalTrafficPurchased)
        .take(limit)
    }
  }

  override def getTopValidatorLicenses(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] =
    throw new NotImplementedError("Not gonna bother.")

  override def getTotalPurchasedMemberTraffic(memberId: Member, domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Long] = {
    multiDomainAcsStore
      .listContractsOnDomain(MemberTraffic.COMPANION, domainId)
      .map(
        _.filter(_.payload.memberId == memberId.toProtoPrimitive)
          .map(_.payload.totalPurchased.toLong)
          .sum
      )
  }

  def listImportCrates(receiverParty: PartyId, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]
  ] =
    multiDomainAcsStore.filterContracts(
      cc.coinimport.ImportCrate.COMPANION,
      (co: Contract[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]) =>
        co.payload.receiver == receiverParty.toProtoPrimitive,
      limit,
    )

  override def findFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] =
    multiDomainAcsStore
      .findContract(coinCodegen.FeaturedAppRight.COMPANION) {
        (co: Contract[?, coinCodegen.FeaturedAppRight]) =>
          co.payload.provider == providerPartyId.toProtoPrimitive
      }

  // TODO(#8152) InMemoryScanStore will be deleted.
  override def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] =
    Future.successful(None)

  // TODO(#8152) InMemoryScanStore will be deleted.
  override def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]] = Future.successful(Seq.empty)

  // TODO(#8152) InMemoryScanStore will be deleted.
  override def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]] = Future.successful(Seq.empty)

  override def close(): Unit = {
    super.close()
  }
}
