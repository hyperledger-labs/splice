package com.daml.network.scan.admin.api.client

import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns.CnsRules
import com.daml.network.environment.CNLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection.{
  CachedCnsRules,
  CachedCoinRules,
  CachedMiningRounds,
}
import com.daml.network.util.ContractWithState
import com.daml.network.util.PrettyInstances.PrettyContractId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.daml.network.util.PrettyInstances.*
import org.apache.pekko.stream.Materializer

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

trait CachingScanConnection extends ScanConnection {

  protected val coinLedgerClient: CNLedgerClient
  protected val coinRulesCacheTimeToLive: NonNegativeFiniteDuration

  private val coinRulesCache: AtomicReference[Option[CachedCoinRules]] =
    new AtomicReference(None)

  private val cnsRulesCache: AtomicReference[Option[CachedCnsRules]] =
    new AtomicReference(None)

  private val cachedRounds: AtomicReference[CachedMiningRounds] =
    new AtomicReference(CachedMiningRounds())

  // register the callback to potentially invalidate the CoinRules cache.
  coinLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedCoinRulesCache)
  // and the rounds cache
  coinLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedRoundsCache)
  // and also nuke everything when we get an error that we're trying to downgrade.
  coinLedgerClient.registerContractDowngradeErrorCallback(() => signalOutdatedCache())

  /** We cache the CoinRules contract, but it may be come outdated if, e.g., the SVC updates the config schedule.
    * The inactive-contracts error message that the ledger returns does not specify the template-id, thus we need
    * to check for each inactive-contract we receive from the ledger that the failure was not caused by an outdated cache
    * of the CoinRules.
    */
  private def signalPossiblyOutdatedCoinRulesCache(inactiveContract: String): Unit =
    coinRulesCache.get() match {
      case Some(CachedCoinRules(_, cachedContract))
          if (cachedContract.contractId.contractId: String) == inactiveContract =>
        logger.info(
          show"Invalidating the CoinRules cache with value ${PrettyContractId(cachedContract.contract)}"
        )(TraceContext.empty)
        coinRulesCache.set(None)
      case _ => ()
    }

  private def signalPossiblyOutdatedRoundsCache(inactiveContract: String): Unit = {
    val rounds = cachedRounds.get()
    if (rounds containsContractId inactiveContract) {
      logger.debug(
        show"Invalidating the rounds cache at ${rounds.describeRounds}"
      )(TraceContext.empty)
      cachedRounds.set(CachedMiningRounds())
    } else ()
  }

  private def signalOutdatedCache(): Unit = {
    logger.debug("Invalidating CoinRules and rounds cache after a failed contract downgrade")(
      TraceContext.empty
    )
    coinRulesCache.set(None)
    cachedRounds.set(CachedMiningRounds())
  }

  override def getCoinRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[CoinRules.ContractId, CoinRules]] = {
    val now = clock.now
    coinRulesCache.get() match {
      case Some(ccr @ CachedCoinRules(_, coinRules)) if ccr validAsOf now =>
        Future.successful(coinRules)
      case cacheO =>
        // Note that here and at other caches in this class, multiple concurrent cache misses result in multiple
        // requests that are not deduplicated against each other. We accept that as we expect low concurrency by default.
        logger.debug(
          s"CoinRules cache is empty or outdated, retrieving CoinRules from CC scan."
        )
        for {
          coinRules <- runGetCoinRulesWithState(cacheO.map(_.coinRules))
        } yield {
          coinRulesCache.set(
            Some(
              CachedCoinRules(
                now.add(coinRulesCacheTimeToLive.asJava),
                coinRules,
              )
            )
          )
          coinRules
        }
    }
  }
  protected def runGetCoinRulesWithState(
      cachedCoinRules: Option[ContractWithState[CoinRules.ContractId, CoinRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CoinRules.ContractId, CoinRules]]

  override def getCnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[CnsRules.ContractId, CnsRules]] = {
    val now = clock.now
    getCoinRulesWithState().flatMap { coinRules =>
      cnsRulesCache.get() match {
        case Some(ccr @ CachedCnsRules(_, cnsRules)) if ccr.validAsOf(now, coinRules) =>
          Future.successful(cnsRules)
        case cacheO =>
          logger.debug(
            s"cnsRules cache is empty or outdated, retrieving CnsRules from CC scan."
          )
          for {
            cnsRules <- runGetCnsRules(cacheO.map(_.cnsRules))
          } yield {
            cnsRulesCache.set(
              Some(
                CachedCnsRules(
                  now.add(coinRulesCacheTimeToLive.asJava),
                  cnsRules,
                )
              )
            )
            cnsRules
          }
      }
    }
  }

  protected def runGetCnsRules(
      cachedCnsRules: Option[ContractWithState[CnsRules.ContractId, CnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CnsRules.ContractId, CnsRules]]

  override def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ] = {
    val now = clock.now
    val cache = cachedRounds.get()
    getCoinRulesWithState().flatMap { coinRules =>
      if (cache.validAsOf(now, coinRules)) {
        logger.info(
          s"Using the client-cache (validUntil ${cache.cacheValidUntil}) to load ${cache.describeRounds}."
        )
        Future.successful(cache.getRoundTuple)
      } else {
        logger.debug(
          s"querying the scan app for the latest round information because the cache expired at ${cache.cacheValidUntil}"
        )
        for {
          (openRounds, issuingRounds, ttlInMicros) <- runGetOpenAndIssuingMiningRounds(
            cache.sortedOpenMiningRounds,
            cache.sortedIssuingMiningRounds,
          )

        } yield {
          val newValidUntil = now.add(Duration.ofNanos(ttlInMicros.longValue * 1000))
          val newRoundsCache = CachedMiningRounds(
            Some(newValidUntil),
            openRounds,
            issuingRounds,
          )
          logger.info(s"New rounds-cache is $newRoundsCache.")
          cachedRounds.set(newRoundsCache)
          cachedRounds.get().getRoundTuple
        }
      }
    }
  }

  protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ]
}
