package com.daml.network.scan.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.codegen.java.cc.api.v1.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.v1test.coin.CoinRulesV1Test
import com.daml.network.environment.{CNLedgerClient, HttpAppConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.ScanConnection.*
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.store.MultiDomainAcsStore.ContractWithState
import com.daml.network.util.{CNNodeUtil, Contract, TemplateJsonDecoder, DisclosedContracts}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the SVC party id.
  */
final class ScanConnection private (
    coinLedgerClient: CNLedgerClient,
    config: ScanAppClientConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config.adminApi, retryProvider, timeouts, loggerFactory) {

  // register the callback to potentially invalidate the CoinRules cache.
  coinLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedCoinRulesCache)

  override def serviceName: String = "scan"

  /** We cache the CoinRules contract, but it may be come outdated if, e.g., the SVC updates the config schedule.
    * The inactive-contracts error message that the ledger returns does not specify the template-id, thus we need
    * to check for each inactive-contract we receive from the ledger that the failure was not caused by an outdated cache
    * of the CoinRules.
    */
  private def signalPossiblyOutdatedCoinRulesCache(inactiveContract: String): Unit =
    coinRulesCache.get() match {
      case Some(CachedCoinRules(_, cachedContract))
          if (cachedContract.contract.contractId.contractId: String) == inactiveContract =>
        logger.info(
          show"Invalidating the CoinRules cache with value ${PrettyContractId(cachedContract.contract)}"
        )(TraceContext.empty)
        coinRulesCache.set(None)
      case _ => ()
    }

  // cached SVC reference.
  private val svcRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  private val coinRulesCache: AtomicReference[Option[CachedCoinRules]] =
    new AtomicReference(None)

  private val cachedRounds: AtomicReference[CachedMiningRounds] =
    new AtomicReference(CachedMiningRounds())

  /** Query for the SVC party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  // NOTE: made private as there does not seem to be another use of calling this w/o retries right now.
  private def getSvcPartyId(): Future[PartyId] = {
    val prev = svcRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runHttpCmd(config.adminApi.url, HttpScanAppClient.GetSvcPartyId(List()))
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          svcRef.set(Some(partyId))
          partyId
        }
    }
  }

  /** Query for the SVC party id, retrying until it succeeds.
    *
    * Intended to be used for app init.
    */
  def getSvcPartyIdWithRetries(): Future[PartyId] =
    retryProvider.getValueWithRetries("SVC party ID from scan", getSvcPartyId(), logger)

  def getTransferContextWithInstances()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[HttpScanAppClient.TransferContextWithInstances] = {
    for {
      openAndIssuingRounds <- getOpenAndIssuingMiningRounds()
      openRounds = openAndIssuingRounds._1
      latestOpenMiningRound = CNNodeUtil.selectLatestOpenMiningRound(clock.now, openRounds)
      coinRules <- getCoinRules()
    } yield TransferContextWithInstances(coinRules, latestOpenMiningRound, openRounds)
  }

  def getCoinRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[CoinRules.ContractId, CoinRules]] = {
    val now = clock.now
    coinRulesCache.get() match {
      case Some(CachedCoinRules(cacheValidUntil, coinRules)) if now.isBefore(cacheValidUntil) =>
        Future.successful(coinRules)
      case cacheO =>
        // Note that here and at other caches in this class, multiple concurrent cache misses result in multiple
        // requests that are not deduplicated against each other. We accept that as we expect low concurrency by default.
        logger.debug(
          s"CoinRules cache is empty or outdated, retrieving CoinRules from CC scan."
        )
        for {
          coinRules <- runHttpCmd(
            config.adminApi.url,
            HttpScanAppClient.GetCoinRules(cacheO.map(_.coinRules)),
          )
        } yield {
          // only cache coinRules if it is not locked.
          // otherwise, an inactive locked coinRules will failed interpretation in the local participant
          // and the cached coinRules contract will never be invalidated as other participant will not be able to validate if it is inactive.
          // TODO(#3933): we can remove this when canton team has completed a proper fix to #3933
          if (!coinRules.contract.payload.lock)
            coinRulesCache.set(
              Some(
                CachedCoinRules(
                  now.add(config.coinRulesCacheTimeToLive.asJava),
                  coinRules,
                )
              )
            )
          coinRules
        }
    }
  }

  def getCoinRulesV1Test()(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[ContractWithState[CoinRulesV1Test.ContractId, CoinRulesV1Test]] = {
    // Note that we did not implement caching here as part of this upgrade PoC
    runHttpCmd(config.adminApi.url, HttpScanAppClient.GetCoinRulesV1Test(None))
  }

  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Contract[OpenMiningRound.ContractId, OpenMiningRound]] = {
    for {
      (openRounds, _) <- getOpenAndIssuingMiningRounds()
      now = clock.now
      openRound = CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
    } yield openRound.contract
  }

  def getOpenAndIssuingMiningRounds()(implicit
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
    if (cache.cacheValidUntil.exists(validUntil => now.isBefore(validUntil))) {
      val rounds = cache.getRoundTuple
      logger.info(
        s"Using the client-cache (validUntil ${cache.cacheValidUntil}) to load following issuing rounds: ${rounds._1
            .map(_.contract.payload.round.number)}, and following open rounds: ${rounds._2
            .map(_.contract.payload.round.number)}."
      )
      Future.successful(rounds)
    } else {
      logger.debug(
        s"querying the scan app for the latest round information because the cache expired at ${cache.cacheValidUntil}"
      )
      for {
        (openRounds, issuingRounds, ttlInMicros) <- runHttpCmd(
          config.adminApi.url,
          HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
            cache.sortedOpenMiningRounds,
            cache.sortedIssuingMiningRounds,
          ),
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

  def approveTaps(validatorParty: PartyId, numTapOperations: Int)(implicit
      ec: ExecutionContext
  ): Future[Boolean] = {
    Future.foldLeft(
      (1 to numTapOperations).map(_ =>
        runHttpCmd(
          config.adminApi.url,
          HttpScanAppClient.CheckAndUpdateValidatorTrafficBalance(validatorParty),
        )
      )
    )(true)(_ && _)
  }

  def getValidatorTrafficBalance(
      validatorParty: PartyId
  )(implicit ec: ExecutionContext): Future[HttpScanAppClient.ValidatorTrafficBalance] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetValidatorTrafficBalance(validatorParty),
    )
  }

  def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  def getAppTransferContext(providerPartyId: PartyId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[(coinCodegen.AppTransferContext, DisclosedContracts.NE)] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules
      val openMiningRound = context.latestOpenMiningRound
      (
        new coinCodegen.AppTransferContext(
          coinRules.contract.contractId.toInterface(coinCodegen.CoinRules.INTERFACE),
          openMiningRound.contract.contractId.toInterface(roundCodegen.OpenMiningRound.INTERFACE),
          featured.map(_.contractId.toInterface(coinCodegen.FeaturedAppRight.INTERFACE)).toJava,
        ),
        DisclosedContracts(coinRules, openMiningRound),
      )
    }
  }

  def getAppTransferContextForRound(providerPartyId: PartyId, round: roundCodegen.Round)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[
    Either[String, (coinCodegen.AppTransferContext, DisclosedContracts.NE)]
  ] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules
      context.openMiningRounds.find(_.contract.payload.round == round) match {
        case Some(openMiningRound) =>
          Right(
            (
              new coinCodegen.AppTransferContext(
                coinRules.contract.contractId.toInterface(coinCodegen.CoinRules.INTERFACE),
                openMiningRound.contract.contractId
                  .toInterface(roundCodegen.OpenMiningRound.INTERFACE),
                featured
                  .map(_.contractId.toInterface(coinCodegen.FeaturedAppRight.INTERFACE))
                  .toJava,
              ),
              DisclosedContracts(coinRules, openMiningRound),
            )
          )
        case None => Left("round is not an open mining round")
      }
    }
  }

}

object ScanConnection {
  def apply(
      coinLedgerClient: CNLedgerClient,
      config: ScanAppClientConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      timeouts: ProcessingTimeout,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[ScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new ScanConnection(coinLedgerClient, config, clock, retryProvider, timeouts, loggerFactory)
    )

  private case class CachedCoinRules(
      cacheValidUntil: CantonTimestamp,
      coinRules: ContractWithState[CoinRules.ContractId, CoinRules],
  ) {}

  private case class CachedMiningRounds(
      cacheValidUntil: Option[CantonTimestamp] = None,
      sortedOpenMiningRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]] =
        Seq(),
      sortedIssuingMiningRounds: Seq[
        ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]
      ] = Seq(),
  ) {
    def getRoundTuple: (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    ) =
      (sortedOpenMiningRounds, sortedIssuingMiningRounds)
  }

}
