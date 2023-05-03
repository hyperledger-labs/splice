package com.daml.network.scan.admin.http

import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  round as roundCodegen,
  v1test as ccV1Test,
}
import com.daml.network.codegen.java.cc.round.{
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.http.v0.definitions.MaybeCachedContract
import com.daml.network.http.v0.{definitions, scan as v0}
import com.daml.network.scan.store.ScanStore
import com.daml.network.util.PrettyInstances.*
import com.daml.network.util.{
  Codec,
  Contract,
  ContractMetadataUtil,
  DomainFeesConstants,
  RateLimiterWithExtraTraffic,
}
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.scan.config.ScanAppBackendConfig
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.grpc.StatusRuntimeException

import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class HttpScanHandler(
    store: ScanStore,
    config: ScanAppBackendConfig,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ScanHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  // TODO(#3816): Temporarily added state to mock the sequencer QoS as part of the DomainFees PoC until the Canton functionality is implemented
  private val validatorTrafficRateLimiters =
    new ConcurrentHashMap[PartyId, RateLimiterWithExtraTraffic]()

  def getSvcPartyId(
      response: v0.ScanResource.GetSvcPartyIdResponse.type
  )(): Future[v0.ScanResource.GetSvcPartyIdResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(definitions.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }

  def getOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetOpenAndIssuingMiningRoundsRequest
  ): Future[v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        issuingRounds <- store.multiDomainAcsStore.listContractsOnDomain(
          IssuingMiningRound.COMPANION,
          domainId,
        )
        openRounds <- store.multiDomainAcsStore.listContractsOnDomain(
          OpenMiningRound.COMPANION,
          domainId,
        )
        summarizingRounds <- store.multiDomainAcsStore.listContractsOnDomain(
          SummarizingMiningRound.COMPANION,
          domainId,
        )
        issuingRoundsCachedByClient = body.cachedIssuingRoundContractIds.toSet
        openRoundsCachedByClient = body.cachedOpenMiningRoundContractIds.toSet
        issuingRoundsResponseMap = selectRoundsToRespondWith(
          issuingRounds,
          issuingRoundsCachedByClient,
        )
        openRoundsResponseMap = selectRoundsToRespondWith(
          openRounds,
          openRoundsCachedByClient,
        )
        ttl = tryComputeTimeToLive(openRounds, summarizingRounds, issuingRounds)
      } yield {
        definitions.GetOpenAndIssuingMiningRoundsResponse(
          timeToLiveInMicroseconds = BigInt(ttl),
          openMiningRounds = openRoundsResponseMap,
          issuingMiningRounds = issuingRoundsResponseMap,
        )
      }
    }

  /** We choose the smallest-tickDuration of all non-closed rounds as the TTL.
    * Using this policy, clients will always know about any newly-created rounds before their `opensAt`.
    * See the SVC round automation design document for details, but in short, this is safe because
    * the minimum-duration between the creation and effective 'opening' of a round is always >= 1 tick.
    */
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def tryComputeTimeToLive(
      openRounds: Seq[Contract[OpenMiningRound.ContractId, OpenMiningRound]],
      summarizingRounds: Seq[Contract[SummarizingMiningRound.ContractId, SummarizingMiningRound]],
      issuingRounds: Seq[Contract[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) = {
    val microseconds: Seq[Long] = (openRounds.map(r =>
      r.payload.tickDuration.microseconds.toLong
    ) ++ summarizingRounds.map(_.payload.tickDuration.microseconds.toLong) ++ issuingRounds.map(r =>
      (ContractMetadataUtil.instantToMicros(r.payload.targetClosesAt) - ContractMetadataUtil
        .instantToMicros(r.payload.opensAt)) / 2
    ))
    // using the potentially-throwing `min` on-purpose as we don't want to accidentally set a very large TTL.
    microseconds.min
  }

  private def selectRoundsToRespondWith[TCid, T](
      rounds: Seq[Contract[TCid, T]],
      cachedRounds: Set[String],
  )(implicit tc: TraceContext): Map[String, MaybeCachedContract] = {
    rounds
      .map(round => {
        val roundIsAlreadyCached =
          cachedRounds.contains(round.contractId.contractId)
        (
          round.contractId.contractId,
          if (roundIsAlreadyCached) {
            logger.debug(
              show"Not sending ${PrettyContractId(round)}, as it is cached by the client."
            )
            MaybeCachedContract(None)
          } else MaybeCachedContract(Some(round.toJson)),
        )
      })
      .toMap
  }

  def getCoinRules(
      response: v0.ScanResource.GetCoinRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  ): Future[v0.ScanResource.GetCoinRulesResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        coinRulesO <- store.lookupCoinRules()
        coinRules = coinRulesO.getOrElse(
          throw new NoSuchElementException("found no coinrules instance")
        )
      } yield {
        val response = body.cachedCoinRulesContractId match {
          case Some(cachedContractId) if cachedContractId == coinRules.contractId.contractId =>
            logger.debug(
              show"Not sending ${PrettyContractId(coinCodegen.CoinRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
            )
            MaybeCachedContract(None)
          case Some(_) => // else: coin rules are cached but outdated.
            MaybeCachedContract(Some(coinRules.toJson))
          case None =>
            MaybeCachedContract(Some(coinRules.toJson))
        }
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }

  def getCoinRulesV1Test(
      response: v0.ScanResource.GetCoinRulesV1TestResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  ): Future[v0.ScanResource.GetCoinRulesV1TestResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      if (!config.enableCoinRulesUpgrade) {
        throw new StatusRuntimeException(
          Status.UNIMPLEMENTED.withDescription("CoinRules upgrades are disabled")
        )
      }
      for {
        coinRulesO <- store.lookupCoinRulesV1Test()
        coinRules = coinRulesO.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("found no upgraded coinrules instance")
          )
        )
      } yield {
        val response = body.cachedCoinRulesContractId match {
          case Some(cachedContractId) if cachedContractId == coinRules.contractId.contractId =>
            logger.debug(
              show"Not sending ${PrettyContractId(ccV1Test.coin.CoinRulesV1Test.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
            )
            MaybeCachedContract(None)
          case Some(_) => // else: coin rules are cached but outdated.
            MaybeCachedContract(Some(coinRules.toJson))
          case None =>
            MaybeCachedContract(Some(coinRules.toJson))
        }
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }

  def getClosedRounds(
      response: v0.ScanResource.GetClosedRoundsResponse.type
  )(): Future[v0.ScanResource.GetClosedRoundsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        rounds <- store.multiDomainAcsStore.listContractsOnDomain(
          roundCodegen.ClosedMiningRound.COMPANION,
          domainId,
        )
      } yield {
        val filteredRounds = rounds.sortBy(_.payload.round.number)
        definitions.GetClosedRoundsResponse(filteredRounds.toVector.map(r => r.toJson))
      }
    }

  def listFeaturedAppRights(
      response: v0.ScanResource.ListFeaturedAppRightsResponse.type
  )(): Future[v0.ScanResource.ListFeaturedAppRightsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        apps <- store.multiDomainAcsStore.listContractsOnDomain(
          coinCodegen.FeaturedAppRight.COMPANION,
          domainId,
        )
      } yield {
        definitions.ListFeaturedAppRightsResponse(apps.toVector.map(a => a.toJson))
      }
    }

  def lookupFeaturedAppRight(
      response: com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse.type
  )(providerPartyId: String): Future[
    com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse
  ] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        right <- store.multiDomainAcsStore.findContractOnDomain(
          coinCodegen.FeaturedAppRight.COMPANION
        )(
          domainId,
          (co: Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]) =>
            co.payload.provider == providerPartyId,
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(r => r.toJson))
      }
    }

  def getTotalCoinBalance(
      response: v0.ScanResource.GetTotalCoinBalanceResponse.type
  )(): Future[v0.ScanResource.GetTotalCoinBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        (totalCoins, totalLockedCoins) <- store.getTotalCoinBalance()
      } yield {
        definitions.GetTotalCoinBalanceResponse(
          Codec.encode(totalCoins),
          Codec.encode(totalLockedCoins),
        )
      }
    }

  def getCoinConfigForRound(
      response: v0.ScanResource.GetCoinConfigForRoundResponse.type
  )(round: Long): Future[v0.ScanResource.GetCoinConfigForRoundResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      store
        .getCoinConfigForRound(round)
        .map(cfg =>
          v0.ScanResource.GetCoinConfigForRoundResponse.OK(
            definitions.GetCoinConfigForRoundResponse(
              Codec.encode(cfg.coinCreateFee),
              Codec.encode(cfg.holdingFee),
              Codec.encode(cfg.lockHolderFee),
              definitions.SteppedRate(
                Codec.encode(cfg.initialTransferFee),
                cfg.transferFeeSteps
                  .map((step) => definitions.RateStep(Codec.encode(step._1), Codec.encode(step._2)))
                  .toVector,
              ),
            )
          )
        )
        .transform(HttpErrorHandler.onGrpcNotFound(s"Round ${round} not found"))
    }

  def getRoundOfLatestData(
      response: v0.ScanResource.GetRoundOfLatestDataResponse.type
  )(): Future[v0.ScanResource.GetRoundOfLatestDataResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      store
        .getRoundOfLatestData()
        .map { case (round, effectiveAt) =>
          v0.ScanResource.GetRoundOfLatestDataResponse.OK(
            definitions
              .GetRoundOfLatestDataResponse(round, effectiveAt.atOffset(ZoneOffset.UTC))
          )
        }
        .transform(HttpErrorHandler.onGrpcNotFound("No data has been made available yet"))
    }

  def getTopProvidersByAppRewards(
      response: v0.ScanResource.GetTopProvidersByAppRewardsResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  ): Future[v0.ScanResource.GetTopProvidersByAppRewardsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      store
        .getTopProvidersByAppRewards(asOfEndOfRound, limit)
        .map(res =>
          v0.ScanResource.GetTopProvidersByAppRewardsResponse.OK(
            definitions
              .GetTopProvidersByAppRewardsResponse(
                res
                  .map(p => definitions.PartyAndRewards(Codec.encode(p._1), Codec.encode(p._2)))
                  .toVector
              )
          )
        )
        .transform(
          HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
        )
    }

  def getTopValidatorsByValidatorRewards(
      response: v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  ): Future[v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      store
        .getTopValidatorsByValidatorRewards(asOfEndOfRound, limit)
        .map(res =>
          v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse.OK(
            definitions
              .GetTopValidatorsByValidatorRewardsResponse(
                res
                  .map(p => definitions.PartyAndRewards(Codec.encode(p._1), Codec.encode(p._2)))
                  .toVector
              )
          )
        )
        .transform(
          HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
        )
    }

  private def getOrCreateTrafficLimiter(validatorParty: PartyId): RateLimiterWithExtraTraffic = {
    validatorTrafficRateLimiters
      .putIfAbsent(
        validatorParty,
        new RateLimiterWithExtraTraffic(
          DomainFeesConstants.defaultThroughput,
          DomainFeesConstants.defaultTrafficBurstWindow,
          clock,
        ),
      )
    validatorTrafficRateLimiters.get(validatorParty)
  }

  def getValidatorTrafficBalance(
      response: v0.ScanResource.GetValidatorTrafficBalanceResponse.type
  )(validatorParty: String): Future[v0.ScanResource.GetValidatorTrafficBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val validatorPartyId = PartyId.tryFromProtoPrimitive(validatorParty)
      store
        .getTotalPaidValidatorTraffic(validatorPartyId)
        .map(totalTraffic => {
          val totalPaidTraffic = NonNegativeNumeric.tryCreate(totalTraffic.toDouble)
          val validatorTrafficRateLimiter = getOrCreateTrafficLimiter(validatorPartyId)
          val extraTrafficBalance =
            validatorTrafficRateLimiter.getExtraTrafficBalance(totalPaidTraffic)
          v0.ScanResource.GetValidatorTrafficBalanceResponse.OK(
            definitions
              .GetValidatorTrafficBalanceResponse(extraTrafficBalance, totalPaidTraffic.value)
          )
        })

    }

  def checkAndUpdateValidatorTrafficBalance(
      response: v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse.type
  )(
      validatorParty: String
  ): Future[v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val validatorPartyId = PartyId.tryFromProtoPrimitive(validatorParty)
      store
        .getTotalPaidValidatorTraffic(validatorPartyId)
        .map(totalTraffic => {
          val totalPaidTraffic = NonNegativeNumeric.tryCreate(totalTraffic.toDouble)
          val validatorTrafficRateLimiter = getOrCreateTrafficLimiter(validatorPartyId)
          logger.debug(
            s"Default traffic balance remaining for validator ${validatorPartyId}: ${validatorTrafficRateLimiter.getDefaultTrafficBalance()}"
          )
          logger.debug(
            s"Extra traffic balance remaining for validator ${validatorPartyId}: ${validatorTrafficRateLimiter
                .getExtraTrafficBalance(totalPaidTraffic)}"
          )
          val approved = validatorTrafficRateLimiter.checkAndUpdate(totalPaidTraffic)
          v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse.OK(
            definitions.CheckAndUpdateValidatorTrafficBalanceResponse(approved)
          )
        })
    }
}
