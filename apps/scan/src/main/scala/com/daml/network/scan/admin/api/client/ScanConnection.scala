package com.daml.network.scan.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.api.v1.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.util.{CoinUtil, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*
import com.daml.network.config.CoinHttpClientConfig
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.stream.Materializer
import com.daml.network.scan.admin.api.client.ScanConnection.CachedMiningRounds
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.Clock

import java.time.Duration
import com.daml.ledger.api.v1.CommandsOuterClass
import com.digitalasset.canton.tracing.TraceContext

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the SVC party id.
  */
final class ScanConnection(
    config: CoinHttpClientConfig,
    clock: Clock,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends AppConnection(config.clientConfig, timeouts, loggerFactory) {

  override def serviceName: String = "scan"

  // cached SVC reference.
  private val svcRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)
  private val coinRulesCache: AtomicReference[Option[Contract[CoinRules.ContractId, CoinRules]]] =
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
          partyId <- runHttpCmd(config.url, HttpScanAppClient.GetSvcPartyId(List()))
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          svcRef.set(Some(partyId))
          partyId
        }
    }
  }

  def getTransferContext()(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[HttpScanAppClient.TransferContext] = {
    runHttpCmd(config.url, HttpScanAppClient.GetTransferContext)
  }

  def getCoinRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[Contract[CoinRules.ContractId, CoinRules]] = {
    for {
      coinRules <- runHttpCmd(
        config.url,
        HttpScanAppClient.GetCoinRules(coinRulesCache.get()),
      )
    } yield {
      coinRulesCache.set(Some(coinRules))
      coinRules
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
          config.url,
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
    runHttpCmd(config.url, HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  def getAppTransferContext(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[(coinCodegen.AppTransferContext, Seq[CommandsOuterClass.DisclosedContract])] = {
    for {
      context <- getTransferContext()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
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
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[
    Either[String, (coinCodegen.AppTransferContext, Seq[CommandsOuterClass.DisclosedContract])]
  ] = {
    for {
      context <- getTransferContext()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules.getOrElse(throw notFound("No active CoinRules contract"))
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

  private def notFound(description: String) = new StatusRuntimeException(
    Status.NOT_FOUND.withDescription(description)
  )

}

object ScanConnection {
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
