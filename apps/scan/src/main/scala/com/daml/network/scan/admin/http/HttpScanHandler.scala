package com.daml.network.scan.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cc.round.{
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.http.v0.{definitions, scan as v0}
import com.daml.network.http.v0.definitions.MaybeCachedContractWithState
import com.daml.network.http.v0.scan.ScanResource
import com.daml.network.scan.store.ScanStore
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.util.{Codec, Contract, ContractMetadataUtil}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import java.time.ZoneOffset

class HttpScanHandler(
    store: ScanStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ScanHandler[TraceContext]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def getSvcPartyId(
      response: v0.ScanResource.GetSvcPartyIdResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetSvcPartyIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getSvcPartyId") { _ => _ =>
      Future.successful(definitions.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }
  }

  def getOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetOpenAndIssuingMiningRoundsRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getOpenAndIssuingMiningRounds") { _ => _ =>
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
          } else Some(round.toHttp),
          constDomain,
        ),
      )
    }.toMap
  }

  def getCoinRules(
      response: v0.ScanResource.GetCoinRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetCoinRulesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getCoinRules") { _ => _ =>
      for {
        coinRulesO <- store.lookupCoinRules()
        coinRules = coinRulesO getOrElse {
          throw new NoSuchElementException("found no coinrules instance")
        }
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedCoinRulesContractId match {
            case Some(cachedContractId) if cachedContractId == coinRules.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(coinCodegen.CoinRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) // else: coin rules are cached but outdated.
                | None =>
              Some(coinRules.contract.toHttp)
          },
          domainId = coinRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }
  }

  def getCnsRules(
      response: v0.ScanResource.GetCnsRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCnsRulesRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetCnsRulesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getCnsRules") { _ => _ =>
      for {
        cnsRulesO <- store.lookupCnsRules()
        cnsRules = cnsRulesO getOrElse {
          throw new NoSuchElementException("found no cnsrules instance")
        }
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedCnsRulesContractId match {
            case Some(cachedContractId) if cachedContractId == cnsRules.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(cnsCodegen.CnsRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) | None =>
              Some(cnsRules.contract.toHttp)
          },
          domainId = cnsRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetCnsRulesResponse(
          cnsRulesUpdate = response
        )
      }
    }
  }
  def getClosedRounds(
      response: v0.ScanResource.GetClosedRoundsResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetClosedRoundsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getClosedRounds") { _ => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        rounds <- store.multiDomainAcsStore.listContractsOnDomain(
          roundCodegen.ClosedMiningRound.COMPANION,
          domainId,
        )
      } yield {
        val filteredRounds = rounds.sortBy(_.payload.round.number)
        definitions.GetClosedRoundsResponse(filteredRounds.toVector.map(r => r.toHttp))
      }
    }
  }

  def listFeaturedAppRights(
      response: v0.ScanResource.ListFeaturedAppRightsResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.ListFeaturedAppRightsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listFeaturedAppRights") { _ => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        apps <- store.multiDomainAcsStore.listContractsOnDomain(
          coinCodegen.FeaturedAppRight.COMPANION,
          domainId,
        )
      } yield {
        definitions.ListFeaturedAppRightsResponse(apps.toVector.map(a => a.toHttp))
      }
    }
  }

  def lookupFeaturedAppRight(
      response: com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse.type
  )(providerPartyId: String)(extracted: TraceContext): Future[
    com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupFeaturedAppRight") { _ => _ =>
      for {
        domainId <- store.defaultAcsDomainIdF
        right <- store.findFeaturedAppRight(
          domainId,
          PartyId.tryFromProtoPrimitive(providerPartyId),
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(r => r.toHttp))
      }
    }
  }

  def getTotalCoinBalance(
      response: v0.ScanResource.GetTotalCoinBalanceResponse.type
  )(
      asOfEndOfRound: Long
  )(extracted: TraceContext): Future[v0.ScanResource.GetTotalCoinBalanceResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTotalCoinBalance") { _ => _ =>
      for {
        total <- store.getTotalCoinBalance(asOfEndOfRound)
      } yield {
        definitions.GetTotalCoinBalanceResponse(
          Codec.encode(total)
        )
      }
    }
  }

  def getCoinConfigForRound(
      response: v0.ScanResource.GetCoinConfigForRoundResponse.type
  )(round: Long)(extracted: TraceContext): Future[v0.ScanResource.GetCoinConfigForRoundResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getCoinConfigForRound") { _ => _ =>
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
  }
  def getRoundOfLatestData(
      response: v0.ScanResource.GetRoundOfLatestDataResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetRoundOfLatestDataResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRoundOfLatestData") { _ => _ =>
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
  }

  def getRewardsCollected(
      response: v0.ScanResource.GetRewardsCollectedResponse.type
  )(
      round: Option[Long]
  )(extracted: TraceContext): Future[v0.ScanResource.GetRewardsCollectedResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRewardsCollected") { _ => _ =>
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
  }

  def getTopProvidersByAppRewards(
      response: v0.ScanResource.GetTopProvidersByAppRewardsResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  )(extracted: TraceContext): Future[v0.ScanResource.GetTopProvidersByAppRewardsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTopProvidersByAppRewards") { _ => _ =>
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
  }
  def getTopValidatorsByValidatorRewards(
      response: v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  )(extracted: TraceContext): Future[v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTopValidatorsByValidatorRewards") { _ => _ =>
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
  }
  override def getTopValidatorsByPurchasedTraffic(
      response: ScanResource.GetTopValidatorsByPurchasedTrafficResponse.type
  )(
      asOfEndOfRound: Long,
      limit: Int,
  )(extracted: TraceContext): Future[ScanResource.GetTopValidatorsByPurchasedTrafficResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTopValidatorsByPurchasedTraffic") { _ => _ =>
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

  override def listImportCrates(respond: v0.ScanResource.ListImportCratesResponse.type)(
      receiverPartyId: String
  )(extracted: TraceContext): Future[v0.ScanResource.ListImportCratesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listImportCrates") { _ => _ =>
      {
        for {
          crates <- store.listImportCrates(PartyId.tryFromProtoPrimitive(receiverPartyId))
        } yield definitions.ListImportCratesResponse(
          crates
            .map(crate =>
              MaybeCachedContractWithState(
                Some(crate.contract.toHttp),
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

  override def listActivity(
      respond: v0.ScanResource.ListActivityResponse.type
  )(
      request: definitions.ListActivityRequest
  )(extracted: TraceContext): Future[v0.ScanResource.ListActivityResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listActivity") { _ => _ =>
      val beginAfterId = if (request.beginAfterId.exists(_.isEmpty)) None else request.beginAfterId
      for {
        activities <- store.listActivity(beginAfterId, request.pageSize.toInt)
      } yield definitions.ListActivityResponse(
        activities.map(_.toResponseItem).toVector
      )
    }
  }
}
