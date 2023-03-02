package com.daml.network.scan.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.ledger.api.v1.CommandsOuterClass
import com.daml.network.admin.api.client.AppConnection
import com.daml.network.codegen.java.cc.api.v1.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection.*
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.{CoinUtil, Contract, TemplateJsonDecoder}
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
final class ScanConnection(
    coinLedgerClient: CoinLedgerClient,
    config: ScanAppClientConfig,
    clock: Clock,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends AppConnection(config.adminApi.clientConfig, timeouts, loggerFactory) {

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
          if cachedContract.contractId.contractId == inactiveContract =>
        logger.info(
          show"Invalidating the CoinRules cache with value ${PrettyContractId(cachedContract)}"
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
  def getSvcPartyId()(implicit mat: Materializer): Future[PartyId] = {
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

  def getTransferContextWithInstances()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[HttpScanAppClient.TransferContextWithInstances] = {
    for {
      openAndIssuingRounds <- getOpenAndIssuingMiningRounds()
      openRounds = openAndIssuingRounds._1
      latestOpenMiningRound = CoinUtil.selectLatestOpenMiningRound(clock.now, openRounds)
      coinRules <- getCoinRules()
    } yield TransferContextWithInstances(coinRules, latestOpenMiningRound, openRounds)
  }

  def getCoinRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Contract[CoinRules.ContractId, CoinRules]] = {
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

          coinRulesCache.set(
            Some(CachedCoinRules(now.add(config.coinRulesCacheTimeToLive.duration), coinRules))
          )
          coinRules
        }
    }
  }

  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Contract[OpenMiningRound.ContractId, OpenMiningRound]] = {
    for {
      (openRounds, _) <- getOpenAndIssuingMiningRounds()
      now = clock.now
      openRound = CoinUtil.selectLatestOpenMiningRound(now, openRounds)
    } yield openRound
  }

  def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ] = {
    val now = clock.now
    val cache = cachedRounds.get()
    if (cache.cacheValidUntil.exists(validUntil => now.isBefore(validUntil))) {
      logger.debug(s"Using the client-cache to load the current round information.")
      Future.successful(cache.getRoundTuple)
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
        cachedRounds.set(
          CachedMiningRounds(
            Some(now.add(Duration.ofNanos(ttlInMicros.longValue * 1000))),
            openRounds,
            issuingRounds,
          )
        )
        cachedRounds.get().getRoundTuple
      }
    }

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
  ): Future[(coinCodegen.AppTransferContext, Seq[CommandsOuterClass.DisclosedContract])] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules
      val openMiningRound = context.latestOpenMiningRound
      (
        new coinCodegen.AppTransferContext(
          coinRules.contractId.toInterface(coinCodegen.CoinRules.INTERFACE),
          openMiningRound.contractId.toInterface(roundCodegen.OpenMiningRound.INTERFACE),
          featured.map(_.contractId.toInterface(coinCodegen.FeaturedAppRight.INTERFACE)).toJava,
        ),
        Seq(coinRules.toDisclosedContract, openMiningRound.toDisclosedContract),
      )
    }
  }

  def getAppTransferContextForRound(providerPartyId: PartyId, round: roundCodegen.Round)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[
    Either[String, (coinCodegen.AppTransferContext, Seq[CommandsOuterClass.DisclosedContract])]
  ] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules
      context.openMiningRounds.find(_.payload.round == round) match {
        case Some(openMiningRound) =>
          Right(
            (
              new coinCodegen.AppTransferContext(
                coinRules.contractId.toInterface(coinCodegen.CoinRules.INTERFACE),
                openMiningRound.contractId.toInterface(roundCodegen.OpenMiningRound.INTERFACE),
                featured
                  .map(_.contractId.toInterface(coinCodegen.FeaturedAppRight.INTERFACE))
                  .toJava,
              ),
              Seq(coinRules.toDisclosedContract, openMiningRound.toDisclosedContract),
            )
          )
        case None => Left("round is not an open mining round")
      }
    }
  }

}

object ScanConnection {

  private case class CachedCoinRules(
      cacheValidUntil: CantonTimestamp,
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  ) {}

  private case class CachedMiningRounds(
      cacheValidUntil: Option[CantonTimestamp] = None,
      sortedOpenMiningRounds: Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]] = Seq(),
      sortedIssuingMiningRounds: Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]] =
        Seq(),
  ) {
    def getRoundTuple: (
        Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
    ) =
      (sortedOpenMiningRounds, sortedIssuingMiningRounds)
  }

}
