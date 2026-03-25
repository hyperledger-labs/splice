// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.{NonEmptyVector, OptionT}
import cats.implicits.catsSyntaxOptionId
import cats.syntax.either.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.data.ActiveContract
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{Member, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.{
  ByteStringUtil,
  ErrorUtil,
  GrpcStreamingUtils,
  MaxBytesToDecompress,
  MonadUtil,
  ResourceUtil,
}
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.daml.lf.data.Time.Timestamp
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet, ans as ansCodegen}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.SynchronizerNodeConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommand,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
  SummarizingMiningRound,
}
import org.lfdecentralizedtrust.splice.config.{SpliceInstanceNamesConfig, Thresholds}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  ParticipantAdminConnection,
  SequencerAdminConnection,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.http.{
  HttpFeatureSupportHandler,
  HttpValidatorLicensesHandler,
  HttpVotesHandler,
  UrlValidator,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan as v0}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AcsRequest,
  BatchListVotesByVoteRequestsRequest,
  DamlValueEncoding,
  ErrorResponse,
  EventHistoryRequest,
  HoldingsStateRequest,
  HoldingsSummaryRequest,
  ListBulkUpdateHistoryObjectsRequest,
  ListVoteResultsRequest,
  MaybeCachedContractWithState,
  UpdateHistoryItem,
  UpdateHistoryItemV2WithHash,
  UpdateHistoryRequestV2,
  UpdateHistoryTransactionV2WithHash,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryResponseItem.TransactionType.members.{
  AbortTransferInstruction,
  DevnetTap,
  Mint,
  Transfer,
}
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings.updateV1ToUpdateV2
import org.lfdecentralizedtrust.splice.scan.config.{BftSequencerConfig, ScanRollForwardLsuConfig}
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
  TxLogEntry,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStorage,
  UpdateHistoryBulkStorage,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.scan.store.db.ScanAggregator.{RoundPartyTotals, RoundTotals}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.TxLogBackfillingState
import org.lfdecentralizedtrust.splice.store.{
  AppStore,
  AppStoreWithIngestion,
  PageLimit,
  SortOrder,
  VotesStore,
}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.lfdecentralizedtrust.splice.store.UpdateHistory
import java.lang.IllegalStateException
import scala.collection.immutable.SortedMap
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  PackageQualifiedName,
  QualifiedName,
}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.Base64
import java.util.zip.GZIPOutputStream
import scala.collection.concurrent
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.{Try, Using}

class HttpScanHandler(
    svParty: PartyId,
    svUserName: String,
    spliceInstanceNames: SpliceInstanceNamesConfig,
    participantAdminConnection: ParticipantAdminConnection,
    synchronizerNodeService: SynchronizerNodeService[ScanSynchronizerNode],
    protected val storeWithIngestion: AppStoreWithIngestion[ScanStore],
    updateHistory: UpdateHistory,
    appRewardsStoreO: Option[DbScanAppRewardsStore],
    appActivityStoreO: Option[AppActivityStore],
    snapshotStore: AcsSnapshotStore,
    eventStore: ScanEventStore,
    bulkStorage: BulkStorage,
    dsoAnsResolver: DsoAnsResolver,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration],
    enableForcedAcsSnapshots: Boolean,
    serveAppActivityRecordsAndTraffic: Boolean,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
    protected val packageVersionSupport: PackageVersionSupport,
    bftSequencers: Seq[(SequencerAdminConnection, BftSequencerConfig)],
    initialRound: String,
    externalTransactionHashThresholdTime: Option[Instant] = None,
    updateHistoryMaxPageSize: Int,
    publicUrlO: Option[Uri],
    lsuRollForwardConfigO: Option[ScanRollForwardLsuConfig],
)(implicit
    ec: ExecutionContextExecutor,
    protected val tracer: Tracer,
) extends v0.ScanHandler[TraceContext]
    with HttpVotesHandler
    with HttpValidatorLicensesHandler
    with HttpFeatureSupportHandler {

  import HttpScanHandler.*

  private val store = storeWithIngestion.store

  override protected val workflowId: String = this.getClass.getSimpleName
  override protected val votesStore: VotesStore = store
  override protected val validatorLicensesStore: AppStore = store

  private val initializedBftSequencersCache
      : concurrent.Map[Int, definitions.SynchronizerBftSequencer] =
    new java.util.concurrent.ConcurrentHashMap[Int, definitions.SynchronizerBftSequencer]().asScala

  private val uninitializedBftSequencersCooldown: Cache[Int, Unit] =
    Scaffeine().expireAfterWrite(10.seconds).build[Int, Unit]()

  private implicit val offsetDateTimeCodecInstance: Codec[CantonTimestamp, OffsetDateTime] =
    Codec.OffsetDateTime.instance

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
        initialRound = Some(initialRound),
      )
    }
  }

  def getOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetOpenAndIssuingMiningRoundsRequest
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
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetAmuletRulesRequest
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
                show"Not sending ${PrettyContractId(AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID, cachedContractId)}, as it is cached by the client."
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

  def getExternalPartyAmuletRules(
      response: v0.ScanResource.GetExternalPartyAmuletRulesResponse.type
  )(
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetExternalPartyAmuletRulesRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetExternalPartyAmuletRulesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getExternalPartyAmuletRules") { _ => _ =>
      for {
        externalPartyAmuletRules <- store.getExternalPartyAmuletRules()
      } yield {
        val response = MaybeCachedContractWithState(
          body.cachedExternalPartyAmuletRulesContractId match {
            case Some(cachedContractId)
                if cachedContractId == externalPartyAmuletRules.contractId.contractId =>
              logger.debug(
                show"Not sending ${PrettyContractId(ExternalPartyAmuletRules.TEMPLATE_ID, cachedContractId)}, as it is cached by the client."
              )
              None
            case Some(_) // else: external party amulet rules are cached but outdated.
                | None =>
              Some(externalPartyAmuletRules.contract.toHttp)
          },
          domainId =
            externalPartyAmuletRules.state.fold(domain => Some(domain.toProtoPrimitive), None),
        )
        definitions.GetExternalPartyAmuletRulesResponse(
          externalPartyAmuletRulesUpdate = response
        )
      }
    }
  }

  def getAnsRules(
      response: v0.ScanResource.GetAnsRulesResponse.type
  )(
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetAnsRulesRequest
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
                show"Not sending ${PrettyContractId(ansCodegen.AnsRules.TEMPLATE_ID_WITH_PACKAGE_ID, cachedContractId)}, as it is cached by the client."
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
      response: org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse.type
  )(providerPartyId: String)(extracted: TraceContext): Future[
    org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupFeaturedAppRight") { _ => _ =>
      for {
        right <- store.lookupFeaturedAppRight(
          PartyId.tryFromProtoPrimitive(providerPartyId)
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(_.contract.toHttp))
      }
    }
  }

  def listFeaturedAppRightsByProvider(
      response: v0.ScanResource.ListFeaturedAppRightsByProviderResponse.type
  )(providerPartyId: String)(extracted: TraceContext): Future[
    v0.ScanResource.ListFeaturedAppRightsByProviderResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listFeaturedAppRightsByProvider") { _ => _ =>
      for {
        rights <- store.listFeaturedAppRightsByProvider(
          PartyId.tryFromProtoPrimitive(providerPartyId)
        )
      } yield {
        definitions.ListFeaturedAppRightsResponse(
          rights.toVector.map(_.contract.toHttp)
        )
      }
    }
  }

  def lookupFeaturedAppRightByContractId(
      response: v0.ScanResource.LookupFeaturedAppRightByContractIdResponse.type
  )(contractId: String)(extracted: TraceContext): Future[
    v0.ScanResource.LookupFeaturedAppRightByContractIdResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupFeaturedAppRightByContractId") { _ => _ =>
      for {
        right <- store.multiDomainAcsStore.lookupContractById(
          amulet.FeaturedAppRight.COMPANION
        )(new amulet.FeaturedAppRight.ContractId(contractId))
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(_.contract.toHttp))
      }
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
        .map { cfg =>
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
        }
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
      // TODO(DACH-NY/canton-network-internal#459): Provide an upper bound for limit
      store
        .getTopProvidersByAppRewards(asOfEndOfRound, limit)
        .map(res =>
          if (res.isEmpty) {
            v0.ScanResource.GetTopProvidersByAppRewardsResponse.NotFound(
              ErrorResponse(s"No top providers by app rewards found for round $asOfEndOfRound")
            )
          } else {
            v0.ScanResource.GetTopProvidersByAppRewardsResponse.OK(
              definitions
                .GetTopProvidersByAppRewardsResponse(
                  res
                    .map(p => definitions.PartyAndRewards(Codec.encode(p._1), Codec.encode(p._2)))
                    .toVector
                )
            )
          }
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
      // TODO(DACH-NY/canton-network-internal#459): Provide an upper bound for limit
      store
        .getTopValidatorsByValidatorRewards(asOfEndOfRound, limit)
        .map(res =>
          if (res.isEmpty) {
            v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse.NotFound(
              ErrorResponse(
                s"No top validators by validator rewards found for round $asOfEndOfRound"
              )
            )
          } else {
            v0.ScanResource.GetTopValidatorsByValidatorRewardsResponse.OK(
              definitions
                .GetTopValidatorsByValidatorRewardsResponse(
                  res
                    .map(p => definitions.PartyAndRewards(Codec.encode(p._1), Codec.encode(p._2)))
                    .toVector
                )
            )
          }
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
                FaucetProcessor.process(licenses)
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
      // TODO(DACH-NY/canton-network-internal#459): Provide an upper bound for limit
      store
        .getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit)
        .map(validatorTraffic =>
          if (validatorTraffic.isEmpty) {
            v0.ScanResource.GetTopValidatorsByPurchasedTrafficResponse.NotFound(
              ErrorResponse(
                s"No top validators by purchased traffic found for round $asOfEndOfRound"
              )
            )
          } else {
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
          }
        )
        .transform(
          HttpErrorHandler.onGrpcNotFound(s"Data for round ${asOfEndOfRound} not yet computed")
        )
    }
  }

  override def listValidatorLicenses(
      respond: ScanResource.ListValidatorLicensesResponse.type
  )(after: Option[Long], limit: Option[Int])(
      extracted: TraceContext
  ): Future[ScanResource.ListValidatorLicensesResponse] = {
    this
      .listValidatorLicenses(after, limit)(extracted, ec)
      .map(ScanResource.ListValidatorLicensesResponse.OK)
  }

  override def listDsoSequencers(
      respond: v0.ScanResource.ListDsoSequencersResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.ListDsoSequencersResponse] = {
    implicit val tc = extracted

    def extractSequencersForSynchronizersFromLegacyState(
        nodeName: String,
        synchronizerConfig: SynchronizerNodeConfig,
    ) = {
      val sequencers = for {
        sequencer <- synchronizerConfig.sequencer.toScala
        availableAfter <- sequencer.availableAfter.toScala
      } yield definitions.DsoSequencer(
        sequencer.migrationId,
        None,
        sequencer.sequencerId,
        sequencer.url,
        nodeName,
        OffsetDateTime.ofInstant(availableAfter, ZoneOffset.UTC),
      )
      val legacySequencers = for {
        legacyConfig <- synchronizerConfig.legacySequencerConfig.toScala.toList
      } yield definitions.DsoSequencer(
        legacyConfig.migrationId,
        None,
        legacyConfig.sequencerId,
        legacyConfig.url,
        nodeName,
        OffsetDateTime.MIN,
      )
      (legacySequencers ++ sequencers).distinct
    }

    def extractSequencersForSynchronizers(
        nodeName: String,
        synchronizerConfig: SynchronizerNodeConfig,
    ) = {
      synchronizerConfig.physicalSynchronizers.toScala.toList.flatMap(_.asScala.flatMap {
        case (serial, nodeConfig) =>
          nodeConfig.sequencer.toScala.flatMap { sequencerConfig =>
            synchronizerConfig.sequencerIdentity.toScala.flatMap { identity =>
              identity.availableAfter.toScala.map { availableAfter =>
                definitions.DsoSequencer(
                  NoMigrationIdSet,
                  Some(serial),
                  identity.sequencerId,
                  sequencerConfig.url,
                  nodeName,
                  OffsetDateTime.ofInstant(availableAfter, ZoneOffset.UTC),
                )
              }
            }
          }
      })
    }

    def extractSequencersFromNodeState(nodeState: SvNodeState) = {
      nodeState.state.synchronizerNodes.asScala.toVector
        .flatMap { case (synchronizerId, domainConfig) =>
          val legacyConfig = extractSequencersForSynchronizersFromLegacyState(
            nodeState.svName,
            domainConfig,
          )
          val physicalSequencers = extractSequencersForSynchronizers(
            nodeState.svName,
            domainConfig,
          )
          (legacyConfig ++ physicalSequencers).map(synchronizerId -> _)
        }
    }

    withSpan(s"$workflowId.listDsoSequencers") { _ => _ =>
      store
        .listFromSvNodeStates { nodeState =>
          extractSequencersFromNodeState(nodeState)
        }
        .map(list =>
          list.map { case (synchronizerId, sequencers) =>
            synchronizerId -> sequencers.filter { sequencer =>
              UrlValidator.isValid(sequencer.url) match {
                case Left(failure) =>
                  logger.warn(
                    s"Not serving sequencer $sequencer for domain $synchronizerId as it has an invalid url: $failure"
                  )
                  false
                case Right(_) => true
              }
            }
          }
        )
        .map(list =>
          definitions.ListDsoSequencersResponse(list.map { case (synchronizerId, sequencers) =>
            definitions.DomainSequencers(synchronizerId, sequencers.toVector)
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
          definitions.ListDsoScansResponse(list.map { case (synchronizerId, scans) =>
            definitions.DomainScans(
              synchronizerId,
              scans.map(s => definitions.ScanInfo(s.publicUrl, s.svName)).toVector,
            )
          })
        )
    }
  }

  override def getActivePhysicalSynchronizerSerial(
      respond: ScanResource.GetActivePhysicalSynchronizerSerialResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetActivePhysicalSynchronizerSerialResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getActivePhysicalSynchronizerSerial") { _ => _ =>
      for {
        synchronizerId <- store
          .lookupAmuletRules()
          .map(_.flatMap(_.state.fold(_.some, None)))
        connectedDomains <- participantAdminConnection.listConnectedDomains()
      } yield {
        synchronizerId.fold(
          ScanResource.GetActivePhysicalSynchronizerSerialResponse.NotFound(
            definitions.ErrorResponse(
              "No amulet rules"
            )
          )
        )(syncId =>
          connectedDomains
            .find(_.synchronizerId == syncId)
            .map(_.physicalSynchronizerId) match {
            case Some(psid) =>
              ScanResource.GetActivePhysicalSynchronizerSerialResponse.OK(
                definitions.GetActivePhysicalSynchronizerSerialResponse(
                  serial = psid.serial.unwrap.toLong
                )
              )
            case None =>
              ScanResource.GetActivePhysicalSynchronizerSerialResponse.NotFound(
                definitions.ErrorResponse(
                  "No active synchronizer connected"
                )
              )
          }
        )
      }
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

  def getUpdateHistory(
      after: Option[definitions.UpdateHistoryRequestAfter] = None,
      pageSize: Int,
      encoding: definitions.DamlValueEncoding,
      consistentResponses: Boolean,
      includeImportUpdates: Boolean,
      extracted: TraceContext,
  ): Future[Vector[definitions.UpdateHistoryItem]] = {
    implicit val tc: TraceContext = extracted
    val afterO = after.map { after =>
      val afterRecordTime = parseTimestamp(after.afterRecordTime)
      (
        after.afterMigrationId,
        afterRecordTime,
      )
    }
    confirmBackfillingIsCompleteThen(updateHistory) {
      for {
        txs <-
          if (includeImportUpdates)
            updateHistory.getAllUpdates(
              afterO,
              PageLimit.tryCreate(pageSize, updateHistoryMaxPageSize),
            )
          else
            updateHistory.getUpdatesWithoutImportUpdates(
              afterO,
              PageLimit.tryCreate(pageSize, updateHistoryMaxPageSize),
            )
      } yield txs
        .map(
          ScanHttpEncodings.encodeUpdate(
            _,
            encoding = encoding,
            version = if (consistentResponses) ScanHttpEncodings.V1 else ScanHttpEncodings.V0,
            hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
            externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
          )
        )
        .toVector
    }
  }

  override def getUpdateHistory(respond: v0.ScanResource.GetUpdateHistoryResponse.type)(
      request: definitions.UpdateHistoryRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetUpdateHistoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getUpdateHistoryV0") { _ => _ =>
      val encoding =
        if (request.lossless.contains(true)) {
          definitions.DamlValueEncoding.ProtobufJson
        } else {
          definitions.DamlValueEncoding.CompactJson
        }
      getUpdateHistory(
        after = request.after,
        pageSize = request.pageSize,
        encoding = encoding,
        consistentResponses = false,
        // Originally this endpoint included import updates. This is changed in the V1 endpoint.
        // Almost all clients will want to filter them out to prevent duplicate contracts
        // (once from the actual create event and once from the import update).
        // Also, all import updates have a record time of 0 and thus don't work with pagination by record time.
        // In this v0 version, we keep `includeImportUpdates = true` to maintain backward compatibility.
        includeImportUpdates = true,
        extracted,
      ).map(
        definitions.UpdateHistoryResponse(_)
      )
    }
  }

  override def getUpdateHistoryV1(respond: v0.ScanResource.GetUpdateHistoryV1Response.type)(
      request: definitions.UpdateHistoryRequestV1
  )(extracted: TraceContext): Future[v0.ScanResource.GetUpdateHistoryV1Response] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getUpdateHistoryV1") { _ => _ =>
      getUpdateHistory(
        after = request.after,
        pageSize = request.pageSize,
        encoding = request.damlValueEncoding.getOrElse(definitions.DamlValueEncoding.CompactJson),
        consistentResponses = true,
        includeImportUpdates = false,
        extracted,
      )
        .map(
          definitions.UpdateHistoryResponse(_)
        )
    }
  }

  override def getUpdateHistoryV2(respond: ScanResource.GetUpdateHistoryV2Response.type)(
      request: UpdateHistoryRequestV2
  )(extracted: TraceContext): Future[ScanResource.GetUpdateHistoryV2Response] = {
    implicit val tc: TraceContext = extracted
    val encoding = request.damlValueEncoding.getOrElse(definitions.DamlValueEncoding.CompactJson)

    def afterMsg = request.after
      .map(a =>
        s": afterMigrationId = ${a.afterMigrationId}, afterRecordTime = ${a.afterRecordTime},"
      )
      .getOrElse(":")

    logger.debug(
      s"Requesting updateHistory${afterMsg} pageSize = ${request.pageSize}, encoding = $encoding"
    )
    withSpan(s"$workflowId.getUpdateHistoryV2") { _ => _ =>
      getUpdateHistory(
        after = request.after,
        pageSize = request.pageSize,
        encoding = encoding,
        consistentResponses = true,
        includeImportUpdates = false,
        extracted,
      )
        .map(items => definitions.UpdateHistoryResponseV2(items.map(updateV1ToUpdateV2)))
    }
  }

  def getEventById(
      updateId: String,
      encoding: definitions.DamlValueEncoding,
      extracted: TraceContext,
  ): Future[Either[definitions.ErrorResponse, definitions.EventHistoryItem]] = {
    implicit val tc = extracted
    for {
      eventO <- eventStore.getEventByUpdateId(
        updateId,
        updateHistory.domainMigrationInfo.currentMigrationId,
      )
      result <- eventO match {
        case None =>
          Future.successful(
            Left(definitions.ErrorResponse(s"Event with id $updateId not found"))
          )
        case Some((verdictWithViewsO, updateO)) =>
          val verdictRowIdO = verdictWithViewsO.map { case (v, _) => v.rowId }
          for {
            appActivityRecordO <-
              if (serveAppActivityRecordsAndTraffic)
                verdictRowIdO match {
                  case Some(rowId) =>
                    eventStore.getAppActivityRecords(Seq(rowId)).map(_.get(rowId))
                  case None => Future.successful(None)
                }
              else Future.successful(None)
          } yield {
            val encodedUpdateV2 = updateO
              .map(
                ScanHttpEncodings.encodeUpdateV2(
                  _,
                  encoding,
                  ScanHttpEncodings.V1,
                  hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
                  externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
                )
              )
            val verdictEncoded = verdictWithViewsO.map { case (v, views) =>
              ScanHttpEncodings.encodeVerdict(v, views)
            }
            val trafficSummaryEncoded =
              if (serveAppActivityRecordsAndTraffic)
                verdictWithViewsO.flatMap { case (v, _) =>
                  v.trafficSummaryO.map(ScanHttpEncodings.encodeTrafficSummary)
                }
              else None
            val appActivityRecordEncoded = appActivityRecordO.map(
              ScanHttpEncodings.encodeAppActivityRecord
            )
            Right(
              definitions.EventHistoryItem(
                encodedUpdateV2,
                verdictEncoded,
                trafficSummaryEncoded,
                appActivityRecordEncoded,
              )
            )
          }
      }
    } yield result
  }

  override def getEventById(respond: ScanResource.GetEventByIdResponse.type)(
      updateId: String,
      damlValueEncoding: Option[definitions.DamlValueEncoding],
  )(extracted: TraceContext): Future[ScanResource.GetEventByIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getEventById") { _ => _ =>
      val encoding = damlValueEncoding.getOrElse(definitions.DamlValueEncoding.CompactJson)
      getEventById(
        updateId = updateId,
        encoding = encoding,
        extracted,
      ).map {
        case Left(error) => ScanResource.GetEventByIdResponse.NotFound(error)
        case Right(update) => ScanResource.GetEventByIdResponse.OK(update)
      }
    }
  }

  def getEventHistory(
      after: Option[definitions.UpdateHistoryRequestAfter] = None,
      pageSize: Int,
      encoding: definitions.DamlValueEncoding,
      extracted: TraceContext,
  ): Future[Vector[definitions.EventHistoryItem]] = {
    implicit val tc: TraceContext = extracted
    val afterO = after.map { a =>
      val afterRecordTime = parseTimestamp(a.afterRecordTime)
      (a.afterMigrationId, afterRecordTime)
    }

    confirmBackfillingIsCompleteThen(updateHistory) {
      for {
        events <- eventStore.getEvents(
          afterO = afterO,
          currentMigrationId = updateHistory.domainMigrationInfo.currentMigrationId,
          limit = PageLimit.tryCreate(pageSize, updateHistoryMaxPageSize),
        )
        verdictRowIds = events.flatMap { case (verdictWithViewsO, _) =>
          verdictWithViewsO.map { case (v, _) => v.rowId }
        }
        appActivityRecordMap <-
          if (serveAppActivityRecordsAndTraffic) eventStore.getAppActivityRecords(verdictRowIds)
          else Future.successful(Map.empty[Long, eventStore.AppActivityRecordT])
      } yield events.map { case (verdictWithViewsO, updateO) =>
        val encodedUpdateV2 = updateO
          .map(
            ScanHttpEncodings.encodeUpdateV2(
              _,
              encoding,
              ScanHttpEncodings.V1,
              hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
              externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
            )
          )
        val verdictEncoded = verdictWithViewsO.map { case (v, views) =>
          ScanHttpEncodings.encodeVerdict(v, views)
        }
        val trafficSummaryEncoded =
          if (serveAppActivityRecordsAndTraffic)
            verdictWithViewsO.flatMap { case (v, _) =>
              v.trafficSummaryO.map(ScanHttpEncodings.encodeTrafficSummary)
            }
          else None
        val appActivityRecordEncoded = verdictWithViewsO.flatMap { case (v, _) =>
          appActivityRecordMap.get(v.rowId).map(ScanHttpEncodings.encodeAppActivityRecord)
        }
        definitions.EventHistoryItem(
          encodedUpdateV2,
          verdictEncoded,
          trafficSummaryEncoded,
          appActivityRecordEncoded,
        )
      }.toVector
    }
  }

  override def getEventHistory(respond: ScanResource.GetEventHistoryResponse.type)(
      request: EventHistoryRequest
  )(extracted: TraceContext): Future[ScanResource.GetEventHistoryResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getEventHistory") { _ => _ =>
      getEventHistory(
        after = request.after,
        pageSize = request.pageSize,
        encoding = request.damlValueEncoding.getOrElse(definitions.DamlValueEncoding.CompactJson),
        extracted,
      )
        .map(items => definitions.EventHistoryResponse(items))
    }
  }

  private def toUpdateV2WithHash(update: UpdateHistoryItem): UpdateHistoryItemV2WithHash =
    update match {
      case UpdateHistoryItem.members.UpdateHistoryReassignment(r) =>
        UpdateHistoryItemV2WithHash(
          UpdateHistoryItemV2WithHash.members.UpdateHistoryReassignment(r)
        )
      case UpdateHistoryItem.members.UpdateHistoryTransaction(t) =>
        UpdateHistoryItemV2WithHash(
          UpdateHistoryTransactionV2WithHash(
            updateId = t.updateId,
            migrationId = t.migrationId,
            workflowId = t.workflowId,
            recordTime = t.recordTime,
            synchronizerId = t.synchronizerId,
            effectiveAt = t.effectiveAt,
            rootEventIds = t.rootEventIds,
            eventsById = SortedMap.from(t.eventsById),
            externalTransactionHash = t.externalTransactionHash.getOrElse(
              throw new IllegalStateException("externalTransactionHash must not be empty")
            ),
          )
        )
    }

  private def confirmBackfillingIsCompleteThen[T](
      updateHistory: UpdateHistory
  )(body: => Future[T])(implicit tc: TraceContext): Future[T] = {
    updateHistory
      .getBackfillingState()
      .flatMap {
        case BackfillingState.NotInitialized =>
          Future.failed(
            Status.UNAVAILABLE
              .withDescription(
                "This scan instance has not yet loaded its updates history. Wait a short time and retry."
              )
              .asRuntimeException()
          )
        case BackfillingState.InProgress(_, _) =>
          Future.failed(
            Status.UNAVAILABLE
              .withDescription(
                "This scan instance has not yet replicated all data. This process can take an extended period of time to complete. " +
                  "Wait until replication is complete, or connect to a different scan instance."
              )
              .asRuntimeException()
          )
        case BackfillingState.Complete => body
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
              case AbortTransferInstruction =>
                ActivityType.AbortTransferInstruction
            },
            eventId = txItem.eventId,
            offset = txItem.offset,
            domainId = txItem.domainId,
            date = txItem.date,
            mint = txItem.mint,
            tap = txItem.tap,
            transfer = txItem.transfer,
            abortTransferInstruction = txItem.abortTransferInstruction,
            round = txItem.round,
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

  override def lookupTransferPreapprovalByParty(
      respond: ScanResource.LookupTransferPreapprovalByPartyResponse.type
  )(
      party: String
  )(extracted: TraceContext): Future[ScanResource.LookupTransferPreapprovalByPartyResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupTransferPreapprovalByParty") { _ => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(party)
      store
        .lookupTransferPreapprovalByParty(
          partyId
        )
        .map {
          case Some(c) =>
            v0.ScanResource.LookupTransferPreapprovalByPartyResponse.OK(
              definitions.LookupTransferPreapprovalByPartyResponse(
                c.toHttp
              )
            )
          case None =>
            v0.ScanResource.LookupTransferPreapprovalByPartyResponse.NotFound(
              definitions.ErrorResponse(s"No TransferPreapproval found for party: $party")
            )
        }
    }
  }

  override def lookupTransferCommandCounterByParty(
      respond: ScanResource.LookupTransferCommandCounterByPartyResponse.type
  )(
      party: String
  )(extracted: TraceContext): Future[ScanResource.LookupTransferCommandCounterByPartyResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupTransferCommandCounterByParty") { _ => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(party)
      store
        .lookupTransferCommandCounterByParty(
          partyId
        )
        .map {
          case Some(c) =>
            v0.ScanResource.LookupTransferCommandCounterByPartyResponse.OK(
              definitions.LookupTransferCommandCounterByPartyResponse(
                c.toHttp
              )
            )
          case None =>
            v0.ScanResource.LookupTransferCommandCounterByPartyResponse.NotFound(
              definitions.ErrorResponse(
                s"No TransferCommandCounter found for party: $party, use 0 for the nonce"
              )
            )
        }
    }
  }

  override def lookupTransferCommandStatus(
      respond: ScanResource.LookupTransferCommandStatusResponse.type
  )(
      sender: String,
      nonce: Long,
  )(extracted: TraceContext): Future[ScanResource.LookupTransferCommandStatusResponse] = {
    implicit val tc = extracted
    val senderParty = PartyId.tryFromProtoPrimitive(sender)
    withSpan(s"$workflowId.lookupTransferCommandStatus") { _ => _ =>
      for {
        txLogEntryMap <- store.lookupLatestTransferCommandEvents(
          senderParty,
          nonce,
          HttpScanHandler.MAX_TRANSFER_COMMAND_CONTRACTS,
        )
        filteredMap <- MonadUtil
          .sequentialTraverse(txLogEntryMap.view.toList) { case (cid, entry) =>
            // The update history ingests independently so this lookup can return None temporarily.
            // We just filter out those contracts.
            updateHistory
              .lookupContractById(TransferCommand.COMPANION)(cid)
              .map(
                _.map(c =>
                  cid.contractId -> definitions.TransferCommandContractWithStatus(
                    c.toHttp,
                    TxLogEntry.Http.toResponse(entry.status),
                  )
                )
              )
          }
          .map(_.flatten.toMap)
      } yield {
        if (filteredMap.isEmpty) {
          v0.ScanResource.LookupTransferCommandStatusResponseNotFound(
            definitions.ErrorResponse(
              s"Couldn't find transfer command for sender $senderParty with nonce $nonce created in the last 24h"
            )
          )
        } else {
          v0.ScanResource.LookupTransferCommandStatusResponseOK(
            definitions.LookupTransferCommandStatusResponse(filteredMap)
          )
        }
      }
    }
  }

  /** Filter the given ACS snapshot to contracts the given party is a stakeholder on */
  // TODO(#828) Move this logic inside a Canton gRPC API.
  private def filterAcsSnapshot(input: ByteString, stakeholder: PartyId): ByteString = {
    val decompressedBytes =
      ByteStringUtil
        .decompressGzip(input, MaxBytesToDecompress.MaxValueUnsafe)
        .valueOr(err =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"Failed to decompress bytes: $err")
            .asRuntimeException
        )
    val contracts = ResourceUtil.withResource(
      new ByteArrayInputStream(decompressedBytes.toByteArray)
    ) { inputSource =>
      GrpcStreamingUtils
        .parseDelimitedFromTrusted[ActiveContract](
          inputSource,
          ActiveContract,
        )
        .valueOr(err =>
          throw Status.INVALID_ARGUMENT
            .withDescription(s"Failed to parse contracts in acs snapshot: $err")
            .asRuntimeException
        )
    }
    val output = ByteString.newOutput
    Using.resource(new GZIPOutputStream(output)) { outputStream =>
      contracts
        .filter(c =>
          c.contract.getCreatedEvent.signatories.contains(
            stakeholder.toLf
          ) || c.contract.getCreatedEvent.observers.contains(stakeholder.toLf)
        )
        .foreach { c =>
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

  override def getAcsSnapshot(
      respond: ScanResource.GetAcsSnapshotResponse.type
  )(party: String, recordTimeStr: Option[String])(
      extracted: com.digitalasset.canton.tracing.TraceContext
  ): Future[ScanResource.GetAcsSnapshotResponse] = {
    val recordTime = recordTimeStr.map(parseTimestamp)
    implicit val tc = extracted
    withSpan(s"$workflowId.getAcsSnapshot") { _ => _ =>
      val partyId = PartyId.tryFromProtoPrimitive(party)
      for {
        synchronizerId <- store.getDecentralizedSynchronizerId()
        // The DSO party is a stakeholder on all "important" contracts, in particular, all amulet holdings and ANS entries.
        // This means the SV participants ingest data for that party and we can take a snapshot for that party.
        // To make sure the snapshot is the same regardless of which SV is queried, we filter it down to
        // contracts that the DSO party is also a stakeholder on.
        // It does however lose third-party application data that the DSO party is not a stakeholder on. Supporting that requires
        // that users backup their own ACS.
        // As the DSO party is hosted on all SVs, an arbitrary scan instance can be chosen for the ACS snapshot.
        // BFT reads are usually not required since ACS commitments act as a check that the ACS was correct.
        timestampOrOffset <- recordTime match {
          case None =>
            storeWithIngestion
              .connection(SpliceLedgerConnectionPriority.Low)
              .ledgerEnd()
              .map(offset => Right(offset))
          case Some(time) => Future.successful(Left(time.toInstant))
        }
        acsSnapshot <- participantAdminConnection.downloadAcsSnapshotNonChunked(
          Set(partyId),
          synchronizerId,
          timestampOrOffset,
        )
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

  private def getRecordTimeAtOrBefore(migrationId: Long, before: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] =
    OptionT(
      snapshotStore
        .lookupSnapshotAtOrBefore(migrationId, before)
    ).map {
      _.snapshotRecordTime
    }.value

  override def getDateOfMostRecentSnapshotBefore(
      respond: ScanResource.GetDateOfMostRecentSnapshotBeforeResponse.type
  )(before: OffsetDateTime, migrationId: Long)(
      extracted: TraceContext
  ): Future[ScanResource.GetDateOfMostRecentSnapshotBeforeResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getDateOfMostRecentSnapshotBefore") { _ => _ =>
      snapshotStore
        .lookupSnapshotAtOrBefore(migrationId, Codec.tryDecode(Codec.OffsetDateTime)(before))
        .map {
          case Some(snapshot) =>
            ScanResource.GetDateOfMostRecentSnapshotBeforeResponseOK(
              definitions
                .AcsSnapshotTimestampResponse(
                  Codec.encode(snapshot.snapshotRecordTime)
                )
            )
          case None =>
            ScanResource.GetDateOfMostRecentSnapshotBeforeResponseNotFound(
              definitions.ErrorResponse(s"No snapshots found before $before")
            )
        }
    }
  }

  override def getDateOfFirstSnapshotAfter(
      respond: ScanResource.GetDateOfFirstSnapshotAfterResponse.type
  )(after: OffsetDateTime, migrationId: Long)(
      extracted: TraceContext
  ): Future[ScanResource.GetDateOfFirstSnapshotAfterResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getDateOfFirstSnapshotAfter") { _ => _ =>
      snapshotStore
        .lookupSnapshotAfter(migrationId, Codec.tryDecode(Codec.OffsetDateTime)(after))
        .map {
          case Some(snapshot) =>
            ScanResource.GetDateOfFirstSnapshotAfterResponseOK(
              definitions
                .AcsSnapshotTimestampResponse(
                  Codec.encode(snapshot.snapshotRecordTime)
                )
            )
          case None =>
            ScanResource.GetDateOfFirstSnapshotAfterResponseNotFound(
              definitions.ErrorResponse(s"No snapshots found after $after")
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
          synchronizerId <- store
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
          snapshotTime <- updateHistory
            .getUpdatesBefore(
              snapshotStore.currentMigrationId,
              synchronizerId,
              CantonTimestamp.MaxValue,
              None,
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
          lastSnapshot <- snapshotStore.lookupSnapshotAtOrBefore(
            snapshotStore.currentMigrationId,
            snapshotTime,
          )
          _ <-
            if (lastSnapshot.exists(_.snapshotRecordTime == snapshotTime)) {
              logger.debug(
                s"ACS snapshot at $snapshotTime already existed, likely to happen in simtime tests."
              )
              Future.successful(snapshotTime)
            } else {
              logger.info(s"Forcing ACS snapshot at $snapshotTime. Last snapshot: $lastSnapshot")
              // note that this will make it so that the next snapshot is taken N hours after THIS snapshot.
              // this is, in principle, not a problem:
              // - this will only be used in tests
              // - wall clock tests must take manual snapshots anyway, because they can't wait
              // - simtime tests will advanceTime(N.hours)
              snapshotStore.insertNewSnapshot(
                lastSnapshot,
                snapshotStore.currentMigrationId,
                snapshotTime,
              )
            }
        } yield ScanResource.ForceAcsSnapshotNowResponse.OK(
          definitions.ForceAcsSnapshotResponse(
            Codec.encode(snapshotTime),
            snapshotStore.currentMigrationId,
          )
        )
      }
    }
  }

  private def queryWithOptionalAtOrBefore[S, T](
      migrationId: Long,
      recordTime: OffsetDateTime,
      recordTimeIsAtOrBefore: Boolean,
      exactQuery: CantonTimestamp => Future[S],
      toResponse: S => T,
  )(implicit tc: TraceContext): Future[Either[String, T]] = {
    val recordTimeTs = Codec.tryDecode(Codec.OffsetDateTime)(recordTime)
    if (recordTimeIsAtOrBefore) {
      val snapshotQueryResult = for {
        recordTime <- OptionT(getRecordTimeAtOrBefore(migrationId, recordTimeTs))
        snapshotQueryResult <- OptionT.liftF(exactQuery(recordTime))
      } yield snapshotQueryResult
      snapshotQueryResult.fold[Either[String, T]](
        Left(s"No snapshots found before $recordTime")
      )(res => Right(toResponse(res)))
    } else {
      exactQuery(recordTimeTs).map(res => Right(toResponse(res)))
    }
  }

  // Shared between /v0/state/acs and /v1/state/acs. The only difference between them is in `toResponse`.
  private def acsSnapshotQuery[T](request: AcsRequest, toResponse: QueryAcsSnapshotResult => T)(
      implicit tc: TraceContext
  ): Future[Either[String, T]] = {
    val AcsRequest(
      migrationId,
      recordTime,
      recordTimeMatch,
      after,
      pageSize,
      partyIds,
      templates,
    ) = request

    def exactQuery(recordTimeTs: CantonTimestamp) = snapshotStore
      .queryAcsSnapshot(
        migrationId,
        recordTimeTs,
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

    queryWithOptionalAtOrBefore(
      migrationId,
      recordTime,
      recordTimeMatch.contains(AcsRequest.RecordTimeMatch.AtOrBefore),
      exactQuery,
      toResponse,
    )

  }

  private def toAcsV0Response(migrationId: Long, result: QueryAcsSnapshotResult)(implicit
      tc: TraceContext
  ) = {
    definitions.AcsResponse(
      Codec.encode(result.snapshotRecordTime),
      migrationId,
      result.createdEventsInPage
        .map(event =>
          CompactJsonScanHttpEncodings().javaToHttpCreatedEvent(
            event.eventId,
            event.event,
          )
        ),
      result.afterToken,
    )
  }

  private def toAcsV1Response(migrationId: Long, result: QueryAcsSnapshotResult)(implicit
      tc: TraceContext
  ) =
    definitions.AcsResponseV1(
      Codec.encode(result.snapshotRecordTime),
      migrationId,
      result.createdEventsInPage
        .map(event =>
          CompactJsonScanHttpEncodings().javaToHttpActiveContract(
            event.eventId,
            event.event,
          )
        ),
      result.afterToken,
    )

  override def getAcsSnapshotAt(respond: ScanResource.GetAcsSnapshotAtResponse.type)(
      body: AcsRequest
  )(extracted: TraceContext): Future[ScanResource.GetAcsSnapshotAtResponse] = {
    implicit val tc: TraceContext = extracted

    def toResponse(result: QueryAcsSnapshotResult) =
      ScanResource.GetAcsSnapshotAtResponseOK(
        toAcsV0Response(body.migrationId, result)
      )

    withSpan(s"$workflowId.getAcsSnapshotAt") { _ => _ =>
      acsSnapshotQuery(body, toResponse).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetAcsSnapshotAtResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  override def getAcsSnapshotAtV1(respond: ScanResource.GetAcsSnapshotAtV1Response.type)(
      body: AcsRequest
  )(extracted: TraceContext): Future[ScanResource.GetAcsSnapshotAtV1Response] = {
    implicit val tc: TraceContext = extracted

    def toResponse(result: QueryAcsSnapshotResult) = {
      ScanResource.GetAcsSnapshotAtV1ResponseOK(
        toAcsV1Response(body.migrationId, result)
      )
    }

    withSpan(s"$workflowId.getAcsSnapshotAtV1") { _ => _ =>
      acsSnapshotQuery(body, toResponse).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetAcsSnapshotAtV1ResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  private def holdingStateQuery[T](
      request: HoldingsStateRequest,
      toResponse: QueryAcsSnapshotResult => T,
  )(implicit
      tc: TraceContext
  ): Future[Either[String, T]] = {
    val HoldingsStateRequest(
      migrationId,
      recordTime,
      recordTimeMatch,
      after,
      pageSize,
      ownerPartyIds,
    ) = request

    def exactQuery(recordTimeTs: CantonTimestamp) = snapshotStore
      .getHoldingsState(
        migrationId,
        recordTimeTs,
        after,
        PageLimit.tryCreate(pageSize),
        nonEmptyOrFail("ownerPartyIds", ownerPartyIds).map(PartyId.tryFromProtoPrimitive),
      )

    queryWithOptionalAtOrBefore(
      migrationId,
      recordTime,
      recordTimeMatch.contains(HoldingsStateRequest.RecordTimeMatch.AtOrBefore),
      exactQuery,
      toResponse,
    )
  }

  override def getHoldingsStateAt(respond: ScanResource.GetHoldingsStateAtResponse.type)(
      body: HoldingsStateRequest
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsStateAtResponse] = {
    implicit val tc: TraceContext = extracted
    def toResponse(result: QueryAcsSnapshotResult) =
      ScanResource.GetHoldingsStateAtResponseOK(toAcsV0Response(body.migrationId, result))

    withSpan(s"$workflowId.getHoldingsStateAt") { _ => _ =>
      holdingStateQuery(body, toResponse).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsStateAtResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  override def getHoldingsStateAtV1(respond: ScanResource.GetHoldingsStateAtV1Response.type)(
      body: HoldingsStateRequest
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsStateAtV1Response] = {
    implicit val tc: TraceContext = extracted
    def toResponse(result: QueryAcsSnapshotResult) =
      ScanResource.GetHoldingsStateAtV1ResponseOK(toAcsV1Response(body.migrationId, result))

    withSpan(s"$workflowId.getHoldingsStateAtV1") { _ => _ =>
      holdingStateQuery(body, toResponse).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsStateAtV1ResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  override def getHoldingsSummaryAt(respond: ScanResource.GetHoldingsSummaryAtResponse.type)(
      body: HoldingsSummaryRequest
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsSummaryAtResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getHoldingsSummaryAt") { _ => _ =>
      val HoldingsSummaryRequest(
        migrationId,
        recordTime,
        recordTimeMatch,
        partyIds,
        asOfRound,
      ) = body

      def exactQuery(recordTimeTs: CantonTimestamp) = for {
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
            recordTimeTs,
            nonEmptyOrFail("partyIds", partyIds).map(PartyId.tryFromProtoPrimitive),
            round,
          )
      } yield result

      def toResponse(result: AcsSnapshotStore.HoldingsSummaryResult) =
        ScanResource.GetHoldingsSummaryAtResponse.OK(
          definitions.HoldingsSummaryResponse(
            Codec.encode(result.recordTime),
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
                accumulatedHoldingFeesLocked = Codec.encode(holdings.accumulatedHoldingFeesLocked),
                accumulatedHoldingFeesTotal = Codec.encode(holdings.accumulatedHoldingFeesTotal),
                totalAvailableCoin = Codec.encode(holdings.totalAvailableCoin),
              )
            }.toVector,
          )
        )

      queryWithOptionalAtOrBefore(
        migrationId,
        recordTime,
        recordTimeMatch.contains(HoldingsSummaryRequest.RecordTimeMatch.AtOrBefore),
        exactQuery,
        toResponse,
      ).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsSummaryAtResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  private def nonEmptyOrFail[A](fieldName: String, vec: Vector[A]): NonEmptyVector[A] = {
    NonEmptyVector
      .fromVector(vec)
      .getOrElse(
        throw io.grpc.Status.INVALID_ARGUMENT
          .withDescription(
            s"Expected '$fieldName' to contain at least one item, but contained none."
          )
          .asRuntimeException()
      )
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

  def getUpdateById(
      updateId: String,
      encoding: DamlValueEncoding,
      consistentResponses: Boolean,
      extracted: TraceContext,
  ): Future[Either[ErrorResponse, UpdateHistoryItem]] = {
    implicit val tc = extracted
    for {
      tx <- updateHistory.getUpdate(updateId)
    } yield {
      tx.fold[Either[ErrorResponse, UpdateHistoryItem]](
        Left(
          ErrorResponse(s"Transaction with id $updateId not found")
        )
      )(txWithMigration =>
        Right(
          ScanHttpEncodings.encodeUpdate(
            txWithMigration,
            encoding = encoding,
            version = if (consistentResponses) ScanHttpEncodings.V1 else ScanHttpEncodings.V0,
            hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
            externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
          )
        )
      )
    }
  }

  override def getUpdateById(
      respond: ScanResource.GetUpdateByIdResponse.type
  )(updateId: String, lossless: Option[Boolean])(
      extracted: TraceContext
  ): Future[ScanResource.GetUpdateByIdResponse] = {
    implicit val tc = extracted
    // in openAPI the operationID for /v0/updates/{update_id} is `getUpdateById`, logging as `getUpdateByIdV0` for clarity
    withSpan(s"$workflowId.getUpdateByIdV0") { _ => _ =>
      val encoding = if (lossless.getOrElse(false)) {
        DamlValueEncoding.ProtobufJson
      } else {
        DamlValueEncoding.CompactJson
      }
      getUpdateById(
        updateId = updateId,
        encoding = encoding,
        consistentResponses = false,
        extracted,
      )
        .map {
          case Left(error) =>
            ScanResource.GetUpdateByIdResponse.NotFound(error)
          case Right(update) =>
            ScanResource.GetUpdateByIdResponse.OK(update)
        }
    }
  }

  override def getUpdateByIdV1(
      respond: ScanResource.GetUpdateByIdV1Response.type
  )(updateId: String, damlValueEncoding: Option[DamlValueEncoding])(
      extracted: TraceContext
  ): Future[ScanResource.GetUpdateByIdV1Response] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getUpdateByIdV1") { _ => _ =>
      getUpdateById(
        updateId = updateId,
        encoding = damlValueEncoding.getOrElse(DamlValueEncoding.members.CompactJson),
        consistentResponses = true,
        extracted,
      )
        .map {
          case Left(error) =>
            ScanResource.GetUpdateByIdV1Response.NotFound(error)
          case Right(update) =>
            ScanResource.GetUpdateByIdV1Response.OK(update)
        }
    }
  }

  override def getUpdateByIdV2(respond: ScanResource.GetUpdateByIdV2Response.type)(
      updateId: String,
      damlValueEncoding: Option[DamlValueEncoding],
  )(extracted: TraceContext): Future[ScanResource.GetUpdateByIdV2Response] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getUpdateByIdV2") { _ => _ =>
      getUpdateById(
        updateId = updateId,
        encoding = damlValueEncoding.getOrElse(DamlValueEncoding.members.CompactJson),
        consistentResponses = true,
        extracted,
      )
        .map {
          case Left(error) =>
            ScanResource.GetUpdateByIdV2Response.NotFound(error)
          case Right(update) =>
            ScanResource.GetUpdateByIdV2Response.OK(updateV1ToUpdateV2(update))
        }
    }
  }

  def getUpdateByHash(
      hash: String,
      encoding: DamlValueEncoding,
      extracted: TraceContext,
  ): Future[Either[ErrorResponse, UpdateHistoryItem]] = {
    implicit val tc = extracted
    for {
      tx <- updateHistory.getUpdateByHash(hash)
    } yield {
      tx.fold[Either[ErrorResponse, UpdateHistoryItem]](
        Left(
          ErrorResponse(s"Transaction with hash $hash not found")
        )
      )(txWithMigration =>
        Right(
          ScanHttpEncodings.encodeUpdate(
            txWithMigration,
            encoding = encoding,
            version = ScanHttpEncodings.V1,
            hashInclusionPolicy = ExternalHashInclusionPolicy.AlwaysInclude,
            None,
          )
        )
      )
    }
  }

  override def getUpdateByHash(respond: ScanResource.GetUpdateByHashResponse.type)(
      hash: String,
      damlValueEncoding: Option[DamlValueEncoding],
  )(extracted: TraceContext): Future[ScanResource.GetUpdateByHashResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getUpdateByHash") { _ => _ =>
      getUpdateByHash(
        hash = hash,
        encoding = damlValueEncoding.getOrElse(DamlValueEncoding.members.CompactJson),
        extracted,
      )
        .map {
          case Left(error) =>
            ScanResource.GetUpdateByHashResponse.NotFound(error)
          case Right(update) =>
            ScanResource.GetUpdateByHashResponse.OK(toUpdateV2WithHash(update))
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
          entries = roundTotals.map(encodeRoundTotals)
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
          entries = roundPartyTotals.map(encodeRoundPartyTotals)
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

  override def listAmuletPriceVotes(
      respond: ScanResource.ListAmuletPriceVotesResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListAmuletPriceVotesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listAmuletPriceVotes") { _ => _ =>
      for {
        amuletPriceVotes <- votesStore.listAmuletPriceVotes()
      } yield ScanResource.ListAmuletPriceVotesResponse.OK(
        definitions.ListAmuletPriceVotesResponse(
          amuletPriceVotes.map(_.toHttp).toVector
        )
      )
    }
  }

  override def listDsoRulesVoteRequests(
      respond: ScanResource.ListDsoRulesVoteRequestsResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListDsoRulesVoteRequestsResponse] = {
    this
      .listDsoRulesVoteRequests(extracted, ec)
      .map(ScanResource.ListDsoRulesVoteRequestsResponse.OK)
  }

  override def listVoteRequestResults(
      respond: ScanResource.ListVoteRequestResultsResponse.type
  )(
      body: ListVoteResultsRequest
  )(extracted: TraceContext): Future[ScanResource.ListVoteRequestResultsResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.listDsoRulesVoteResults") { _ => _ =>
      val limit = PageLimit.tryCreate(body.limit.intValue)
      val after = body.pageToken.map(_.longValue)
      for {
        page <- votesStore.listVoteRequestResults(
          body.actionName,
          body.accepted,
          body.requester,
          body.effectiveFrom,
          body.effectiveTo,
          limit,
          after,
        )
      } yield {
        ScanResource.ListVoteRequestResultsResponse.OK(
          definitions.ListDsoRulesVoteResultsResponse(
            page.resultsInPage
              .map(voteResult => {
                io.circe.parser
                  .parse(
                    ApiCodecCompressed
                      .apiValueToJsValue(Contract.javaValueToLfValue(voteResult.toValue))
                      .compactPrint
                  )
                  .valueOr(err =>
                    ErrorUtil.invalidState(s"Failed to convert from spray to circe: $err")
                  )
              })
              .toVector,
            page.nextPageToken.map(BigInt(_)),
          )
        )
      }
    }
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

  override def getMigrationInfo(respond: ScanResource.GetMigrationInfoResponse.type)(
      body: definitions.GetMigrationInfoRequest
  )(extracted: TraceContext): Future[ScanResource.GetMigrationInfoResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getMigrationInfo") { _ => _ =>
      val sourceHistory = updateHistory.sourceHistory
      for {
        infoO <- sourceHistory.migrationInfo(body.migrationId)
      } yield infoO match {
        case Some(info) =>
          ScanResource.GetMigrationInfoResponse.OK(
            definitions.GetMigrationInfoResponse(
              previousMigrationId = info.previousMigrationId,
              complete = info.complete,
              importUpdatesComplete = Some(info.importUpdatesComplete),
              lastImportUpdateId = info.lastImportUpdateId,
              recordTimeRange = info.recordTimeRange.iterator.map { case (synchronizerId, range) =>
                definitions.RecordTimeRange(
                  synchronizerId = synchronizerId.toProtoPrimitive,
                  min = java.time.OffsetDateTime.ofInstant(range.min.toInstant, ZoneOffset.UTC),
                  max = java.time.OffsetDateTime.ofInstant(range.max.toInstant, ZoneOffset.UTC),
                )
              }.toVector,
            )
          )
        case None =>
          ScanResource.GetMigrationInfoResponse.NotFound(
            definitions.ErrorResponse(s"No data for migration ${body.migrationId}")
          )
      }
    }
  }

  override def getUpdatesBefore(respond: ScanResource.GetUpdatesBeforeResponse.type)(
      body: definitions.GetUpdatesBeforeRequest
  )(extracted: TraceContext): Future[ScanResource.GetUpdatesBeforeResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getUpdatesBefore") { _ => _ =>
      updateHistory
        .getUpdatesBefore(
          migrationId = body.migrationId,
          synchronizerId = SynchronizerId.tryFromString(body.synchronizerId),
          beforeRecordTime = CantonTimestamp.assertFromInstant(body.before.toInstant),
          atOrAfterRecordTime =
            body.atOrAfter.map(x => CantonTimestamp.assertFromInstant(x.toInstant)),
          limit = PageLimit.tryCreate(body.count),
        )
        .map { txs =>
          definitions.GetUpdatesBeforeResponse(
            txs
              .map(
                ScanHttpEncodings.encodeUpdate(
                  _,
                  encoding = definitions.DamlValueEncoding.members.ProtobufJson,
                  version = ScanHttpEncodings.V1,
                  hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
                  externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
                )
              )
              .toVector
          )
        }
    }
  }

  override def getImportUpdates(respond: ScanResource.GetImportUpdatesResponse.type)(
      body: definitions.GetImportUpdatesRequest
  )(extracted: TraceContext): Future[ScanResource.GetImportUpdatesResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getImportUpdates") { _ => _ =>
      updateHistory
        .getImportUpdates(
          migrationId = body.migrationId,
          afterUpdateId = body.afterUpdateId,
          limit = PageLimit.tryCreate(body.limit),
        )
        .map { txs =>
          definitions.GetImportUpdatesResponse(
            txs
              .map(
                ScanHttpEncodings.encodeUpdate(
                  _,
                  encoding = definitions.DamlValueEncoding.members.ProtobufJson,
                  version = ScanHttpEncodings.V1,
                  hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
                  externalTransactionHashThresholdTime = externalTransactionHashThresholdTime,
                )
              )
              .toVector
          )
        }
    }
  }

  override def getMemberTrafficStatus(
      respond: ScanResource.GetMemberTrafficStatusResponse.type
  )(synchronizerId: String, memberId: String)(
      extracted: TraceContext
  ): Future[ScanResource.GetMemberTrafficStatusResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getMemberTrafficStatus") { _ => _ =>
      for {
        member <- Member.fromProtoPrimitive_(memberId) match {
          case Right(member) => Future.successful(member)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode member ID: $error")
            )
        }
        domain <- SynchronizerId.fromString(synchronizerId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode domain ID: $error")
            )
        }
        actual <- synchronizerNodeService
          .sequencerAdminConnection()
          .flatMap(_.getSequencerTrafficControlState(member))
        actualConsumed = actual.extraTrafficConsumed.value
        actualLimit = actual.extraTrafficLimit.value
        targetTotalPurchased <- store.getTotalPurchasedMemberTraffic(member, domain)
      } yield {
        definitions.GetMemberTrafficStatusResponse(
          definitions.MemberTrafficStatus(
            definitions.ActualMemberTrafficState(actualConsumed, actualLimit),
            definitions.TargetMemberTrafficState(targetTotalPurchased),
          )
        )
      }
    }
  }

  override def getPartyToParticipant(respond: ScanResource.GetPartyToParticipantResponse.type)(
      synchronizerId: String,
      partyId: String,
  )(extracted: TraceContext): Future[ScanResource.GetPartyToParticipantResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getPartyToParticipant") { _ => _ =>
      for {
        domain <- SynchronizerId.fromString(synchronizerId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode domain ID: $error")
            )
        }
        party <- PartyId.fromProtoPrimitive(partyId, "partyId") match {
          case Right(party) => Future.successful(party)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode party ID: $error")
            )
        }
        response <- synchronizerNodeService
          .sequencerAdminConnection()
          .flatMap(
            _.getPartyToParticipant(
              domain,
              party,
              topologyTransactionType = AuthorizedState,
              topologySnapshot =
                TopologySnapshot.Effective, // Follow the usual Canton APIs to return effective and not sequenced state.
            )
          )
        participantId <- response.mapping.participantIds match {
          case Seq() =>
            Future.failed(
              HttpErrorHandler.notFound(
                s"No participant id found hosting party: $party"
              )
            )
          case Seq(participantId) => Future.successful(participantId)
          case _ =>
            Future.failed(
              HttpErrorHandler.internalServerError(
                s"Party ${party} is hosted on multiple participants, which is not currently supported"
              )
            )
        }
      } yield definitions.GetPartyToParticipantResponse(participantId.toProtoPrimitive)
    }
  }

  override def getPartyToParticipantV1(
      respond: ScanResource.GetPartyToParticipantV1Response.type
  )(
      synchronizerId: String,
      partyId: String,
  )(extracted: TraceContext): Future[ScanResource.GetPartyToParticipantV1Response] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getPartyToParticipantV1") { _ => _ =>
      for {
        domain <- SynchronizerId.fromString(synchronizerId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode synchronizer ID: $error")
            )
        }
        party <- PartyId.fromProtoPrimitive(partyId, "partyId") match {
          case Right(party) => Future.successful(party)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode party ID: $error")
            )
        }
        response <- synchronizerNodeService
          .sequencerAdminConnection()
          .flatMap(
            _.getPartyToParticipant(
              domain,
              party,
              topologyTransactionType = AuthorizedState,
              topologySnapshot =
                TopologySnapshot.Effective, // Follow the usual Canton APIs to return effective and not sequenced state.
            )
          )
        _ <-
          if (response.mapping.partyId == party) Future.unit
          else
            Future.failed(
              HttpErrorHandler.notFound(s"Party not found: $party")
            )
      } yield definitions.GetPartyToParticipantResponseV1(
        response.mapping.participantIds.map(_.toProtoPrimitive).toVector
      )
    }
  }

  override def getValidatorFaucetsByValidator(
      respond: ScanResource.GetValidatorFaucetsByValidatorResponse.type
  )(validators: Vector[String])(
      extracted: TraceContext
  ): Future[ScanResource.GetValidatorFaucetsByValidatorResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getValidatorFaucetsByValidator") { _ => _ =>
      store
        .getValidatorLicenseByValidator(validators.map(v => PartyId.tryFromProtoPrimitive(v)))
        .map(licenses =>
          ScanResource.GetValidatorFaucetsByValidatorResponse.OK(
            definitions
              .GetValidatorFaucetsByValidatorResponse(
                FaucetProcessor.process(licenses)
              )
          )
        )
    }
  }

  override def getBackfillingStatus(
      respond: ScanResource.GetBackfillingStatusResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetBackfillingStatusResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getBackfillingStatus") { _ => _ =>
      for {
        updateHistoryStatus <- updateHistory.getBackfillingState()
        txLogStatus <- store.multiDomainAcsStore.getTxLogBackfillingState()
        updateHistoryComplete = updateHistoryStatus == BackfillingState.Complete
        txLogComplete = txLogStatus == TxLogBackfillingState.Complete
      } yield ScanResource.GetBackfillingStatusResponse.OK(
        definitions.GetBackfillingStatusResponse(
          complete = updateHistoryComplete && txLogComplete
        )
      )
    }
  }

  override def featureSupport(respond: ScanResource.FeatureSupportResponse.type)()(
      extracted: TraceContext
  ): Future[ScanResource.FeatureSupportResponse] =
    readFeatureSupport(store.key.dsoParty)(ec, extracted, tracer)
      .map(ScanResource.FeatureSupportResponseOK(_))

  private def parseTimestamp(str: String): CantonTimestamp = {
    val timestamp = for {
      instant <- Try(Instant.parse(str)).toEither.left.map(_.getMessage)
      ts <- Timestamp.fromInstant(instant)
    } yield CantonTimestamp(ts)
    timestamp.fold(
      error => throw new IllegalArgumentException(s"Invalid timestamp: $error"),
      identity,
    )
  }

  override def listSvBftSequencers(respond: ScanResource.ListSvBftSequencersResponse.type)()(
      extracted: TraceContext
  ): Future[ScanResource.ListSvBftSequencersResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listSvBftSequencers") { _ => _ =>
      MonadUtil
        .sequentialTraverse(bftSequencers.zipWithIndex) {
          case ((sequencerAdminConnection, bftSequencer), idx) =>
            initializedBftSequencersCache.get(idx) match {
              case Some(cached) =>
                Future.successful(Some(cached))
              case None if uninitializedBftSequencersCooldown.getIfPresent(idx).isDefined =>
                Future.successful(None)
              case None =>
                sequencerAdminConnection.getStatus
                  .flatMap { status =>
                    if (status.isInitialized) {
                      val sequencerStatus = status.trySuccess
                      val psid = sequencerStatus.synchronizerId
                      sequencerAdminConnection.getSequencerId.map { id =>
                        val entry = definitions.SynchronizerBftSequencer(
                          psid.serial.unwrap.toLong,
                          id.toProtoPrimitive,
                          bftSequencer.p2pUrl,
                        )
                        initializedBftSequencersCache.put(idx, entry).discard
                        Some(entry)
                      }
                    } else {
                      logger.info(
                        s"Skipping BFT sequencer with p2p url ${bftSequencer.p2pUrl} as it is not initialized"
                      )
                      uninitializedBftSequencersCooldown.put(idx, ()).discard
                      Future.successful(None)
                    }
                  }
                  .recover { case ex =>
                    logger.warn(
                      s"Failed to get status of BFT sequencer with p2p url ${bftSequencer.p2pUrl}",
                      ex,
                    )
                    uninitializedBftSequencersCooldown.put(idx, ()).discard
                    None
                  }
            }
        }
        .map { results =>
          ScanResource.ListSvBftSequencersResponse.OK(
            definitions.ListSvBftSequencersResponse(results.flatten.toVector)
          )
        }
    }
  }

  override def listUnclaimedDevelopmentFundCoupons(
      respond: ScanResource.ListUnclaimedDevelopmentFundCouponsResponse.type
  )()(extracted: TraceContext): Future[ScanResource.ListUnclaimedDevelopmentFundCouponsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listUnclaimedDevelopmentFundCoupons") { _ => _ =>
      for {
        coupons <- store.multiDomainAcsStore.listContracts(
          amulet.UnclaimedDevelopmentFundCoupon.COMPANION
        )
      } yield {
        definitions.ListUnclaimedDevelopmentFundCouponsResponse(
          coupons.map(_.toHttp).toVector
        )
      }
    }
  }

  private def getBulkStorage(): Option[(AcsSnapshotBulkStorage, UpdateHistoryBulkStorage, Uri)] = {
    for {
      acs <- bulkStorage.acsSnapshotBulkStorage
      update <- bulkStorage.updateHistoryBulkStorage
      publicUrl <- publicUrlO
    } yield {
      (acs, update, publicUrl)
    }
  }

  private def encodeBulkStorageObjects(objects: Seq[ObjectKeyAndChecksum], publicUrl: Uri) =
    objects.map { case ObjectKeyAndChecksum(key, digest) =>
      val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
      definitions.BulkStorageObjectRef(
        s"$publicUrl/api/scan/v0/history/bulk/download/$encodedKey",
        digest,
      )
    }.toVector

  override def listBulkAcsSnapshotObjects(
      respond: ScanResource.ListBulkAcsSnapshotObjectsResponse.type
  )(
      atOrBeforeRecordTime: OffsetDateTime
  )(extracted: TraceContext): Future[ScanResource.ListBulkAcsSnapshotObjectsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listBulkAcsSnapshotObjects") { _ => _ =>
      getBulkStorage() match {
        case None =>
          Future.failed[ScanResource.ListBulkAcsSnapshotObjectsResponse](
            Status.UNIMPLEMENTED
              .withDescription("Bulk storage or public URL is not configured")
              .asRuntimeException()
          )
        case Some((acsSnapshotBulkStorage, _, publicUrl)) =>
          val recordTimeTs = Codec.tryDecode(Codec.OffsetDateTime)(atOrBeforeRecordTime)
          acsSnapshotBulkStorage.getAcsSnapshotAtOrBefore(recordTimeTs).map {
            case AcsSnapshotObjects(ts, objects) =>
              ScanResource.ListBulkAcsSnapshotObjectsResponse.OK(
                definitions.ListBulkAcsSnapshotObjectsResponse(
                  Codec.encode(ts),
                  encodeBulkStorageObjects(objects, publicUrl),
                )
              )
          }

      }
    }
  }

  override def listBulkUpdateHistoryObjects(
      respond: ScanResource.ListBulkUpdateHistoryObjectsResponse.type
  )(body: ListBulkUpdateHistoryObjectsRequest)(
      extracted: TraceContext
  ): Future[ScanResource.ListBulkUpdateHistoryObjectsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listBulkUpdateHistoryObjects") { _ => _ =>
      getBulkStorage() match {
        case None =>
          Future.failed[ScanResource.ListBulkUpdateHistoryObjectsResponse](
            Status.UNIMPLEMENTED
              .withDescription("Bulk storage or public URL is not configured")
              .asRuntimeException()
          )
        case Some((_, updateHistoryBulkStorage, publicUrl)) =>
          val afterTs = Codec.tryDecode(Codec.OffsetDateTime)(body.startRecordTime)
          val upToTs = Codec.tryDecode(Codec.OffsetDateTime)(body.endRecordTime)
          updateHistoryBulkStorage
            .getUpdatesBetweenDates(
              afterTs,
              upToTs,
              PageLimit.tryCreate(body.pageSize),
              body.nextPageToken,
            )
            .map { case UpdateHistoryObjectsResponse(objects, nextPageToken) =>
              ScanResource.ListBulkUpdateHistoryObjectsResponse.OK(
                definitions.ListBulkUpdateHistoryObjectsResponse(
                  encodeBulkStorageObjects(objects, publicUrl),
                  nextPageToken,
                )
              )
            }
      }
    }
  }

  def getRollForwardLsu(respond: ScanResource.GetRollForwardLsuResponse.type)()(
      extracted: TraceContext
  ): Future[ScanResource.GetRollForwardLsuResponse] = {
    implicit val tc = extracted
    lsuRollForwardConfigO match {
      case None =>
        Future.successful(
          ScanResource.GetRollForwardLsuResponse.OK(definitions.GetRollForwardLsuResponse())
        )
      case Some(rollForward) =>
        val legacy = synchronizerNodeService.nodes.legacy.getOrElse(
          throw Status.INTERNAL
            .withDescription(s"Roll forward LSU config set but no legacy synchronizer configured")
            .asRuntimeException
        )
        for {
          legacySynchronizerId <- legacy.sequencerAdminConnection.getPhysicalSynchronizerId()
          currentSynchronizerId <- synchronizerNodeService.nodes.current.sequencerAdminConnection
            .getPhysicalSynchronizerId()
          upgradeTime <- rollForward.upgradeTime match {
            case Some(t) => Future.successful(t)
            case None =>
              for {
                announcements <- legacy.sequencerAdminConnection.listLsuAnnouncements(
                  legacySynchronizerId.logical
                )
              } yield {
                announcements match {
                  case Seq(announcement) => announcement.mapping.upgradeTime
                  case _ =>
                    throw Status.INTERNAL
                      .withDescription(
                        s"Expected exactly one LSU annoucement on legacy synchronizer but got $announcements"
                      )
                      .asRuntimeException
                }
              }
          }
        } yield ScanResource.GetRollForwardLsuResponse.OK(
          definitions.GetRollForwardLsuResponse(
            Some(
              definitions.RollForwardLsu(
                upgradeTime = upgradeTime.toInstant.atOffset(java.time.ZoneOffset.UTC),
                currentPhysicalSynchronizerId = legacySynchronizerId.toProtoPrimitive,
                successorPhysicalSynchronizerId = currentSynchronizerId.toProtoPrimitive,
              )
            )
          )
        )
    }
  }

  def getRewardAccountingEarliestAvailableRound(
      respond: ScanResource.GetRewardAccountingEarliestAvailableRoundResponse.type
  )()(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingEarliestAvailableRoundResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRewardAccountingEarliestAvailableRound") { _ => _ =>
      appActivityStoreO match {
        case Some(appActivityStore) =>
          appActivityStore.earliestRoundWithCompleteAppActivity().map {
            case Some(round) =>
              ScanResource.GetRewardAccountingEarliestAvailableRoundResponse.OK(
                definitions.GetRewardAccountingEarliestAvailableRoundResponse(round)
              )
            case None =>
              ScanResource.GetRewardAccountingEarliestAvailableRoundResponse.NotFound(
                ErrorResponse("No reward accounting data available yet")
              )
          }
        case None =>
          Future.successful(
            ScanResource.GetRewardAccountingEarliestAvailableRoundResponse.NotFound(
              ErrorResponse("Reward accounting is not enabled")
            )
          )
      }
    }
  }

  def getRewardAccountingActivityTotals(
      respond: ScanResource.GetRewardAccountingActivityTotalsResponse.type
  )(roundNumber: Long)(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingActivityTotalsResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRewardAccountingActivityTotals") { _ => _ =>
      appRewardsStoreO match {
        case None =>
          Future.successful(
            ScanResource.GetRewardAccountingActivityTotalsResponse.NotFound(
              ErrorResponse("Reward accounting is not enabled on this node")
            )
          )
        case Some(appRewardsStore) =>
          appRewardsStore.getAppActivityRoundTotalByRound(roundNumber).map {
            case None =>
              ScanResource.GetRewardAccountingActivityTotalsResponse.NotFound(
                ErrorResponse(
                  s"Activity totals not (yet) computed for round $roundNumber"
                )
              )
            case Some(roundTotal) =>
              ScanResource.GetRewardAccountingActivityTotalsResponse.OK(
                definitions.GetRewardAccountingActivityTotalsResponse(
                  roundNumber = roundTotal.roundNumber,
                  totalAppActivityWeight = roundTotal.totalRoundAppActivityWeight,
                  activePartiesCount = roundTotal.activeAppProviderPartiesCount,
                )
              )
          }
      }
    }
  }
}

object HttpScanHandler {
  // We expect a handful at most but want to somewhat guard against attacks
  // so we just hardcode a limit of 100.
  private val MAX_TRANSFER_COMMAND_CONTRACTS: Int = 100

  // for DsoSequencers that use the serial instead of the migration we set -1 as the migration id
  // we can't simply make it non required as it's part of the public API and it would break clients
  val NoMigrationIdSet = -1L

  def encodeRoundTotals(roundTotal: RoundTotals): definitions.RoundTotals = {
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

  def encodeRoundPartyTotals(roundPartyTotal: RoundPartyTotals): definitions.RoundPartyTotals = {
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
}
