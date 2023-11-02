package com.daml.network.scan.admin.http

import com.daml.lf.data.Time.Timestamp
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.round.{
  ClosedMiningRound,
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
import com.daml.network.util.{Codec, Contract, ContractWithState}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import java.time.{OffsetDateTime, ZoneOffset}
import com.daml.network.http.v0.definitions.TransactionHistoryResponseItem.TransactionType.members.{
  DevnetTap,
  Mint,
  SvRewardCollected,
  Transfer,
}
import com.daml.network.scan.store.SortOrder
import com.daml.network.store.PageLimit

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
        issuingRounds <- store.multiDomainAcsStore
          .listContracts(IssuingMiningRound.COMPANION)
        openRounds <- store.multiDomainAcsStore
          .listContracts(OpenMiningRound.COMPANION)
        summarizingRounds <- store.multiDomainAcsStore
          .listContracts(SummarizingMiningRound.COMPANION)
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
  }

  /** We choose the smallest-tickDuration of all non-closed rounds as the TTL.
    * Using this policy, clients will always know about any newly-created rounds before their `opensAt`.
    * See the SVC round automation design document for details, but in short, this is safe because
    * the minimum-duration between the creation and effective 'opening' of a round is always >= 1 tick.
    */
  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  private def tryComputeTimeToLive(
      openRounds: Seq[Contract.Has[?, OpenMiningRound]],
      summarizingRounds: Seq[Contract.Has[?, SummarizingMiningRound]],
      issuingRounds: Seq[Contract.Has[?, IssuingMiningRound]],
  ) = {
    val microseconds: Seq[Long] = (openRounds.map(r =>
      r.payload.tickDuration.microseconds.toLong
    ) ++ summarizingRounds.map(_.payload.tickDuration.microseconds.toLong) ++ issuingRounds.map(r =>
      (Timestamp
        .assertFromInstant(r.payload.targetClosesAt)
        .micros - Timestamp.assertFromInstant(r.payload.opensAt).micros) / 2
    ))
    // using the potentially-throwing `min` on-purpose as we don't want to accidentally set a very large TTL.
    microseconds.min
  }

  private def selectRoundsToRespondWith[TCid, T](
      rounds: Seq[ContractWithState[TCid, T]],
      cachedRounds: Set[String],
  )(implicit tc: TraceContext): Map[String, MaybeCachedContractWithState] = {
    rounds.view.map { round =>
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
          } else Some(round.contract.toHttp),
          round.state.fold(domain => Some(domain.toProtoPrimitive), None),
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
        rounds <- store.multiDomainAcsStore.listContracts(
          ClosedMiningRound.COMPANION
        )
      } yield {
        val filteredRounds = rounds.sortBy(_.payload.round.number)
        definitions.GetClosedRoundsResponse(filteredRounds.toVector.map(r => r.contract.toHttp))
      }
    }
  }

  def listFeaturedAppRights(
      response: v0.ScanResource.ListFeaturedAppRightsResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.ListFeaturedAppRightsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listFeaturedAppRights") { _ => _ =>
      for {
        apps <- store.multiDomainAcsStore.listContracts(
          coinCodegen.FeaturedAppRight.COMPANION
        )
      } yield {
        definitions.ListFeaturedAppRightsResponse(apps.toVector.map(a => a.contract.toHttp))
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
        right <- store.findFeaturedAppRight(
          PartyId.tryFromProtoPrimitive(providerPartyId)
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(_.contract.toHttp))
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

  // TODO: (#7809) Add caching for sequencers per domain
  override def listSvcSequencers(
      respond: v0.ScanResource.ListSvcSequencersResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.ListSvcSequencersResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTopValidatorsByPurchasedTraffic") { _ => _ =>
      for {
        svcRulesO <- store.lookupSvcRules()
        svcRules = svcRulesO getOrElse {
          throw new NoSuchElementException("found no svcRules instance")
        }
        sequencers = for {
          memberInfo <- svcRules.payload.members.asScala.values
          (domainId, domainConfig) <- memberInfo.domainNodes.asScala
          sequencer <- domainConfig.sequencer.toScala
        } yield domainId -> definitions.SvcSequencer(
          sequencer.sequencerId,
          sequencer.url,
          memberInfo.name,
          OffsetDateTime.ofInstant(sequencer.availableAfter, ZoneOffset.UTC),
        )
        sequencersByDomain = sequencers.groupBy(_._1).view.mapValues(_.map(_._2))
        domainSequencers = sequencersByDomain.map { case (domainId, svcSequencers) =>
          definitions.DomainSequencers(domainId, svcSequencers.toVector)
        }.toVector
      } yield definitions.ListSvcSequencersResponse(domainSequencers)
    }
  }

  override def listTransactionHistory(
      respond: v0.ScanResource.ListTransactionHistoryResponse.type
  )(
      request: definitions.TransactionHistoryRequest
  )(extracted: TraceContext): Future[v0.ScanResource.ListTransactionHistoryResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listTransactions") { _ => _ =>
      val pageEndEventId =
        if (request.pageEndEventId.exists(_.isEmpty)) None else request.pageEndEventId
      val sortOrder = request.sortOrder
        .fold[SortOrder](SortOrder.Ascending) {
          case definitions.TransactionHistoryRequest.SortOrder.members.Asc => SortOrder.Ascending
          case definitions.TransactionHistoryRequest.SortOrder.members.Desc => SortOrder.Descending
        }

      for {
        txs <- store.listTransactions(
          pageEndEventId,
          sortOrder,
          PageLimit.tryCreate(request.pageSize.intValue()),
        )
      } yield definitions.TransactionHistoryResponse(
        txs.map(_.toResponseItem).toVector
      )
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
        transactions <- store.listTransactions(
          beginAfterId,
          SortOrder.Descending,
          PageLimit.tryCreate(request.pageSize.intValue()),
        )
      } yield definitions.ListActivityResponse(
        transactions.map { tx =>
          val txItem = tx.toResponseItem
          import definitions.ListActivityResponseItem.*
          definitions.ListActivityResponseItem(
            activityType = txItem.transactionType match {
              case DevnetTap =>
                ActivityType.DevnetTap
              case Mint =>
                ActivityType.Mint
              case Transfer =>
                ActivityType.Transfer
              case SvRewardCollected =>
                ActivityType.SvRewardCollected
            },
            eventId = txItem.eventId,
            offset = txItem.offset,
            domainId = txItem.domainId,
            date = txItem.date,
            svRewardCollected = txItem.svRewardCollected,
            mint = txItem.mint,
            tap = txItem.tap,
            transfer = txItem.transfer,
            round = txItem.round,
            coinPrice = txItem.coinPrice,
          )
        }.toVector
      )
    }
  }
}
