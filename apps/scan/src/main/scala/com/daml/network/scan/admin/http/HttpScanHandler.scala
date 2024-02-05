package com.daml.network.scan.admin.http

import cats.syntax.either.*
import com.daml.lf.data.Time.Timestamp
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinrules.CoinRules
import com.daml.network.codegen.java.cc.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.cns as cnsCodegen
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.{definitions, scan as v0}
import com.daml.network.http.v0.definitions.MaybeCachedContractWithState
import com.daml.network.http.v0.scan.ScanResource
import com.daml.network.scan.store.ScanStore
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.daml.network.util.{Codec, Contract, ContractWithState}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.admin.data.ActiveContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Using
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.time.{OffsetDateTime, ZoneOffset}
import com.daml.network.http.v0.definitions.TransactionHistoryResponseItem.TransactionType.members.{
  DevnetTap,
  Mint,
  SvRewardCollected,
  Transfer,
}
import com.daml.network.scan.store.SortOrder
import com.daml.network.store.PageLimit
import com.digitalasset.canton.config.NonNegativeFiniteDuration

class HttpScanHandler(
    participantAdminConnection: ParticipantAdminConnection,
    store: ScanStore,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration],
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
    val microseconds: Seq[Long] =
      (openRounds.map(r => r.payload.tickDuration.microseconds.toLong) ++ summarizingRounds.map(
        _.payload.tickDuration.microseconds.toLong
      ) ++ issuingRounds.map(r =>
        (Timestamp
          .assertFromInstant(r.payload.targetClosesAt)
          .micros - Timestamp.assertFromInstant(r.payload.opensAt).micros) / 2
      ))
    // using the potentially-throwing `min` on-purpose as we don't want to accidentally set a very large TTL.
    val ttlFromTickDuration = microseconds.min

    miningRoundsCacheTimeToLiveOverride match {
      case Some(value) =>
        val ttlFromConfig = value.duration.toMicros
        if (ttlFromConfig < ttlFromTickDuration) ttlFromConfig
        else
          throw new IllegalArgumentException(
            "`miningRoundsCacheTimeToLiveOverride` cannot be greater than the tick duration."
          )
      case None =>
        ttlFromTickDuration
    }
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
    withSpan(s"$workflowId.getCoinRulesWithState") { _ => _ =>
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
                show"Not sending ${PrettyContractId(CoinRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
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
          FeaturedAppRight.COMPANION
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
        total <- store
          .getTotalCoinBalance(asOfEndOfRound)
          .transform(
            HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
          )
      } yield {
        definitions.GetTotalCoinBalanceResponse(
          Codec.encode(total)
        )
      }
    }
  }

  override def getWalletBalance(
      respond: v0.ScanResource.GetWalletBalanceResponse.type
  )(
      partyId: String,
      asOfEndOfRound: Long,
  )(extracted: TraceContext): Future[v0.ScanResource.GetWalletBalanceResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getWalletBalance") { _ => _ =>
      for {
        total <- store
          .getWalletBalance(PartyId tryFromProtoPrimitive partyId, asOfEndOfRound)
          .transform(
            HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
          )
      } yield definitions.GetWalletBalanceResponse(Codec.encode(total))
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
        .transform(
          HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
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
    withSpan(s"$workflowId.listSvcSequencers") { _ => _ =>
      listFromSvcRules { svcRules =>
        for {
          memberInfo <- svcRules.payload.members.asScala.values
          (domainId, domainConfig) <- memberInfo.domainNodes.asScala
          sequencer <- domainConfig.sequencer.toScala
          availableAfter <- sequencer.availableAfter.toScala
        } yield domainId -> definitions.SvcSequencer(
          sequencer.sequencerId,
          sequencer.url,
          memberInfo.name,
          OffsetDateTime.ofInstant(availableAfter, ZoneOffset.UTC),
        )
      }.map(list =>
        definitions.ListSvcSequencersResponse(list.map { case (domainId, scans) =>
          definitions.DomainSequencers(domainId, scans.toVector)
        })
      )
    }
  }

  override def listSvcScans(
      respond: ScanResource.ListSvcScansResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListSvcScansResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.listSvcScans") { _ => _ =>
      listFromSvcRules { svcRules =>
        for {
          memberInfo <- svcRules.payload.members.asScala.values
          (domainId, domainConfig) <- memberInfo.domainNodes.asScala
          scan <- domainConfig.scan.toScala
        } yield domainId -> definitions.ScanInfo(scan.publicUrl, memberInfo.name)
      }.map(list =>
        definitions.ListSvcScansResponse(list.map { case (domainId, scans) =>
          definitions.DomainScans(domainId, scans.toVector)
        })
      )
    }
  }

  /** Returns all items extracted by `f` from the SvcRules ensuring that they're sorted by domainId,
    * so that the order is deterministic.
    */
  private def listFromSvcRules[T](
      f: ContractWithState[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules] => Iterable[
        (String, T)
      ]
  )(implicit tc: TraceContext): Future[Vector[(String, Iterable[T])]] = {
    for {
      svcRulesO <- store.lookupSvcRules()
    } yield {
      val svcRules = svcRulesO getOrElse {
        throw new NoSuchElementException("found no svcRules instance")
      }
      val items = f(svcRules)
      val itemsByDomain = items.groupBy(_._1).view.mapValues(_.map(_._2))
      itemsByDomain.toVector.sortBy(_._1)
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

  override def listCnsEntries(
      respond: ScanResource.ListCnsEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int)(
      extracted: TraceContext
  ): Future[ScanResource.ListCnsEntriesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listEntries") { _ => _ =>
      for {
        entries <- store.listEntries(namePrefix.getOrElse(""), PageLimit.tryCreate(pageSize))
      } yield definitions.ListEntriesResponse(entries.map(_.contract.toHttp).toVector)
    }
  }

  override def lookupCnsEntryByName(respond: ScanResource.LookupCnsEntryByNameResponse.type)(
      name: String
  )(extracted: TraceContext): Future[ScanResource.LookupCnsEntryByNameResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByName") { _ => _ =>
      store.lookupEntryByName(name).flatMap {
        case Some(entry) =>
          Future.successful(
            v0.ScanResource.LookupCnsEntryByNameResponse.OK(
              definitions.LookupEntryByNameResponse(entry.contract.toHttp)
            )
          )
        case None =>
          Future.successful(
            v0.ScanResource.LookupCnsEntryByNameResponse.NotFound(
              definitions.ErrorResponse(s"No cns entry found for name: $name")
            )
          )
      }
    }
  }

  override def lookupCnsEntryByParty(respond: ScanResource.LookupCnsEntryByPartyResponse.type)(
      party: String
  )(extracted: TraceContext): Future[ScanResource.LookupCnsEntryByPartyResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByParty") { _ => _ =>
      store
        .lookupEntryByParty(PartyId.tryFromProtoPrimitive(party))
        .flatMap {
          case Some(entry) =>
            Future.successful(
              v0.ScanResource.LookupCnsEntryByPartyResponse.OK(
                definitions.LookupEntryByPartyResponse(entry.contract.toHttp)
              )
            )
          case None =>
            Future.successful(
              v0.ScanResource.LookupCnsEntryByPartyResponse.NotFound(
                definitions.ErrorResponse(s"No cns entry found for party: $party")
              )
            )
        }
    }
  }

  /** Filter the given ACS snapshot to contracts the given party is a stakeholder on */
  // TODO(#9340) Move this logic inside a Canton gRPC API.
  private def filterAcsSnapshot(input: ByteString, stakeholder: PartyId): ByteString = {
    val contracts = ActiveContract
      .loadFromByteString(input)
      .valueOr(error =>
        throw Status.INTERNAL
          .withDescription(s"Failed to read ACS snapshot: ${error}")
          .asRuntimeException()
      )
    val output = ByteString.newOutput
    Using.resource(new GZIPOutputStream(output)) { outputStream =>
      contracts.filter(c => c.contract.metadata.stakeholders.contains(stakeholder.toLf)).foreach {
        c =>
          c.writeDelimitedTo(outputStream) match {
            case Left(error) =>
              throw Status.INTERNAL
                .withDescription(s"Failed to write ACS snapshot: ${error}")
                .asRuntimeException()
            case Right(_) => outputStream.flush()
          }
      }
    }
    output.toByteString
  }

  override def getAcsSnapshot(respond: ScanResource.GetAcsSnapshotResponse.type)(party: String)(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanResource.GetAcsSnapshotResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getAcsSnapshot") { _ => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(party)
      for {
        // The SVC party is a stakeholder on all "important" contracts, in particular, all coin holdings and CNS entries
        // so filtering an SVC snapshot to contracts another party is also a stakeholder on provides a sufficient snapshot
        // for that party to recover.
        // It does however lose third-party application data that the SVC party is not a stakeholder on. Supporting that requires
        // that users backup their own ACS.
        // As the SVC party is hosted on all SVs, an arbitrary scan instance can be chosen for the ACS snapshot.
        // BFT reads are usually not required since ACS commitments act as a check that the ACS was correct.
        acsSnapshot <- participantAdminConnection.downloadAcsSnapshot(Set(store.svcParty))
      } yield {
        val filteredAcsSnapshot =
          filterAcsSnapshot(acsSnapshot, partyId)
        v0.ScanResource.GetAcsSnapshotResponse.OK(
          definitions.GetAcsSnapshotResponse(
            Base64.getEncoder.encodeToString(filteredAcsSnapshot.toByteArray)
          )
        )
      }
    }
  }
  override def getAggregatedRounds(respond: ScanResource.GetAggregatedRoundsResponse.type)()(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanResource.GetAggregatedRoundsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getAggregatedRounds") { _ => _ =>
      for {
        range <- store.getAggregatedRounds()
      } yield {
        range.fold(
          v0.ScanResource.GetAggregatedRoundsResponse.NotFound(
            definitions.ErrorResponse("No aggregated rounds found")
          )
        )(range =>
          v0.ScanResource.GetAggregatedRoundsResponse.OK(
            definitions.GetAggregatedRoundsResponse(start = range.start, end = range.end)
          )
        )
      }
    }
  }

  private def ensureValidRange[T](start: Long, end: Long, maxRounds: Int)(
      f: => Future[T]
  )(implicit tc: com.digitalasset.canton.tracing.TraceContext): Future[T] = {
    require(maxRounds > 0, "maxRounds must be positive")
    if (start < 0 || end < 0) {
      Future.failed(
        HttpErrorHandler.badRequest(
          s"rounds must be non-negative: start_round $start, end_round $end"
        )
      )
    } else if (end < start) {
      Future.failed(
        HttpErrorHandler.badRequest(s"end_round $end must be >= start_round $start")
      )
    } else if (end - start + 1 > maxRounds) {
      Future.failed(
        HttpErrorHandler.badRequest(s"Cannot request more than $maxRounds rounds at a time")
      )
    } else {
      for {
        range <- store.getAggregatedRounds()
        res <- range.fold(
          Future.failed(
            HttpErrorHandler.notFound("No aggregated rounds found")
          ): Future[T]
        )(range =>
          if (start < range.start || end > range.end) {
            Future.failed(
              HttpErrorHandler.badRequest(
                s"Requested rounds range ${start}-${end} is outside of the available rounds range ${range.start}-${range.end}"
              )
            ): Future[T]
          } else {
            f
          }
        )
      } yield res
    }
  }

  override def listRoundTotals(
      respond: ScanResource.ListRoundTotalsResponse.type
  )(request: definitions.ListRoundTotalsRequest)(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanResource.ListRoundTotalsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listRoundTotals") { _ => _ =>
      ensureValidRange(request.startRound, request.endRound, 200) {
        for {
          roundTotals <- store.getRoundTotals(request.startRound, request.endRound)
          entries = roundTotals.map { roundTotal =>
            definitions.RoundTotals(
              closedRound = roundTotal.closedRound,
              closedRoundEffectiveAt = java.time.OffsetDateTime
                .ofInstant(roundTotal.closedRoundEffectiveAt.toInstant, ZoneOffset.UTC),
              appRewards = Codec.encode(roundTotal.appRewards),
              validatorRewards = Codec.encode(roundTotal.validatorRewards),
              changeToInitialAmountAsOfRoundZero =
                Codec.encode(roundTotal.changeToInitialAmountAsOfRoundZero),
              changeToHoldingFeesRate = Codec.encode(roundTotal.changeToHoldingFeesRate),
              cumulativeAppRewards = Codec.encode(roundTotal.cumulativeAppRewards),
              cumulativeValidatorRewards = Codec.encode(roundTotal.cumulativeValidatorRewards),
              cumulativeChangeToInitialAmountAsOfRoundZero =
                Codec.encode(roundTotal.cumulativeChangeToInitialAmountAsOfRoundZero),
              cumulativeChangeToHoldingFeesRate =
                Codec.encode(roundTotal.cumulativeChangeToHoldingFeesRate),
              totalCoinBalance = Codec.encode(roundTotal.totalCoinBalance),
            )
          }
        } yield v0.ScanResource.ListRoundTotalsResponse.OK(
          definitions.ListRoundTotalsResponse(entries.toVector)
        )
      }
    }
  }
  override def listRoundPartyTotals(
      respond: ScanResource.ListRoundPartyTotalsResponse.type
  )(request: definitions.ListRoundPartyTotalsRequest)(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanResource.ListRoundPartyTotalsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listRoundPartyTotals") { _ => _ =>
      ensureValidRange(request.startRound, request.endRound, 50) {
        for {
          roundPartyTotals <- store.getRoundPartyTotals(request.startRound, request.endRound)
          entries = roundPartyTotals.map { roundPartyTotal =>
            definitions.RoundPartyTotals(
              closedRound = roundPartyTotal.closedRound,
              party = roundPartyTotal.party,
              appRewards = Codec.encode(roundPartyTotal.appRewards),
              validatorRewards = Codec.encode(roundPartyTotal.validatorRewards),
              trafficPurchased = roundPartyTotal.trafficPurchased,
              trafficPurchasedCcSpent = Codec.encode(roundPartyTotal.trafficPurchasedCcSpent),
              trafficNumPurchases = roundPartyTotal.trafficNumPurchases,
              cumulativeAppRewards = Codec.encode(roundPartyTotal.cumulativeAppRewards),
              cumulativeValidatorRewards = Codec.encode(roundPartyTotal.cumulativeValidatorRewards),
              cumulativeChangeToInitialAmountAsOfRoundZero =
                Codec.encode(roundPartyTotal.cumulativeChangeToInitialAmountAsOfRoundZero),
              cumulativeChangeToHoldingFeesRate =
                Codec.encode(roundPartyTotal.cumulativeChangeToHoldingFeesRate),
              cumulativeTrafficPurchased = roundPartyTotal.cumulativeTrafficPurchased,
              cumulativeTrafficPurchasedCcSpent =
                Codec.encode(roundPartyTotal.cumulativeTrafficPurchasedCcSpent),
              cumulativeTrafficNumPurchases = roundPartyTotal.cumulativeTrafficNumPurchases,
            )
          }
        } yield v0.ScanResource.ListRoundPartyTotalsResponse.OK(
          definitions.ListRoundPartyTotalsResponse(entries.toVector)
        )
      }
    }
  }
}
