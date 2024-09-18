// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.http

import com.digitalasset.canton.data.CantonTimestamp
import cats.data.OptionT
import cats.syntax.either.*
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.codegen.java.splice.amulet
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules
import com.daml.network.codegen.java.splice.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import com.daml.network.codegen.java.splice.ans as ansCodegen
import com.daml.network.config.Thresholds
import com.daml.network.config.SpliceInstanceNamesConfig
import com.daml.network.environment.ParticipantAdminConnection
import com.daml.network.http.v0.{definitions, scan as v0}
import com.daml.network.http.v0.definitions.{
  AcsRequest,
  BatchListVotesByVoteRequestsRequest,
  HoldingsStateRequest,
  HoldingsSummaryRequest,
  ListVoteResultsRequest,
  MaybeCachedContractWithState,
}
import com.daml.network.http.v0.scan.ScanResource
import com.daml.network.scan.store.{
  AcsSnapshotStore,
  ScanHistoryBackfilling,
  ScanStore,
  SortOrder,
  TxLogEntry,
}
import com.daml.network.util.{
  Codec,
  Contract,
  ContractWithState,
  PackageQualifiedName,
  QualifiedName,
}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.data.ActiveContract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Try, Using}
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import com.daml.network.http.v0.definitions.TransactionHistoryResponseItem.TransactionType.members.{
  DevnetTap,
  Mint,
  Transfer,
}
import com.daml.network.http.{HttpVotesHandler, UrlValidator}
import com.daml.network.scan.dso.DsoAnsResolver
import com.daml.network.store.PageLimit
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.Clock

class HttpScanHandler(
    svParty: PartyId,
    svUserName: String,
    spliceInstanceNames: SpliceInstanceNamesConfig,
    participantAdminConnection: ParticipantAdminConnection,
    protected val store: ScanStore,
    snapshotStore: AcsSnapshotStore,
    dsoAnsResolver: DsoAnsResolver,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration],
    enableForcedAcsSnapshots: Boolean,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
) extends v0.ScanHandler[TraceContext]
    with HttpVotesHandler {
  protected val workflowId = this.getClass.getSimpleName
  protected val votesStore = store

  def getDsoPartyId(
      response: v0.ScanResource.GetDsoPartyIdResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetDsoPartyIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getDsoPartyId") { _ => _ =>
      Future.successful(definitions.GetDsoPartyIdResponse(store.key.dsoParty.toProtoPrimitive))
    }
  }

  def getDsoInfo(
      respond: v0.ScanResource.GetDsoInfoResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetDsoInfoResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getDsoInfo") { _ => _ =>
      for {
        latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
        amuletRules <- store.getAmuletRulesWithState()
        rulesAndStates <- store.getDsoRulesWithStateWithSvNodeStates()
        dsoRules = rulesAndStates.dsoRules
      } yield definitions.GetDsoInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        dsoPartyId = store.key.dsoParty.toProtoPrimitive,
        votingThreshold = Thresholds.requiredNumVotes(dsoRules),
        latestMiningRound = latestOpenMiningRound.toContractWithState.toHttp,
        amuletRules = amuletRules.toHttp,
        dsoRules = dsoRules.toHttp,
        svNodeStates = rulesAndStates.svNodeStates.values.map(_.toHttp).toVector,
      )
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
    * See the DSO round automation design document for details, but in short, this is safe because
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

  def getAmuletRules(
      response: v0.ScanResource.GetAmuletRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetAmuletRulesRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetAmuletRulesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getAmuletRulesWithState") { _ => _ =>
      for {
        amuletRulesO <- store.lookupAmuletRules()
        amuletRules = amuletRulesO getOrElse {
          throw new NoSuchElementException("found no amuletrules instance")
        }
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedAmuletRulesContractId match {
            case Some(cachedContractId) if cachedContractId == amuletRules.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(AmuletRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) // else: amulet rules are cached but outdated.
                | None =>
              Some(amuletRules.contract.toHttp)
          },
          domainId = amuletRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetAmuletRulesResponse(
          amuletRulesUpdate = response
        )
      }
    }
  }

  def getAnsRules(
      response: v0.ScanResource.GetAnsRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetAnsRulesRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetAnsRulesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getAnsRules") { _ => _ =>
      for {
        ansRulesO <- store.lookupAnsRules()
        ansRules = ansRulesO getOrElse {
          throw new NoSuchElementException("found no ansrules instance")
        }
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedAnsRulesContractId match {
            case Some(cachedContractId) if cachedContractId == ansRules.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(ansCodegen.AnsRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) | None =>
              Some(ansRules.contract.toHttp)
          },
          domainId = ansRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetAnsRulesResponse(
          ansRulesUpdate = response
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
          amulet.FeaturedAppRight.COMPANION
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

  def getTotalAmuletBalance(
      response: v0.ScanResource.GetTotalAmuletBalanceResponse.type
  )(
      asOfEndOfRound: Long
  )(extracted: TraceContext): Future[v0.ScanResource.GetTotalAmuletBalanceResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTotalAmuletBalance") { _ => _ =>
      for {
        total <- store
          .getTotalAmuletBalance(asOfEndOfRound)
          .transform(
            HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
          )
      } yield {
        definitions.GetTotalAmuletBalanceResponse(
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

  def getAmuletConfigForRound(
      response: v0.ScanResource.GetAmuletConfigForRoundResponse.type
  )(
      round: Long
  )(extracted: TraceContext): Future[v0.ScanResource.GetAmuletConfigForRoundResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getAmuletConfigForRound") { _ => _ =>
      store
        .getAmuletConfigForRound(round)
        .map(cfg => {
          val transferFee = cfg.transferFee.getOrElse(throw new RuntimeException("No transfer fee"))
          v0.ScanResource.GetAmuletConfigForRoundResponse.OK(
            definitions.GetAmuletConfigForRoundResponse(
              Codec.encode(cfg.amuletCreateFee),
              Codec.encode(cfg.holdingFee),
              Codec.encode(cfg.lockHolderFee),
              definitions.SteppedRate(
                Codec.encode(transferFee.initialRate),
                transferFee.steps
                  .map((step) =>
                    definitions.RateStep(Codec.encode(step.from), Codec.encode(step.rate))
                  )
                  .toVector,
              ),
            )
          )
        })
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

  override def getTopValidatorsByValidatorFaucets(
      respond: v0.ScanResource.GetTopValidatorsByValidatorFaucetsResponse.type
  )(limit: Int)(
      extracted: TraceContext
  ): Future[v0.ScanResource.GetTopValidatorsByValidatorFaucetsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getTopValidatorsByValidatorRewards") { _ => _ =>
      store
        .getTopValidatorLicenses(PageLimit.tryCreate(limit))
        .map(licenses =>
          v0.ScanResource.GetTopValidatorsByValidatorFaucetsResponse.OK(
            definitions
              .GetTopValidatorsByValidatorFaucetsResponse(
                licenses.map { license =>
                  val numRoundsCollected = license.payload.faucetState
                    .map { faucetState =>
                      faucetState.lastReceivedFor.number - faucetState.firstReceivedFor.number - faucetState.numCouponsMissed + 1
                    }
                    .orElse(0L)
                  definitions.ValidatorReceivedFaucets(
                    validator = license.payload.validator,
                    numRoundsCollected = numRoundsCollected,
                    numRoundsMissed =
                      license.payload.faucetState.map(_.numCouponsMissed.longValue()).orElse(0L),
                    firstCollectedInRound = license.payload.faucetState
                      .map(_.firstReceivedFor.number.longValue())
                      .orElse(0L),
                    lastCollectedInRound = license.payload.faucetState
                      .map(_.lastReceivedFor.number.longValue())
                      .orElse(0L),
                  )
                }.toVector
              )
          )
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

  // TODO: (#7809) Add caching for sequencers per domain
  override def listDsoSequencers(
      respond: v0.ScanResource.ListDsoSequencersResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.ListDsoSequencersResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listDsoSequencers") { _ => _ =>
      store
        .listFromSvNodeStates { nodeState =>
          for {
            (domainId, domainConfig) <- nodeState.state.synchronizerNodes.asScala.toVector
            sequencers = for {
              sequencer <- domainConfig.sequencer.toScala
              availableAfter <- sequencer.availableAfter.toScala
            } yield domainId -> definitions.DsoSequencer(
              sequencer.migrationId,
              sequencer.sequencerId,
              sequencer.url,
              nodeState.svName,
              OffsetDateTime.ofInstant(availableAfter, ZoneOffset.UTC),
            )
            legacySequencers = for {
              legacyConfig <- domainConfig.legacySequencerConfig.toScala.toList
            } yield domainId -> definitions.DsoSequencer(
              legacyConfig.migrationId,
              legacyConfig.sequencerId,
              legacyConfig.url,
              nodeState.svName,
              OffsetDateTime.MIN,
            )
            sequencerConfig <- (legacySequencers ++ sequencers).distinct
          } yield sequencerConfig
        }
        .map(list =>
          list.map { case (domainId, sequencers) =>
            domainId -> sequencers.filter { sequencer =>
              UrlValidator.isValid(sequencer.url) match {
                case Left(failure) =>
                  logger.warn(
                    s"Not serving sequencer $sequencer for domain $domainId as it has an invalid url: $failure"
                  )
                  false
                case Right(_) => true
              }
            }
          }
        )
        .map(list =>
          definitions.ListDsoSequencersResponse(list.map { case (domainId, sequencers) =>
            definitions.DomainSequencers(domainId, sequencers.toVector)
          })
        )
    }
  }

  override def listDsoScans(
      respond: ScanResource.ListDsoScansResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListDsoScansResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.listDsoScans") { _ => _ =>
      store
        .listDsoScans()
        .map(list =>
          definitions.ListDsoScansResponse(list.map { case (domainId, scans) =>
            definitions.DomainScans(
              domainId,
              scans.map(s => definitions.ScanInfo(s.publicUrl, s.svName)).toVector,
            )
          })
        )
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
        txs.map(TxLogEntry.Http.toResponseItem).toVector
      )
    }
  }

  override def getUpdateHistory(respond: v0.ScanResource.GetUpdateHistoryResponse.type)(
      request: definitions.UpdateHistoryRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetUpdateHistoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getUpdateHistory") { _ => _ =>
      val updateHistory = store.updateHistory
      val afterO = request.after.map { after =>
        val afterRecordTime = {
          for {
            instant <- Try(Instant.parse(after.afterRecordTime)).toEither.left.map(_.getMessage)
            ts <- Timestamp.fromInstant(instant)
          } yield CantonTimestamp(ts)
        }
        afterRecordTime.fold(
          error => throw new IllegalArgumentException(s"Invalid timestamp: $error"),
          afterRecordTime =>
            (
              after.afterMigrationId,
              afterRecordTime,
            ),
        )
      }
      updateHistory
        .getUpdates(
          afterO,
          includeImportUpdates = false,
          PageLimit.tryCreate(request.pageSize),
        )
        .flatMap { txs =>
          // TODO(#14076: replace this with a better check for whether this scan instance has replicated all data)
          if (
            afterO.isEmpty && txs.headOption.exists(u =>
              !ScanHistoryBackfilling
                .isFoundingTransactionTreeUpdate(u.update, store.key.dsoParty.toProtoPrimitive)
            )
          ) {
            logger.debug(s"Expected founding transaction, found ${txs.headOption}")
            Future.failed(
              Status.FAILED_PRECONDITION
                .withDescription(
                  s"This scan instance has not yet replicated all data. Wait before retrying, or connect to a different scan instance."
                )
                .asRuntimeException()
            )
          } else {
            Future.successful(txs)
          }
        }
        .map { txs =>
          {
            val lossless = request.lossless.getOrElse(false)
            val encodings: ScanHttpEncodings =
              if (lossless) LosslessScanHttpEncodings else LossyScanHttpEncodings
            definitions.UpdateHistoryResponse(
              txs
                .map(encodings.lapiToHttpUpdate(_))
                .toVector
            )
          }
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
        transactions <- store.listTransactions(
          beginAfterId,
          SortOrder.Descending,
          PageLimit.tryCreate(request.pageSize.intValue()),
        )
      } yield definitions.ListActivityResponse(
        transactions.map { tx =>
          val txItem = TxLogEntry.Http.toResponseItem(tx)
          import definitions.ListActivityResponseItem.*
          definitions.ListActivityResponseItem(
            activityType = txItem.transactionType match {
              case DevnetTap =>
                ActivityType.DevnetTap
              case Mint =>
                ActivityType.Mint
              case Transfer =>
                ActivityType.Transfer
            },
            eventId = txItem.eventId,
            offset = txItem.offset,
            domainId = txItem.domainId,
            date = txItem.date,
            mint = txItem.mint,
            tap = txItem.tap,
            transfer = txItem.transfer,
            round = txItem.round,
            amuletPrice = txItem.amuletPrice,
          )
        }.toVector
      )
    }
  }

  override def listAnsEntries(
      respond: ScanResource.ListAnsEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int)(
      extracted: TraceContext
  ): Future[ScanResource.ListAnsEntriesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listEntries") { _ => _ =>
      for {
        entryContracts <- store.listEntries(
          namePrefix.getOrElse(""),
          clock.now,
          PageLimit.tryCreate(pageSize),
        )
        entries = entryContracts.map { contract =>
          definitions.AnsEntry(
            Some(contract.contractId.contractId),
            contract.payload.user,
            contract.payload.name,
            contract.payload.url,
            contract.payload.description,
            Some(java.time.OffsetDateTime.ofInstant(contract.payload.expiresAt, ZoneOffset.UTC)),
          )
        }
        sizeToAppendDsoEntries = pageSize - entries.size
        appended <-
          if (sizeToAppendDsoEntries > 0) {
            getDsoEntriesFromDsoRules(namePrefix).map { dsoEntries =>
              entries ++ dsoEntries.take(sizeToAppendDsoEntries)
            }
          } else Future.successful(entries)
      } yield definitions.ListEntriesResponse(appended.toVector)
    }
  }

  private def getDsoEntriesFromDsoRules(namePrefix: Option[String])(implicit tc: TraceContext) =
    store.lookupDsoRules().map { dsoRulesOpt =>
      dsoRulesOpt.toList.flatMap { dsoRules =>
        dsoAnsResolver
          .listEntries(dsoRules.contract, namePrefix)
          .map(_.toHttp)
      }
    }

  override def lookupAnsEntryByName(respond: ScanResource.LookupAnsEntryByNameResponse.type)(
      name: String
  )(extracted: TraceContext): Future[ScanResource.LookupAnsEntryByNameResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByName") { _ => _ =>
      store.lookupDsoRules().flatMap {
        case Some(dsoRules) =>
          dsoAnsResolver.lookupEntryByName(dsoRules.contract, name) match {
            case Some(dsoAnsEntry) =>
              Future.successful(
                v0.ScanResource.LookupAnsEntryByNameResponse
                  .OK(definitions.LookupEntryByNameResponse(dsoAnsEntry.toHttp))
              )
            case None =>
              store.lookupEntryByName(name, clock.now).map {
                case Some(entry) =>
                  v0.ScanResource.LookupAnsEntryByNameResponse.OK(
                    definitions.LookupEntryByNameResponse(
                      definitions.AnsEntry(
                        Some(entry.contractId.contractId),
                        entry.payload.user,
                        entry.payload.name,
                        entry.payload.url,
                        entry.payload.description,
                        Some(
                          java.time.OffsetDateTime
                            .ofInstant(entry.payload.expiresAt, ZoneOffset.UTC)
                        ),
                      )
                    )
                  )
                case None =>
                  v0.ScanResource.LookupAnsEntryByNameResponse.NotFound(
                    definitions.ErrorResponse(s"No ans entry found for name: $name")
                  )
              }
          }
        case None =>
          Future.successful(
            v0.ScanResource.LookupAnsEntryByNameResponse.NotFound(
              definitions.ErrorResponse(s"No DsoRules contract found")
            )
          )
      }
    }
  }

  override def lookupAnsEntryByParty(respond: ScanResource.LookupAnsEntryByPartyResponse.type)(
      party: String
  )(extracted: TraceContext): Future[ScanResource.LookupAnsEntryByPartyResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByParty") { _ => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(party)
      store.lookupDsoRules().flatMap {
        case Some(dsoRules) =>
          dsoAnsResolver.lookupEntryByParty(dsoRules.contract, partyId) match {
            case Some(dsoAnsEntry) =>
              Future.successful(
                v0.ScanResource.LookupAnsEntryByPartyResponse
                  .OK(definitions.LookupEntryByPartyResponse(dsoAnsEntry.toHttp))
              )
            case None =>
              store
                .lookupEntryByParty(partyId, clock.now)
                .flatMap {
                  case Some(entry) =>
                    Future.successful(
                      v0.ScanResource.LookupAnsEntryByPartyResponse.OK(
                        definitions.LookupEntryByPartyResponse(
                          definitions.AnsEntry(
                            Some(entry.contractId.contractId),
                            entry.payload.user,
                            entry.payload.name,
                            entry.payload.url,
                            entry.payload.description,
                            Some(
                              java.time.OffsetDateTime
                                .ofInstant(entry.payload.expiresAt, ZoneOffset.UTC)
                            ),
                          )
                        )
                      )
                    )
                  case None =>
                    Future.successful(
                      v0.ScanResource.LookupAnsEntryByPartyResponse.NotFound(
                        definitions.ErrorResponse(s"No ans entry found for party: $party")
                      )
                    )
                }
          }
        case None =>
          Future.successful(
            v0.ScanResource.LookupAnsEntryByPartyResponse.NotFound(
              definitions.ErrorResponse(s"No DsoRules contract found")
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
        // The DSO party is a stakeholder on all "important" contracts, in particular, all amulet holdings and ANS entries.
        // This means the SV participants ingest data for that party and we can take a snapshot for that party.
        // To make sure the snapshot is the same regardless of which SV is queried, we filter it down to
        // contracts that the DSO party is also a stakeholder on.
        // It does however lose third-party application data that the DSO party is not a stakeholder on. Supporting that requires
        // that users backup their own ACS.
        // As the DSO party is hosted on all SVs, an arbitrary scan instance can be chosen for the ACS snapshot.
        // BFT reads are usually not required since ACS commitments act as a check that the ACS was correct.
        acsSnapshot <- participantAdminConnection.downloadAcsSnapshot(Set(partyId))
      } yield {
        val filteredAcsSnapshot =
          filterAcsSnapshot(acsSnapshot, store.key.dsoParty)
        v0.ScanResource.GetAcsSnapshotResponse.OK(
          definitions.GetAcsSnapshotResponse(
            Base64.getEncoder.encodeToString(filteredAcsSnapshot.toByteArray)
          )
        )
      }
    }
  }

  override def getDateOfMostRecentSnapshotBefore(
      respond: ScanResource.GetDateOfMostRecentSnapshotBeforeResponse.type
  )(before: OffsetDateTime, migrationId: Long)(
      extracted: TraceContext
  ): Future[ScanResource.GetDateOfMostRecentSnapshotBeforeResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getDateOfMostRecentSnapshotBefore") { _ => _ =>
      snapshotStore
        .lookupSnapshotBefore(migrationId, CantonTimestamp.assertFromInstant(before.toInstant))
        .map {
          case Some(snapshot) =>
            ScanResource.GetDateOfMostRecentSnapshotBeforeResponseOK(
              definitions
                .AcsSnapshotTimestampResponse(
                  snapshot.snapshotRecordTime.toInstant.atOffset(ZoneOffset.UTC)
                )
            )
          case None =>
            ScanResource.GetDateOfMostRecentSnapshotBeforeResponseNotFound(
              definitions.ErrorResponse(s"No snapshots found before $before")
            )
        }
    }
  }

  override def forceAcsSnapshotNow(
      respond: ScanResource.ForceAcsSnapshotNowResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ForceAcsSnapshotNowResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.forceAcsSnapshotNow") { _ => _ =>
      if (!enableForcedAcsSnapshots) {
        Future.successful(
          ScanResource.ForceAcsSnapshotNowResponse.BadRequest(
            definitions.ErrorResponse("Forced ACS snapshots are disabled.")
          )
        )
      } else {
        for {
          domainId <- store
            .lookupAmuletRules()
            .map(
              _.getOrElse(
                throw io.grpc.Status.FAILED_PRECONDITION
                  .withDescription("No amulet rules.")
                  .asRuntimeException()
              ).state.fold(
                identity,
                throw io.grpc.Status.FAILED_PRECONDITION
                  .withDescription("Amulet rules are in flight.")
                  .asRuntimeException(),
              )
            )
          snapshotTime <- snapshotStore.updateHistory
            .getUpdatesBefore(
              snapshotStore.migrationId,
              domainId,
              CantonTimestamp.MaxValue,
              PageLimit.tryCreate(1),
            )
            .map(
              _.headOption
                .getOrElse(
                  throw io.grpc.Status.FAILED_PRECONDITION
                    .withDescription("No updates ever happened for a snapshot.")
                    .asRuntimeException()
                )
                .update
                .update
                .recordTime
            )
          lastSnapshot <- snapshotStore.lookupSnapshotBefore(
            snapshotStore.migrationId,
            snapshotTime,
          )
          // note that this will make it so that the next snapshot is taken N hours after THIS snapshot.
          // this is, in principle, not a problem:
          // - this will only be used in tests
          // - wall clock tests must take manual snapshots anyway, because they can't wait
          // - simtime tests will advanceTime(N.hours)
          _ = logger.info(s"Forcing ACS snapshot at $snapshotTime. Last snapshot: $lastSnapshot")
          _ <- snapshotStore.insertNewSnapshot(lastSnapshot, snapshotTime)
        } yield ScanResource.ForceAcsSnapshotNowResponse.OK(
          definitions.ForceAcsSnapshotResponse(
            snapshotTime.toInstant.atOffset(ZoneOffset.UTC),
            snapshotStore.migrationId,
          )
        )
      }
    }
  }

  override def getAcsSnapshotAt(respond: ScanResource.GetAcsSnapshotAtResponse.type)(
      body: AcsRequest
  )(extracted: TraceContext): Future[ScanResource.GetAcsSnapshotAtResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAcsSnapshotAt") { _ => _ =>
      body match {
        case AcsRequest(migrationId, recordTime, after, pageSize, partyIds, templates) =>
          snapshotStore
            .queryAcsSnapshot(
              migrationId,
              CantonTimestamp.assertFromInstant(recordTime.toInstant),
              after,
              PageLimit.tryCreate(pageSize),
              partyIds
                .getOrElse(Seq.empty)
                .map(PartyId.tryFromProtoPrimitive),
              templates
                .getOrElse(Seq.empty)
                .map(_.split(":") match {
                  case Array(packageName, moduleName, entityName) =>
                    PackageQualifiedName(packageName, QualifiedName(moduleName, entityName))
                  case _ =>
                    throw HttpErrorHandler.badRequest(
                      s"Malformed template_id, expected 'package_name:module_name:entity_name'"
                    )
                }),
            )
            .map { result =>
              ScanResource.GetAcsSnapshotAtResponseOK(
                definitions.AcsResponse(
                  recordTime,
                  migrationId,
                  result.createdEventsInPage.map(LossyScanHttpEncodings.javaToHttpCreatedEvent(_)),
                  result.afterToken,
                )
              )
            }
      }
    }
  }

  override def getHoldingsStateAt(respond: ScanResource.GetHoldingsStateAtResponse.type)(
      body: HoldingsStateRequest
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsStateAtResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getAmuletStateAt") { _ => _ =>
      body match {
        case HoldingsStateRequest(migrationId, recordTime, after, pageSize, ownerPartyIds) =>
          snapshotStore
            .getHoldingsState(
              migrationId,
              CantonTimestamp.assertFromInstant(recordTime.toInstant),
              after,
              PageLimit.tryCreate(pageSize),
              ownerPartyIds.map(PartyId.tryFromProtoPrimitive),
            )
            .map { result =>
              ScanResource.GetHoldingsStateAtResponseOK(
                definitions.AcsResponse(
                  recordTime,
                  migrationId,
                  result.createdEventsInPage.map(LossyScanHttpEncodings.javaToHttpCreatedEvent(_)),
                  result.afterToken,
                )
              )
            }
      }
    }
  }

  override def getHoldingsSummaryAt(respond: ScanResource.GetHoldingsSummaryAtResponse.type)(
      body: HoldingsSummaryRequest
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsSummaryAtResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getHoldingsSummaryAt") { _ => _ =>
      body match {
        case HoldingsSummaryRequest(migrationId, recordTime, partyIds, asOfRound) =>
          for {
            round <- asOfRound match {
              case Some(round) => Future.successful(round)
              case None =>
                // gives the earliest mining round, as listContracts orders by event_number ASC
                store.multiDomainAcsStore
                  .listContracts(OpenMiningRound.COMPANION, PageLimit.tryCreate(1))
                  .map(
                    _.headOption.getOrElse(
                      throw Status.FAILED_PRECONDITION
                        .withDescription("No open mining rounds found.")
                        .asRuntimeException()
                    )
                  )
                  .map(_.contract.payload.round.number.toLong)
            }
            result <- snapshotStore
              .getHoldingsSummary(
                migrationId,
                CantonTimestamp.assertFromInstant(recordTime.toInstant),
                partyIds.map(PartyId.tryFromProtoPrimitive),
                round,
              )
          } yield ScanResource.GetHoldingsSummaryAtResponse.OK(
            definitions.HoldingsSummaryResponse(
              result.recordTime.toInstant.atOffset(ZoneOffset.UTC),
              result.migrationId,
              result.asOfRound,
              result.summaries.map { case (partyId, holdings) =>
                definitions.HoldingsSummary(
                  partyId = Codec.encode(partyId),
                  totalUnlockedCoin = Codec.encode(holdings.totalUnlockedCoin),
                  totalLockedCoin = Codec.encode(holdings.totalLockedCoin),
                  totalCoinHoldings = Codec.encode(holdings.totalCoinHoldings),
                  accumulatedHoldingFeesUnlocked =
                    Codec.encode(holdings.accumulatedHoldingFeesUnlocked),
                  accumulatedHoldingFeesLocked =
                    Codec.encode(holdings.accumulatedHoldingFeesLocked),
                  accumulatedHoldingFeesTotal = Codec.encode(holdings.accumulatedHoldingFeesTotal),
                  totalAvailableCoin = Codec.encode(holdings.totalAvailableCoin),
                )
              }.toVector,
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

  override def getUpdateById(
      respond: ScanResource.GetUpdateByIdResponse.type
  )(updateId: String)(extracted: TraceContext): Future[ScanResource.GetUpdateByIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getUpdateById") { _ => _ =>
      for {
        tx <- store.updateHistory.getUpdate(updateId)
      } yield {
        tx.fold(
          v0.ScanResource.GetUpdateByIdResponse.NotFound(
            definitions.ErrorResponse(s"Transaction with id $updateId not found")
          )
        )(txWithMigration =>
          v0.ScanResource.GetUpdateByIdResponse.OK(
            LossyScanHttpEncodings.lapiToHttpUpdate(txWithMigration)
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
              totalAmuletBalance = Codec.encode(roundTotal.totalAmuletBalance),
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

  override def getMigrationSchedule(
      respond: ScanResource.GetMigrationScheduleResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetMigrationScheduleResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getMigrationSchedule") { _ => _ =>
      OptionT(store.lookupDsoRules())
        .map(_.payload)
        .subflatMap { dsoRules =>
          dsoRules.config.nextScheduledSynchronizerUpgrade.toScala.map { nextUpgrade =>
            definitions.MigrationSchedule(
              java.time.OffsetDateTime
                .ofInstant(nextUpgrade.time, ZoneOffset.UTC),
              nextUpgrade.migrationId,
            )
          }
        }
        .fold(
          ScanResource.GetMigrationScheduleResponse.NotFound
        )(schedule =>
          ScanResource.GetMigrationScheduleResponse.OK(
            schedule
          )
        )
    }
  }

  override def getSpliceInstanceNames(
      respond: ScanResource.GetSpliceInstanceNamesResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetSpliceInstanceNamesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getSpliceInstanceNames") { _ => _ =>
      Future.successful {
        ScanResource.GetSpliceInstanceNamesResponse.OK(
          definitions.GetSpliceInstanceNamesResponse(
            networkName = spliceInstanceNames.networkName,
            networkFaviconUrl = spliceInstanceNames.networkFaviconUrl,
            amuletName = spliceInstanceNames.amuletName,
            amuletNameAcronym = spliceInstanceNames.amuletNameAcronym,
            nameServiceName = spliceInstanceNames.nameServiceName,
            nameServiceNameAcronym = spliceInstanceNames.nameServiceNameAcronym,
          )
        )
      }
    }
  }

  override def listDsoRulesVoteRequests(
      respond: ScanResource.ListDsoRulesVoteRequestsResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListDsoRulesVoteRequestsResponse] = {
    this
      .listDsoRulesVoteRequests(extracted, ec)
      .map(ScanResource.ListDsoRulesVoteRequestsResponse.OK)
  }

  override def listVoteRequestResults(respond: ScanResource.ListVoteRequestResultsResponse.type)(
      body: ListVoteResultsRequest
  )(extracted: TraceContext): Future[ScanResource.ListVoteRequestResultsResponse] = {
    implicit val tc: TraceContext = extracted
    this.listVoteRequestResults(body).map(ScanResource.ListVoteRequestResultsResponse.OK)
  }

  override def listVoteRequestsByTrackingCid(
      respond: ScanResource.ListVoteRequestsByTrackingCidResponse.type
  )(body: BatchListVotesByVoteRequestsRequest)(
      extracted: TraceContext
  ): Future[ScanResource.ListVoteRequestsByTrackingCidResponse] = {
    implicit val tc: TraceContext = extracted
    this
      .listVoteRequestsByTrackingCid(body)
      .map(ScanResource.ListVoteRequestsByTrackingCidResponse.OK)
  }

  override def lookupDsoRulesVoteRequest(
      respond: ScanResource.LookupDsoRulesVoteRequestResponse.type
  )(voteRequestContractId: String)(
      extracted: TraceContext
  ): Future[ScanResource.LookupDsoRulesVoteRequestResponse] = {
    implicit val tc: TraceContext = extracted
    this
      .lookupDsoRulesVoteRequest(voteRequestContractId)
      .map(ScanResource.LookupDsoRulesVoteRequestResponse.OK)
  }
}
