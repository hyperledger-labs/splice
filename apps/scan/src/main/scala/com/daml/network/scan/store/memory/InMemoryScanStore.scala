package com.daml.network.scan.store.memory

import cats.implicits.*
import cats.kernel.Monoid
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.globaldomain.ValidatorTraffic
import com.daml.network.environment.{CNLedgerConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.ValidatorPurchasedTraffic
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.{ScanStore, ScanTxLogParser}
import com.daml.network.store.{InMemoryCNNodeAppStore, TxLogStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.collection.immutable.Map
import scala.concurrent.*

import java.time.Instant

class InMemoryScanStore(
    override val svcParty: PartyId,
    override protected[this] val scanConfig: ScanAppBackendConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val futureSupervisor: FutureSupervisor,
    override protected val connection: CNLedgerConnection,
    override protected val retryProvider: RetryProvider,
)(implicit
    ec: ExecutionContext
) extends InMemoryCNNodeAppStore[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry]
    with ScanStore {

  override lazy val acsContractFilter = ScanStore.contractFilter(svcParty, scanConfig)

  override def getTotalCoinBalance()(implicit
      tc: TraceContext
  ): Future[(BigDecimal, BigDecimal)] = {
    for {
      // TODO(#2930): This is a very naive preliminary implementation that will be completely replaced soon
      domainId <- defaultAcsDomainIdF
      coins <- multiDomainAcsStore.listContractsOnDomain(
        coinCodegen.Coin.COMPANION,
        domainId,
      )
      totalCoins = coins.foldLeft(BigDecimal(0.0))((b, coin) =>
        b + coin.payload.amount.initialAmount
      )
      lockedCoins <- multiDomainAcsStore.listContractsOnDomain(
        coinCodegen.LockedCoin.COMPANION,
        domainId,
      )
      totalLockedCoins = lockedCoins.foldLeft(BigDecimal(0.0))((b, lockedCoin) =>
        b + lockedCoin.payload.coin.amount.initialAmount
      )
    } yield {
      (
        totalCoins,
        totalLockedCoins,
      )
    }
  }

  override def getCoinConfigForRound(
      round: Long
  )(implicit tc: TraceContext): Future[ScanTxLogParser.TxLogEntry.OpenMiningRoundLogEntry] = {
    for {
      roundConfig <- txLogReader.getLatestTxLogEntry((indexRecord) =>
        indexRecord match {
          case roundConfig: ScanTxLogParser.TxLogIndexRecord.OpenMiningRoundIndexRecord =>
            roundConfig.round == round
          case _ => false
        }
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
    // TODO(#2930): For now, this is simply the latest closed mining round, since we are computing everything on-demand
    // Note that for all existing (and currently planned) queries, we could make this also the latest open mining round
    // that has been archived, but for now we're going for the later event of the round closing, to be a bit more future-proof.
    for {
      round <- txLog.getLatestTxLogIndex((indexRecord) =>
        indexRecord match {
          case _: ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord => true
          case _ => false
        }
      )
    } yield {
      round match {
        case r: ScanTxLogParser.TxLogIndexRecord.ClosedMiningRoundIndexRecord =>
          (r.round, r.effectiveAt)
        case _ =>
          throw Status.INTERNAL.withDescription("Unexpected log entry type").asRuntimeException()
      }
    }
  }

  override def verifyDataExistsForEndOfRound(
      asOfEndOfRound: Long
  )(implicit tc: TraceContext): Future[Unit] = {
    if (asOfEndOfRound < 0) {
      throw Status.OUT_OF_RANGE
        .withDescription("Round numbers cannot be negative")
        .asRuntimeException()
    }
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
      log: TxLogStore[ScanTxLogParser.TxLogIndexRecord, ScanTxLogParser.TxLogEntry],
      round: Long,
      rewardTypeFilter: (ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord) => Boolean,
  ) =
    for {
      ret <- log
        .getTxLogIndicesByFilter(_ match {
          case reward: ScanTxLogParser.TxLogIndexRecord.RewardIndexRecord
              if reward.round == round && rewardTypeFilter(reward) =>
            true
          case _ => false
        })
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
        sumRewardsCollectedInRound(txLog, _, rewardTypeFilter)
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

  private def listValidatorTrafficContracts()(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]] = {
    // TODO(#4913): read from all domains in the global domain
    defaultAcsDomainIdF.flatMap(defaultDomainId =>
      multiDomainAcsStore
        .listContractsOnDomain(
          ValidatorTraffic.COMPANION,
          defaultDomainId,
          _ => true,
        )
    )
  }

  override def getTopValidatorsByPurchasedTraffic(limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ValidatorPurchasedTraffic]] = {
    listValidatorTrafficContracts().map(
      _.groupBy(_.payload.validator).toSeq
        .map { case (validator, trafficSeq) =>
          trafficSeq.foldLeft(
            ValidatorPurchasedTraffic(
              PartyId.tryFromProtoPrimitive(validator),
              0,
              0,
              0,
              0,
              Instant.MIN,
            )
          )((t1, t2) =>
            ValidatorPurchasedTraffic(
              t1.validator,
              t1.numPurchases + t2.payload.numPurchases,
              t1.totalTrafficPurchased + t2.payload.totalPurchased,
              t1.totalCcSpent + t2.payload.ccSpent,
              t1.totalUsdSpent + t2.payload.usdSpent,
              if (t2.payload.lastPurchasedAt.isAfter(t1.lastPurchasedAt))
                t2.payload.lastPurchasedAt
              else t1.lastPurchasedAt,
            )
          )
        }
        .sortWith(_.totalTrafficPurchased > _.totalTrafficPurchased)
        .slice(0, limit)
    )
  }

  override def getTotalPaidValidatorTraffic(
      validatorParty: PartyId
  )(implicit tc: TraceContext): Future[Long] = {
    lookupValidatorTraffic(validatorParty).map {
      case None => 0L
      case Some(validatorTraffic) => validatorTraffic.payload.totalPurchased
    }
  }

  override def close(): Unit = {
    super.close()
  }
}
