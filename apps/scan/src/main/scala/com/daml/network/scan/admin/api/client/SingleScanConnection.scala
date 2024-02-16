package com.daml.network.scan.admin.api.client

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns.{CnsEntry, CnsRules}
import com.daml.network.environment.{CNLedgerClient, HttpAppConnection, RetryProvider}
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.scan.store.db.ScanAggregator
import com.daml.network.util.{Codec, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import com.google.protobuf.ByteString
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import com.digitalasset.canton.data.CantonTimestamp

/** Connection to the admin API of CC Scan. This is used by other apps
  * to query for the SVC party id.
  */
class SingleScanConnection private[client] (
    private[client] val config: ScanAppClientConfig,
    protected val clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
)(implicit
    protected val ec: ExecutionContextExecutor,
    tc: TraceContext,
    protected val mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends HttpAppConnection(
      config.adminApi,
      "scan",
      retryProvider,
      outerLoggerFactory.append("scan-connection", config.adminApi.url.toString),
    )
    with ScanConnection {

  // cached SVC reference. Never changes.
  private val svcRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  /** Query for the SVC party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  override def getSvcPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] = {
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

  override def getCoinRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[CoinRules.ContractId, CoinRules]] = {
    getCoinRulesWithState(None)
  }

  def getCoinRulesWithState(
      cachedCoinRules: Option[ContractWithState[CoinRules.ContractId, CoinRules]]
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[CoinRules.ContractId, CoinRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetCoinRules(cachedCoinRules),
    )
  }

  override def getCnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[CnsRules.ContractId, CnsRules]] = {
    getCnsRules(None)
  }

  def getCnsRules(cachedCnsRules: Option[ContractWithState[CnsRules.ContractId, CnsRules]])(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[CnsRules.ContractId, CnsRules]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetCnsRules(cachedCnsRules),
    )
  }

  def lookupCnsEntryByParty(
      id: PartyId
  )(implicit tc: TraceContext): Future[Option[Contract[CnsEntry.ContractId, CnsEntry]]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupCnsEntryByParty(id),
    )
  }

  def lookupCnsEntryByName(
      name: String
  )(implicit tc: TraceContext): Future[Option[Contract[CnsEntry.ContractId, CnsEntry]]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.LookupCnsEntryByName(name),
    )
  }

  def listCnsEntries(namePrefix: Option[String], pageSize: Int)(implicit tc: TraceContext) = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListCnsEntries(namePrefix, pageSize),
    )
  }

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
    getOpenAndIssuingMiningRounds(Seq.empty, Seq.empty).map { case (open, issuing, _) =>
      (open, issuing)
    }
  }

  def getOpenAndIssuingMiningRounds(
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
  ] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
        cachedOpenRounds,
        cachedIssuingRounds,
      ),
    )
  }

  override def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]] = {
    runHttpCmd(config.adminApi.url, HttpScanAppClient.LookupFeaturedAppRight(providerPartyId))
  }

  override def listImportCrates(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]
  ] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListImportCrates(party),
    )

  override def listSvcSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListSvcSequencers(),
    )
  }

  override def listSvcScans()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainScans]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListSvcScans(),
    ).map(_.map { scans =>
      if (scans.malformed.nonEmpty) {
        logger.warn(
          s"Malformed scans found for domain ${scans.domainId}: ${scans.malformed.keys}. This likely indicates malicious SVs."
        )
      }
      scans
    })
  }

  def getAcsSnapshot(partyId: PartyId)(implicit tc: TraceContext): Future[ByteString] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAcsSnapshot(partyId),
    )
  }
  def listRoundTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[com.daml.network.http.v0.definitions.RoundTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundTotals(start, end),
    )
  }
  def listRoundPartyTotals(
      start: Long,
      end: Long,
  )(implicit
      tc: TraceContext
  ): Future[Seq[com.daml.network.http.v0.definitions.RoundPartyTotals]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.ListRoundPartyTotals(start, end),
    )
  }
  def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] = {
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetAggregatedRounds,
    )
  }

  def getRoundAggregate(round: Long)(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundAggregate]] = {
    for {
      roundTotals <- listRoundTotals(round, round).flatMap { roundTotals =>
        roundTotals.headOption
          .map { rt =>
            decodeRoundTotal(rt).fold(
              err =>
                Future.failed(ScanAggregator.CannotAdvance(s"Failed to decode round totals: $err")),
              rt => Future.successful(Some(rt)),
            )
          }
          .getOrElse(Future.successful(None))
      }
      roundPartyTotals <- listRoundPartyTotals(round, round).flatMap { roundPartyTotals =>
        val (errors, totals) = roundPartyTotals.partitionMap { rt =>
          decodeRoundPartyTotals(rt)
        }
        if (errors.nonEmpty) {
          Future.failed(
            ScanAggregator.CannotAdvance(
              s"""Failed to decode round party totals: ${errors.mkString(", ")}"""
            )
          )
        } else {
          Future.successful(totals.toVector)
        }
      }
    } yield {
      roundTotals.map { rt =>
        ScanAggregator.RoundAggregate(roundTotals = rt, roundPartyTotals = roundPartyTotals)
      }
    }
  }

  private def decodeRoundTotal(
      rt: com.daml.network.http.v0.definitions.RoundTotals
  ): Either[String, ScanAggregator.RoundTotals] = {
    (for {
      closedRoundEffectiveAt <- CantonTimestamp.fromInstant(rt.closedRoundEffectiveAt.toInstant)
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      changeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.changeToInitialAmountAsOfRoundZero)
      changeToHoldingFeesRate <- Codec.decode(Codec.BigDecimal)(rt.changeToHoldingFeesRate)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
      cumulativeChangeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToInitialAmountAsOfRoundZero)
      cumulativeChangeToHoldingFeesRate <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToHoldingFeesRate)
      totalCoinBalance <- Codec.decode(Codec.BigDecimal)(rt.totalCoinBalance)
    } yield {
      ScanAggregator.RoundTotals(
        closedRound = rt.closedRound,
        closedRoundEffectiveAt = closedRoundEffectiveAt,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        changeToInitialAmountAsOfRoundZero = changeToInitialAmountAsOfRoundZero,
        changeToHoldingFeesRate = changeToHoldingFeesRate,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
        cumulativeChangeToInitialAmountAsOfRoundZero = cumulativeChangeToInitialAmountAsOfRoundZero,
        cumulativeChangeToHoldingFeesRate = cumulativeChangeToHoldingFeesRate,
        totalCoinBalance = totalCoinBalance,
      )
    })
  }

  private def decodeRoundPartyTotals(
      rt: com.daml.network.http.v0.definitions.RoundPartyTotals
  ): Either[String, ScanAggregator.RoundPartyTotals] = {
    (for {
      appRewards <- Codec.decode(Codec.BigDecimal)(rt.appRewards)
      validatorRewards <- Codec.decode(Codec.BigDecimal)(rt.validatorRewards)
      trafficPurchasedCcSpent <- Codec.decode(Codec.BigDecimal)(rt.trafficPurchasedCcSpent)
      cumulativeAppRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeAppRewards)
      cumulativeValidatorRewards <- Codec.decode(Codec.BigDecimal)(rt.cumulativeValidatorRewards)
      cumulativeChangeToInitialAmountAsOfRoundZero <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToInitialAmountAsOfRoundZero)
      cumulativeChangeToHoldingFeesRate <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeChangeToHoldingFeesRate)
      cumulativeTrafficPurchasedCcSpent <- Codec
        .decode(Codec.BigDecimal)(rt.cumulativeTrafficPurchasedCcSpent)
    } yield {
      ScanAggregator.RoundPartyTotals(
        closedRound = rt.closedRound,
        party = rt.party,
        appRewards = appRewards,
        validatorRewards = validatorRewards,
        trafficPurchased = rt.trafficPurchased,
        trafficPurchasedCcSpent = trafficPurchasedCcSpent,
        trafficNumPurchases = rt.trafficNumPurchases,
        cumulativeAppRewards = cumulativeAppRewards,
        cumulativeValidatorRewards = cumulativeValidatorRewards,
        cumulativeChangeToInitialAmountAsOfRoundZero = cumulativeChangeToInitialAmountAsOfRoundZero,
        cumulativeChangeToHoldingFeesRate = cumulativeChangeToHoldingFeesRate,
        cumulativeTrafficPurchased = rt.cumulativeTrafficPurchased,
        cumulativeTrafficPurchasedCcSpent = cumulativeTrafficPurchasedCcSpent,
        cumulativeTrafficNumPurchases = rt.cumulativeTrafficNumPurchases,
      )
    })
  }
}

class CachedScanConnection private[client] (
    protected val coinLedgerClient: CNLedgerClient,
    config: ScanAppClientConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    outerLoggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    tc: TraceContext,
    mat: Materializer,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
) extends SingleScanConnection(config, clock, retryProvider, outerLoggerFactory)
    with CachingScanConnection {

  override protected val coinRulesCacheTimeToLive: NonNegativeFiniteDuration =
    config.coinRulesCacheTimeToLive

  override protected def runGetCoinRulesWithState(
      cachedCoinRules: Option[ContractWithState[CoinRules.ContractId, CoinRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CoinRules.ContractId, CoinRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetCoinRules(cachedCoinRules),
    )

  override protected def runGetCnsRules(
      cachedCnsRules: Option[ContractWithState[CnsRules.ContractId, CnsRules]]
  )(implicit tc: TraceContext): Future[ContractWithState[CnsRules.ContractId, CnsRules]] =
    runHttpCmd(
      config.adminApi.url,
      HttpScanAppClient.GetCnsRules(cachedCnsRules),
    )

  override protected def runGetOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  )(implicit ec: ExecutionContext, mat: Materializer, tc: TraceContext): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
        BigInt,
    )
  ] = runHttpCmd(
    config.adminApi.url,
    HttpScanAppClient.GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds,
      cachedIssuingRounds,
    ),
  )
}
