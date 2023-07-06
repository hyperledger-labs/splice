package com.daml.network.scan.admin.http

import com.daml.network.admin.http.HttpErrorHandler
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
import com.daml.network.http.v0.{definitions, scan as v0}
import com.daml.network.http.v0.definitions.MaybeCachedContractWithState
import com.daml.network.http.v0.scan.ScanResource
import com.daml.network.scan.config.ScanAppBackendConfig
import com.daml.network.scan.store.ScanStore
import com.daml.network.store.AcsStoreDump
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.util.{
  Codec,
  Contract,
  ContractMetadataUtil,
  DomainFeesConstants,
  RateLimiterWithExtraTraffic,
}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.config.RequireTypes.{NonNegativeNumeric, PositiveNumeric}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
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
) extends v0.ScanHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  // TODO(#3816): Temporarily added state to mock the sequencer QoS as part of the DomainFees PoC until the Canton functionality is implemented
  private val validatorTrafficRateLimiters =
    new ConcurrentHashMap[PartyId, RateLimiterWithExtraTraffic]()

  def getSvcPartyId(
      response: v0.ScanResource.GetSvcPartyIdResponse.type
  )()(extracted: Unit): Future[v0.ScanResource.GetSvcPartyIdResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(definitions.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }

  def getOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetOpenAndIssuingMiningRoundsRequest
  )(extracted: Unit): Future[v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse] =
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
          domainId,
        )
        openRoundsResponseMap = selectRoundsToRespondWith(
          openRounds,
          openRoundsCachedByClient,
          domainId,
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
      onlyDomainId: DomainId,
  )(implicit tc: TraceContext): Map[String, MaybeCachedContractWithState] = {
    val constDomain = Some(onlyDomainId.toProtoPrimitive)
    rounds.map { round =>
      val roundIsAlreadyCached =
        cachedRounds.contains(round.contractId.contractId)
      (
        round.contractId.contractId,
        MaybeCachedContractWithState(
          if (roundIsAlreadyCached) {
            logger.debug(
              show"Not sending ${PrettyContractId(round)}, as it is cached by the client."
            )
            None
          } else Some(round.toJson),
          constDomain,
        ),
      )
    }.toMap
  }

  def getCoinRules(
      response: v0.ScanResource.GetCoinRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  )(extracted: Unit): Future[v0.ScanResource.GetCoinRulesResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        coinRulesO <- store.lookupCoinRules()
        coinRules = coinRulesO getOrElse {
          throw new NoSuchElementException("found no coinrules instance")
        }
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedCoinRulesContractId match {
            case Some(cachedContractId)
                if cachedContractId == coinRules.contract.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(coinCodegen.CoinRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) // else: coin rules are cached but outdated.
                | None =>
              Some(coinRules.contract.toJson)
          },
          domainId = coinRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }

  def getCoinRulesV1Test(
      response: v0.ScanResource.GetCoinRulesV1TestResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  )(extracted: Unit): Future[v0.ScanResource.GetCoinRulesV1TestResponse] =
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
        val response = MaybeCachedContractWithState(
          body.cachedCoinRulesContractId match {
            case Some(cachedContractId)
                if cachedContractId == coinRules.contract.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(ccV1Test.coin.CoinRulesV1Test.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) // else: coin rules are cached but outdated.
                | None =>
              Some(coinRules.contract.toJson)
          },
          domainId = coinRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }

  def getClosedRounds(
      response: v0.ScanResource.GetClosedRoundsResponse.type
  )()(extracted: Unit): Future[v0.ScanResource.GetClosedRoundsResponse] =
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
  )()(extracted: Unit): Future[v0.ScanResource.ListFeaturedAppRightsResponse] =
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
  )(providerPartyId: String)(extracted: Unit): Future[
    com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse
  ] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        right <- store.findFeaturedAppRight(
          domainId,
          PartyId.tryFromProtoPrimitive(providerPartyId),
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(r => r.toJson))
      }
    }

  def getTotalCoinBalance(
      response: v0.ScanResource.GetTotalCoinBalanceResponse.type
  )(asOfEndOfRound: Long)(extracted: Unit): Future[v0.ScanResource.GetTotalCoinBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      for {
        total <- store.getTotalCoinBalance(asOfEndOfRound)
      } yield {
        definitions.GetTotalCoinBalanceResponse(
          Codec.encode(total)
        )
      }
    }

  def getCoinConfigForRound(
      response: v0.ScanResource.GetCoinConfigForRoundResponse.type
  )(round: Long)(extracted: Unit): Future[v0.ScanResource.GetCoinConfigForRoundResponse] =
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
  )()(extracted: Unit): Future[v0.ScanResource.GetRoundOfLatestDataResponse] =
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

  def getRewardsCollected(
      response: v0.ScanResource.GetRewardsCollectedResponse.type
  )(round: Option[Long])(extracted: Unit): Future[v0.ScanResource.GetRewardsCollectedResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      round
        .fold(store.getTotalRewardsCollectedEver())(store.getRewardsCollectedInRound(_))
        .map { case amount =>
          v0.ScanResource.GetRewardsCollectedResponse.OK(
            definitions
              .GetRewardsCollectedResponse(Codec.encode(amount))
          )
        }
        .transform(HttpErrorHandler.onGrpcNotFound("No data has been made available yet"))
    }

  def getTopProvidersByAppRewards(
      response: v0.ScanResource.GetTopProvidersByAppRewardsResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  )(extracted: Unit): Future[v0.ScanResource.GetTopProvidersByAppRewardsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      // TODO(#4965): Provide an upper bound for limit
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
  )(extracted: Unit): Future[v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      // TODO(#4965): Provide an upper bound for limit
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

  override def getTopValidatorsByPurchasedTraffic(
      response: ScanResource.GetTopValidatorsByPurchasedTrafficResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  )(extracted: Unit): Future[ScanResource.GetTopValidatorsByPurchasedTrafficResponse] = {
    withNewTrace(workflowId) { implicit traceContext => _ =>
      // TODO(#4965): Provide an upper bound for limit
      store
        .getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit)
        .map(validatorTraffic =>
          v0.ScanResource.GetTopValidatorsByPurchasedTrafficResponse.OK(
            definitions.GetTopValidatorsByPurchasedTrafficResponse(
              validatorTraffic
                .map(t =>
                  definitions.ValidatorPurchasedTraffic(
                    Codec.encode(t.validator),
                    t.numPurchases,
                    t.totalTrafficPurchased,
                    Codec.encode(t.totalCcSpent),
                    t.lastPurchasedInRound,
                  )
                )
                .toVector
            )
          )
        )
    }
  }

  private def getOrCreateTrafficLimiter(
      validatorParty: PartyId
  )(implicit tc: TraceContext): Future[RateLimiterWithExtraTraffic] = {
    store
      .getBaseRateTrafficLimitsAsOf(clock.now)
      .map(baseRateLimits => {
        validatorTrafficRateLimiters
          .putIfAbsent(
            validatorParty,
            new RateLimiterWithExtraTraffic(
              NonNegativeNumeric.tryCreate(baseRateLimits.rate.doubleValue()),
              PositiveNumeric
                .tryCreate(baseRateLimits.burstWindow.microseconds.doubleValue() / 1e6),
              DomainFeesConstants.assumedCoinTxSizeBytes,
              clock,
            ),
          )
        validatorTrafficRateLimiters.get(validatorParty)
      })
  }

  def getValidatorTrafficBalance(
      response: v0.ScanResource.GetValidatorTrafficBalanceResponse.type
  )(
      validatorParty: String
  )(extracted: Unit): Future[v0.ScanResource.GetValidatorTrafficBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val validatorPartyId = PartyId.tryFromProtoPrimitive(validatorParty)
      for {
        totalTraffic <- store.getTotalPaidValidatorTraffic(validatorPartyId)
        totalTraffic_ = NonNegativeNumeric.tryCreate(totalTraffic.toDouble)
        validatorTrafficRateLimiter <- getOrCreateTrafficLimiter(validatorPartyId)
      } yield {
        val extraTrafficBalance =
          validatorTrafficRateLimiter.getExtraTrafficBalance(totalTraffic_)
        v0.ScanResource.GetValidatorTrafficBalanceResponse.OK(
          definitions
            .GetValidatorTrafficBalanceResponse(extraTrafficBalance, totalTraffic.toDouble)
        )
      }
    }

  def checkAndUpdateValidatorTrafficBalance(
      response: v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse.type
  )(
      validatorParty: String
  )(extracted: Unit): Future[v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      val validatorPartyId = PartyId.tryFromProtoPrimitive(validatorParty)
      for {
        totalTraffic <- store.getTotalPaidValidatorTraffic(validatorPartyId)
        totalTraffic_ = NonNegativeNumeric.tryCreate(totalTraffic.toDouble)
        validatorTrafficRateLimiter <- getOrCreateTrafficLimiter(validatorPartyId)
      } yield {
        logger.debug(
          s"Default traffic balance remaining for validator ${validatorPartyId}: ${validatorTrafficRateLimiter
              .getDefaultTrafficBalance()}"
        )
        logger.debug(
          s"Extra traffic balance remaining for validator ${validatorPartyId}: ${validatorTrafficRateLimiter
              .getExtraTrafficBalance(totalTraffic_)}"
        )
        val approved = validatorTrafficRateLimiter.checkAndUpdate(totalTraffic_)
        v0.ScanResource.CheckAndUpdateValidatorTrafficBalanceResponse.OK(
          definitions.CheckAndUpdateValidatorTrafficBalanceResponse(approved)
        )
      }
    }

  override def listImportCrates(respond: v0.ScanResource.ListImportCratesResponse.type)(
      party: String
  )(extracted: Unit): Future[v0.ScanResource.ListImportCratesResponse] =
    withNewTrace(workflowId) { implicit traceContext => _ =>
      {
        // TODO(#6278): do not drop the suffix here
        val receiverName = AcsStoreDump.dropPartyNameSuffix(party)
        for {
          crates <- store.listImportCrates(receiverName)
        } yield definitions.ListImportCratesResponse(
          crates
            .map(crate =>
              MaybeCachedContractWithState(
                Some(crate.contract.toJson),
                crate.state match {
                  case ContractState.InFlight => None
                  case ContractState.Assigned(domainId) => Some(domainId.toProtoPrimitive)
                },
              )
            )
            .toVector
        )
      }
    }
}
