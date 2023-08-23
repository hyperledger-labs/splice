package com.daml.network.scan.store.memory

import cats.implicits.*
import cats.kernel.Monoid
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.codegen.java.cn.cns.CnsRules
import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.{ScanStore, ScanTxLogParser}
import com.daml.network.store.TxLogStore.TransactionTreeSource
import com.daml.network.store.{HardLimit, InMemoryCNNodeAppStore}
import com.daml.network.util.{Contract, ContractWithState}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.*
import java.time.Instant

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected[this] val scanConfig: ScanAppBackendConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val transactionTreeSource: TransactionTreeSource,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry]
    with ScanStore {

  override def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CoinRules.ContractId, CoinRules]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(cc.coin.CoinRules.COMPANION, HardLimit(1))
    } yield contracts.headOption

  override def lookupCoinRulesV1Test()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CoinRulesV1Test.ContractId, CoinRulesV1Test]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(CoinRulesV1Test.COMPANION, HardLimit(1))
    } yield contracts.headOption

  override def lookupCnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[CnsRules.ContractId, CnsRules]]] =
    for {
      contracts <- multiDomainAcsStore
        .listContracts(CnsRules.COMPANION, HardLimit(1))
    } yield contracts.headOption

  override def getTotalCoinBalance(asOfEndOfRound: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = {
    for {
      totalCoinBalance <- txLog
        .getTxLogIndicesByFilter(_ match {
          case balanceChange: ScanTxLogParser.TxLogIndexRecord.BalanceChangeIndexRecord =>
            balanceChange.round <= asOfEndOfRound
          case _ => false
        })
        .map(txLogEntries =>
          txLogEntries.foldLeft(BigDecimal.valueOf(0.0))((sum, entry) =>
            entry match {
              case e: ScanTxLogParser.TxLogIndexRecord.BalanceChangeIndexRecord =>
                sum + e.changeToInitialAmountAsOfRoundZero - e.changeToHoldingFeesRate * (asOfEndOfRound + 1)
              case _ =>
                throw Status.INTERNAL
                  .withDescription("Unexpected log entry type")
                  .asRuntimeException()
            }
          )
        )
    } yield totalCoinBalance
  }

  override def getCoinConfigForRound(
      round: Long
  )(implicit tc: TraceContext): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry] = {
    for {
      indexRecord <- txLog.getLatestTxLogIndex {
        case roundConfig: ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord =>
          roundConfig.round == round
        case _ => false
      }
      roundConfig <- loadTxLogEntry(
        txLogReader,
        indexRecord.eventId,
        indexRecord.domainId,
        indexRecord.acsContractId,
        ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord.dbType,
      )
    } yield {
      roundConfig match {
        case r: ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry => r
        case _ =>
          throw Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
      }
    }
  }

  override def getRoundOfLatestData()(implicit tc: TraceContext): Future[(Long, Instant)] = {
    // TODO(#2930): For now, this is the latest closed mining round which has a corresponding open mining round in the log, since we are computing everything on-demand
    // Note that for all existing (and currently planned) queries, we could make this also the latest open mining round
    // that has been archived, but for now we're going for the later event of the round closing, to be a bit more future-proof.

    type Closed = ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord
    type Open = ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord

    txLog
      .findLatestTxLogIndex[Closed, Map[Long, Closed]](Map.empty) {
        case (closed, r: Closed) =>
          (closed + (r.round -> r)).asRight
        case (closed, r: Open) =>
          closed.get(r.round).fold(closed.asRight[Closed]) { c: Closed =>
            c.asLeft[Map[Long, Closed]]
          }
        case (z, _) => z.asRight
      }
      .map(r => r.round -> r.effectiveAt)
  }

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    getRewardsCollected(None)

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = getRewardsCollected(Some(round))

  private def getRewardsCollected(round: Option[Long]): Future[BigDecimal] = {
    for {
      ret <- txLog
        .getTxLogIndicesByFilter(_ match {
          case reward: ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord =>
            round.fold(true)(_ == reward.round)
          case _ => false
        })
        .map(rewards =>
          rewards.foldLeft(BigDecimal.valueOf(0.0))((sum, reward) =>
            reward match {
              case r: ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord =>
                sum + r.amount
              case _ =>
                throw Status.INTERNAL
                  .withDescription("Unexpected log entry type")
                  .asRuntimeException()
            }
          )
        )
    } yield ret
  }

  private def sumRewardsCollectedInRound(
      round: Long,
      rewardTypeFilter: (ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord) => Boolean,
  ) =
    for {
      ret <- txLog
        .getTxLogIndicesByFilter {
          case reward: ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord
              if reward.round == round && rewardTypeFilter(reward) =>
            true
          case _ => false
        }
        .map(rewards =>
          rewards.foldLeft(Map[PartyId, BigDecimal]())((m, reward) =>
            reward match {
              case r: ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord =>
                m |+| Map((r.party -> r.amount))
              case _ =>
                throw Status.INTERNAL
                  .withDescription("Unexpected log entry type")
                  .asRuntimeException()
            }
          )
        )
    } yield ret

  private def getTopRewardRecipients(
      asOfEndOfRound: Long,
      limit: Int,
      rewardTypeFilter: (ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord) => Boolean,
  )(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] =
    for {
      _ <- verifyDataExistsForEndOfRound(asOfEndOfRound)
      // TODO(#2930): for now we assume that the number of rewards per round is small enough that querying the log by round
      // provides small enough partitioning of the result, thus no further pagination of the tx log query is required.
      perRound <- (0L to asOfEndOfRound).toList.traverse(
        sumRewardsCollectedInRound(_, rewardTypeFilter)
      )
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
        case _: ScanTxLogParser.TxLogIndexRecord.AppRewardIndexRecord => true
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
        case _: ScanTxLogParser.TxLogIndexRecord.ValidatorRewardIndexRecord => true
        case _ => false
      }),
    )

  override def listRecentActivity(limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ScanTxLogParser.TxLogEntry.RecentActivityLogEntry]] = {
    for {
      indexes <- txLog
        .collectTxLogIndicesType[ScanTxLogParser.TxLogIndexRecord.RecentActivityIndexRecord]
      records <- indexes
        .traverse { index =>
          loadTxLogEntry(
            txLogReader,
            index.eventId,
            index.domainId,
            index.acsContractId,
            index.companion.dbType,
          )
        }
        .map {
          _.collect { case entry: ScanTxLogParser.TxLogEntry.RecentActivityLogEntry =>
            entry
          }.take(limit)
        }
    } yield records
  }

  private def trafficPurchasesByValidatorInRound(
      round: Long
  ): Future[Map[PartyId, ValidatorPurchasedTraffic]] =
    txLog
      .getTxLogIndicesByFilter {
        case indexRecord: ScanTxLogParser.TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord
            if indexRecord.round == round =>
          true
        case _ => false
      }
      .map(
        _.foldLeft(Map.empty[PartyId, ValidatorPurchasedTraffic])((acc, entry) => {
          entry match {
            case e: ScanTxLogParser.TxLogIndexRecord.ExtraTrafficPurchaseIndexRecord =>
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
            case _ =>
              throw Status.INTERNAL
                .withDescription("Unexpected log entry type")
                .asRuntimeException()
          }
        })
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
      _ <- verifyDataExistsForEndOfRound(asOfEndOfRound)
      perRound <- (0L to asOfEndOfRound).toList.traverse(trafficPurchasesByValidatorInRound)
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

  override def lookupValidatorTraffic(validatorParty: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(ValidatorTraffic.COMPANION)(
        _,
        contract => contract.payload.validator == validatorParty.toProtoPrimitive,
      )
    )

  def listImportCrates(receiverParty: PartyId)(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]
  ] =
    multiDomainAcsStore.filterContracts(
      cc.coinimport.ImportCrate.COMPANION,
      (co: Contract[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]) =>
        co.payload.receiver == receiverParty.toProtoPrimitive,
    )

  override def findFeaturedAppRight(
      domainId: DomainId,
      providerPartyId: PartyId,
  )(implicit tc: TraceContext): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] = {
    multiDomainAcsStore.findContractOnDomain(
      coinCodegen.FeaturedAppRight.COMPANION
    )(
      domainId,
      (co: Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]) =>
        co.payload.provider == providerPartyId.toProtoPrimitive,
    )
  }

  override def close(): Unit = {
    super.close()
  }
}
