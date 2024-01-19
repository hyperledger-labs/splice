package com.daml.network.scan.admin.api.client

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinrules.{AppTransferContext, CoinRules}
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cn.cns.CnsRules
import com.daml.network.environment.{
  CNLedgerClient,
  HttpAppConnection,
  PackageIdResolver,
  RetryFor,
  RetryProvider,
}
import com.daml.network.scan.admin.api.client.ScanConnection.*
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.TransferContextWithInstances
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.store.AcsStoreDump
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FlagCloseableAsync
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.OptionConverters.*

trait ScanConnection extends PackageIdResolver.HasCoinRules with FlagCloseableAsync {

  protected val clock: Clock
  protected val retryProvider: RetryProvider
  implicit protected val ec: ExecutionContext
  implicit protected val mat: Materializer
  protected def logger: TracedLogger

  def getSvcPartyId()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId]

  /** Query for the SVC party id, retrying until it succeeds.
    *
    * Intended to be used for app init.
    */
  def getSvcPartyIdWithRetries()(implicit ec: ExecutionContext, tc: TraceContext): Future[PartyId] =
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "SVC party ID from scan",
      getSvcPartyId(),
      logger,
    )

  def getCoinRulesWithState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ContractWithState[CoinRules.ContractId, CoinRules]]

  def getCnsRules()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[ContractWithState[CnsRules.ContractId, CnsRules]]

  def getOpenAndIssuingMiningRounds()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[
    (
        Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
        Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
    )
  ]

  def lookupFeaturedAppRight(providerPartyId: PartyId)(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]

  def listImportCrates(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    Seq[ContractWithState[cc.coinimport.ImportCrate.ContractId, cc.coinimport.ImportCrate]]
  ]

  def listSvcSequencers()(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.DomainSequencers]]

  def listSvcScans()(implicit tc: TraceContext): Future[Seq[HttpScanAppClient.DomainScans]]

  def getTransferContextWithInstances()(implicit
      ec: ExecutionContext,
      mat: Materializer,
      tc: TraceContext,
  ): Future[HttpScanAppClient.TransferContextWithInstances] = {
    for {
      openAndIssuingRounds <- getOpenAndIssuingMiningRounds()
      openRounds = openAndIssuingRounds._1
      latestOpenMiningRound = CNNodeUtil.selectLatestOpenMiningRound(clock.now, openRounds)
      coinRules <- getCoinRulesWithState()
    } yield TransferContextWithInstances(coinRules, latestOpenMiningRound, openRounds)
  }

  def getCoinRulesDomain: GetCoinRulesDomain = { () => implicit tc =>
    getCoinRulesWithState()
      .flatMap(
        _.state.fold(
          Future.successful,
          Future failed Status.FAILED_PRECONDITION
            .withDescription("CoinRules is in-flight, no current global domain")
            .asRuntimeException(),
        )
      )
  }

  def getCoinRules()(implicit tc: TraceContext): Future[Contract[CoinRules.ContractId, CoinRules]] =
    getCoinRulesWithState().map(_.contract)

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

  def getAppTransferContext(providerPartyId: PartyId)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      mat: Materializer,
  ): Future[(AppTransferContext, DisclosedContracts.NE)] = {
    for {
      context <- getTransferContextWithInstances()
      featured <- lookupFeaturedAppRight(providerPartyId)
    } yield {
      val coinRules = context.coinRules
      val openMiningRound = context.latestOpenMiningRound
      (
        new AppTransferContext(
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
    Either[String, (AppTransferContext, DisclosedContracts.NE)]
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
              new AppTransferContext(
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

}

object ScanConnection {
  def singleCached(
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
      new CachedScanConnection(coinLedgerClient, config, clock, retryProvider, loggerFactory)
    )

  def singleUncached(
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
  ): Future[SingleScanConnection] =
    HttpAppConnection.checkVersionOrClose(
      new SingleScanConnection(config, clock, retryProvider, loggerFactory)
    )

  private[client] case class CachedCoinRules(
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

  private[client] case class CachedCnsRules(
      cacheValidUntil: CantonTimestamp,
      cnsRules: ContractWithState[CnsRules.ContractId, CnsRules],
  ) {
    def validAsOf(now: CantonTimestamp, coinRules: ContractWithState[?, CoinRules]): Boolean =
      now.isBefore(cacheValidUntil) && coinRules.state.fold(
        assignment =>
          CoinConfigSchedule(coinRules)
            .getConfigAsOf(now)
            .globalDomain
            .activeDomain == assignment.toProtoPrimitive,
        false,
      )
  }

  private[client] case class CachedMiningRounds(
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
) extends HttpAppConnection(config.adminApi, "scan", retryProvider, loggerFactory) {}
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
