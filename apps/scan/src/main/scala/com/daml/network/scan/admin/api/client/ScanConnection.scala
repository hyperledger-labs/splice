package com.daml.network.scan.admin.api.client

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.{coin as coinCodegen}
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.environment.{
  CNLedgerClient,
  HttpAppConnection,
  PackageIdResolver,
  RetryProvider,
  RetryFor,
}
import com.daml.network.scan.admin.api.client.ScanConnection.*
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.store.AcsStoreDump
import com.daml.network.util.{
  CNNodeUtil,
  CoinConfigSchedule,
  Contract,
  ContractWithState,
  DisclosedContracts,
  TemplateJsonDecoder,
}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

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
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config.adminApi, retryProvider, loggerFactory)
    with PackageIdResolver.HasCoinRulesPayload {
  import ScanConnection.GetCoinRulesDomain

  // register the callback to potentially invalidate the CoinRules cache.
  coinLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedCoinRulesCache)
  // and the rounds cache
  coinLedgerClient.registerInactiveContractsCallback(signalPossiblyOutdatedRoundsCache)

  override def serviceName: String = "scan"

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
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "SVC party ID from scan",
      getSvcPartyId(),
      logger,
    )

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
      case Some(ccr @ CachedCoinRules(_, coinRules)) if ccr validAsOf now =>
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

  def getCoinRulesDomain: GetCoinRulesDomain = { () => implicit tc =>
    getCoinRules()
      .flatMap(
        _.state.fold(
          Future.successful,
          Future failed Status.FAILED_PRECONDITION
            .withDescription("CoinRules is in-flight, no current global domain")
            .asRuntimeException(),
        )
      )
  }

  def getCoinRulesPayload()(implicit tc: TraceContext): Future[CoinRules] =
    getCoinRules().map(_.payload)

  def getLatestOpenMiningRound()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]] = {
    for {
      (openRounds, _) <- getOpenAndIssuingMiningRounds()
      now = clock.now
      openRound = CNNodeUtil.selectLatestOpenMiningRound(now, openRounds)
    } yield openRound
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
    getCoinRules().flatMap { coinRules =>
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
          coinRules.contractId,
          openMiningRound.contractId,
          featured.map(_.contractId).toJava,
        ),
        DisclosedContracts(coinRules, openMiningRound),
      )
    }
  }

  def getAppTransferContextForRound(providerPartyId: PartyId, round: Round)(implicit
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
      context.openMiningRounds.find(_.payload.round == round) match {
        case Some(openMiningRound) =>
          Right(
            (
              new coinCodegen.AppTransferContext(
                coinRules.contractId,
                openMiningRound.contractId,
                featured.map(_.contractId).toJava,
              ),
              DisclosedContracts(coinRules, openMiningRound),
            )
          )
        case None => Left("round is not an open mining round")
      }
    }
  }

  def listImportCrates(
      party: PartyId
  ): Future[
    Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]
  ] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListImportCrates(party),
    )

  def getImportShipment(
      party: PartyId
  )(implicit tc: TraceContext): Future[AcsStoreDump.ImportShipment] = {
    for {
      // Important: we need to query the open-round before we list crates, as the availability of an open round signals
      // that all crates have been ingested in scan.
      (openRounds, _) <- getOpenAndIssuingMiningRounds()
      // We're explicitly not checking for the round to be open, as we only need it to supply the CoinPrice for scan.
      openRound = openRounds
        .collectFirst(Function.unlift(_.toAssignedContract))
        .getOrElse(
          throw Status.Code.FAILED_PRECONDITION.toStatus
            .withDescription(
              show"There is at least one open round in $openRounds that is assigned to a domain."
            )
            .asRuntimeException()
        )
      crates <- listImportCrates(party)
    } yield AcsStoreDump.ImportShipment(openRound, crates)
  }

  def listSvcSequencers(): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListSvcSequencers(),
    )
  }
}

object ScanConnection {
  def apply(
      coinLedgerClient: CNLedgerClient,
      config: ScanAppClientConfig,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[ScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new ScanConnection(coinLedgerClient, config, clock, retryProvider, loggerFactory)
    )

  private case class CachedCoinRules(
      cacheValidUntil: CantonTimestamp,
      coinRules: ContractWithState[CoinRules.ContractId, CoinRules],
  ) {
    def validAsOf(now: CantonTimestamp): Boolean =
      now.isBefore(cacheValidUntil) && coinRules.state.fold(
        assignment =>
          CoinConfigSchedule(coinRules)
            .getConfigAsOf(now)
            .globalDomain
            .activeDomain == assignment.toProtoPrimitive,
        false,
      )
  }

  private case class CachedMiningRounds(
      cacheValidUntil: Option[CantonTimestamp] = None,
      sortedOpenMiningRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]] =
        Seq(),
      sortedIssuingMiningRounds: Seq[
        ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]
      ] = Seq(),
  ) {
    def validAsOf(now: CantonTimestamp, coinRules: ContractWithState[?, CoinRules]): Boolean =
      cacheValidUntil.exists(validUntil => now.isBefore(validUntil)) && {
        val states = (sortedOpenMiningRounds.view ++ sortedIssuingMiningRounds).map(_.state).toSet
        states.sizeIs <= 1 && states.forall(
          _.fold(
            assignment =>
              CoinConfigSchedule(coinRules)
                .getConfigAsOf(now)
                .globalDomain
                .activeDomain == assignment.toProtoPrimitive,
            false,
          )
        )
      }

    def getRoundTuple: (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    ) =
      (sortedOpenMiningRounds, sortedIssuingMiningRounds)

    def containsContractId(contractId: String): Boolean =
      (sortedOpenMiningRounds.view ++ sortedIssuingMiningRounds).exists { c =>
        (c.contractId.contractId: String) == contractId
      }

    def describeRounds = s"following issuing rounds: ${sortedIssuingMiningRounds
        .map(_.payload.round.number)}, and following open rounds: ${sortedOpenMiningRounds.map(_.payload.round.number)}"
  }

  type GetCoinRulesDomain = () => TraceContext => Future[DomainId]
}

/** Connection to the admin API of CC Scan usable for version and availability checks
  * before a ledger connection is available.
  */
// TODO(tech-debt) consider removing this if we stop doing early version checks
class MinimalScanConnection(
    config: ScanAppClientConfig,
    retryProvider: RetryProvider,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(config.adminApi, retryProvider, loggerFactory) {
  override def serviceName: String = "scan"
}
object MinimalScanConnection {
  def apply(
      config: ScanAppClientConfig,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      tc: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[MinimalScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new MinimalScanConnection(config, retryProvider, loggerFactory)
    )
}
