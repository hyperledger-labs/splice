// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.{NonEmptyList, NonEmptyVector, OptionT}
import cats.implicits.catsSyntaxOptionId
import cats.syntax.either.*
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.data.CantonTimestampExtensions.*
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.data.ActiveContract
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.store.TimeQuery
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
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  TopologySnapshot,
  TopologyTransactionType,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.http.{
  HttpFeatureSupportHandler,
  HttpValidatorLicensesHandler,
  HttpVotesHandler,
  UrlValidator,
}
import org.lfdecentralizedtrust.splice.http.HttpRequestLimits.maxSizeOrFail
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scan as v0}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AcsRequestV2,
  BatchListVotesByVoteRequestsRequest,
  CountVoteResultsRequest,
  DamlValueEncoding,
  ErrorResponse,
  EventHistoryRequest,
  GetBulkObjectChecksumsRequest,
  HoldingsStateRequestV2,
  HoldingsSummaryRequest,
  HoldingsSummaryRequestV1,
  ListBulkUpdateHistoryObjectsRequest,
  ListVoteResultsRequest,
  MaybeCachedContractWithState,
  PreviousSvRewardWeightRequest,
  PreviousSvRewardWeightResponse,
  UpdateHistoryItem,
  UpdateHistoryItemV2WithHash,
  UpdateHistoryRequestV2,
  UpdateHistoryTransactionV2WithHash,
}
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.ScanSynchronizerNode
import org.lfdecentralizedtrust.splice.scan.admin.http.ScanHttpEncodings.updateV1ToUpdateV2
import org.lfdecentralizedtrust.splice.scan.config.{
  CantonBftPeerConfig,
  ScanRollForwardLsuConfig,
  ScanStorageConfig,
}
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics
import org.lfdecentralizedtrust.splice.scan.metrics.ScanHttpApiMetrics.{
  AsOfRound,
  Presence,
  SnapshotQueryLabels,
}
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  AppActivityStore,
  ScanEventStore,
  ScanStore,
  TxLogEntry,
}
import org.lfdecentralizedtrust.splice.scan.store.AppActivityStore.RoundIngestionStatus
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  BulkObjectKind,
  BulkObjectsAvailability,
  BulkStorageReader,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.{
  IncrementalAcsSnapshotTable,
  QueryAcsSnapshotResult,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.AcsSnapshotBulkStorage.AcsSnapshotObjects
import org.lfdecentralizedtrust.splice.scan.store.bulk.UpdateHistoryBulkStorage.UpdateHistoryObjectsResponse
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.TxLogBackfillingState
import org.lfdecentralizedtrust.splice.store.{
  ActiveVotesStore,
  AppStore,
  AppStoreWithIngestion,
  HistoricalVotesStore,
  PageLimit,
  TimestampWithMigrationId,
  UpdateHistory,
  VoteResultsFilters,
}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState

import scala.collection.immutable.SortedMap
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanAppRewardsStore
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  Contract,
  ContractWithState,
  DsoInfo,
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
    appRewardsStore: DbScanAppRewardsStore,
    appActivityStore: AppActivityStore,
    snapshotStore: AcsSnapshotStore,
    eventStore: ScanEventStore,
    historicalVotesStore: HistoricalVotesStore,
    bulkStorage: Option[BulkStorageReader],
    scanApiMetrics: ScanHttpApiMetrics,
    dsoAnsResolver: DsoAnsResolver,
    miningRoundsCacheTimeToLiveOverride: Option[NonNegativeFiniteDuration],
    enableForcedAcsSnapshots: Boolean,
    perAcsSnapshotTablesEnabled: Boolean,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
    protected val packageVersionSupport: PackageVersionSupport,
    bftSequencers: Seq[(SequencerAdminConnection, CantonBftPeerConfig)],
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
  override protected val activeVotesStore: ActiveVotesStore = store
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
      lookupDsoInfo().map(_.toHttp)
    }
  }

  def getDsoInfoV1(
      respond: v0.ScanResource.GetDsoInfoV1Response.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetDsoInfoV1Response] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getDsoInfoV1") { _ => _ =>
      lookupDsoInfo().map(_.toV1.toHttp)
    }
  }

  def getSvInfo(
      respond: v0.ScanResource.GetSvInfoResponse.type
  )()(extracted: TraceContext): Future[v0.ScanResource.GetSvInfoResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getSvInfo") { _ => _ =>
      Future.successful(definitions.GetSvInfoResponse(svUserName, svParty.toProtoPrimitive))
    }
  }

  private def lookupDsoInfo()(implicit tc: TraceContext): Future[DsoInfo] =
    for {
      latestOpenMiningRound <- store.getLatestActiveOpenMiningRound()
      amuletRules <- store.getAmuletRulesWithState()
      rulesAndStates <- store.getDsoRulesWithStateWithSvNodeStates()
      dsoRules = rulesAndStates.dsoRules
    } yield DsoInfo(
      svUser = svUserName,
      svParty = svParty,
      dsoParty = store.key.dsoParty,
      votingThreshold = Thresholds.requiredNumVotes(dsoRules),
      latestMiningRound = latestOpenMiningRound.toContractWithState,
      amuletRules = amuletRules,
      dsoRules = dsoRules,
      svNodeStates = rulesAndStates.svNodeStates,
      initialRound = Some(initialRound),
    )

  def getOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetOpenAndIssuingMiningRoundsRequest
  )(extracted: TraceContext): Future[v0.ScanResource.GetOpenAndIssuingMiningRoundsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getOpenAndIssuingMiningRounds") { _ => _ =>
      val cachedOpenMiningRoundContractIds = maxSizeOrFail(
        "cached_open_mining_round_contract_ids",
        body.cachedOpenMiningRoundContractIds,
      )
      val cachedIssuingRoundContractIds =
        maxSizeOrFail("cached_issuing_round_contract_ids", body.cachedIssuingRoundContractIds)
      for {
        issuingRounds <- store.multiDomainAcsStore
          .listContracts(IssuingMiningRound.COMPANION)
        openRounds <- store.multiDomainAcsStore
          .listContracts(OpenMiningRound.COMPANION)
        summarizingRounds <- store.multiDomainAcsStore
          .listContracts(SummarizingMiningRound.COMPANION)
        issuingRoundsCachedByClient = cachedIssuingRoundContractIds.toSet
        openRoundsCachedByClient = cachedOpenMiningRoundContractIds.toSet
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
          PartyId.tryFromProtoPrimitive(providerPartyId),
          store.defaultLimit,
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
          extractSequencersForSynchronizers(
            nodeState.svName,
            domainConfig,
          ).map(synchronizerId -> _)
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
        connectedDomains <- participantAdminConnection.listConnectedSynchronizers()
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
      TimestampWithMigrationId(
        afterRecordTime,
        after.afterMigrationId,
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
        updateHistory.domainMigrationId,
      )
      result <- eventO match {
        case None =>
          Future.successful(
            Left(definitions.ErrorResponse(s"Event with id $updateId not found"))
          )
        case Some((verdictWithViewsO, updateO)) =>
          val verdictRowIdO = verdictWithViewsO.map { case (v, _) => v.rowId }
          for {
            appActivityRecordO <- verdictRowIdO match {
              case Some(rowId) =>
                eventStore.getAppActivityRecords(Seq(rowId)).map(_.get(rowId))
              case None => Future.successful(None)
            }
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
            val trafficSummaryEncoded = verdictWithViewsO.flatMap { case (v, _) =>
              v.trafficSummaryO.map(ScanHttpEncodings.encodeTrafficSummary)
            }
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
      TimestampWithMigrationId(afterRecordTime, a.afterMigrationId)
    }

    confirmBackfillingIsCompleteThen(updateHistory) {
      for {
        events <- eventStore.getEvents(
          afterO = afterO,
          currentMigrationId = updateHistory.domainMigrationId,
          limit = PageLimit.tryCreate(pageSize, updateHistoryMaxPageSize),
        )
        verdictRowIds = events.flatMap { case (verdictWithViewsO, _) =>
          verdictWithViewsO.map { case (v, _) => v.rowId }
        }
        appActivityRecordMap <- eventStore.getAppActivityRecords(verdictRowIds)
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
        val trafficSummaryEncoded = verdictWithViewsO.flatMap { case (v, _) =>
          v.trafficSummaryO.map(ScanHttpEncodings.encodeTrafficSummary)
        }
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

  override def getLatestEventRecordTime(
      respond: ScanResource.GetLatestEventRecordTimeResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetLatestEventRecordTimeResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getLatestEventRecordTime") { _ => _ =>
      eventStore.getLatestEventRecordTime(updateHistory.domainMigrationId).map {
        case Some(timestamp) =>
          ScanResource.GetLatestEventRecordTimeResponse.OK(
            definitions.EventLatestRecordTimeResponse(Codec.encode(timestamp))
          )
        case None =>
          ScanResource.GetLatestEventRecordTimeResponse.NotFound(
            definitions.ErrorResponse("No events found")
          )
      }
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

  private def getSnapshotRecordTimeAtOrBefore(migrationId: Long, before: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[Option[CantonTimestamp]] =
    OptionT(
      snapshotStore
        .lookupSnapshotAtOrBefore(migrationId, before, onlyIndexed = true)
    ).map {
      _.snapshotRecordTime
    }.value

  override def getDateOfMostRecentSnapshotBefore(
      respond: ScanResource.GetDateOfMostRecentSnapshotBeforeResponse.type
  )(before: OffsetDateTime, migrationId: Long)(
      extracted: TraceContext
  ): Future[ScanResource.GetDateOfMostRecentSnapshotBeforeResponse] = {
    implicit val tc: TraceContext = extracted

    def notFound = ScanResource.GetDateOfMostRecentSnapshotBeforeResponseNotFound(
      definitions.ErrorResponse(s"No snapshots found before $before")
    )

    withSpan(s"$workflowId.getDateOfMostRecentSnapshotBefore") { _ => _ =>
      snapshotStore
        .lookupSnapshotAtOrBefore(
          migrationId,
          Codec.tryDecode(Codec.OffsetDateTime)(before),
          onlyIndexed = true,
        )
        .map {
          case None => notFound
          case Some(snapshot) =>
            ScanResource.GetDateOfMostRecentSnapshotBeforeResponseOK(
              definitions
                .AcsSnapshotTimestampResponse(
                  Codec.encode(snapshot.snapshotRecordTime)
                )
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
      def notFound = ScanResource.GetDateOfFirstSnapshotAfterResponseNotFound(
        definitions.ErrorResponse(s"No snapshots found after $after")
      )

      snapshotStore
        .lookupSnapshotAfter(migrationId, Codec.tryDecode(Codec.OffsetDateTime)(after))
        .map {
          case None => notFound
          case Some(snapshot) if !snapshot.indexesCreated => notFound
          case Some(snapshot) =>
            ScanResource.GetDateOfFirstSnapshotAfterResponseOK(
              definitions
                .AcsSnapshotTimestampResponse(
                  Codec.encode(snapshot.snapshotRecordTime)
                )
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
        val snapshotTable: IncrementalAcsSnapshotTable =
          if (perAcsSnapshotTablesEnabled) {
            AcsSnapshotStore.IncrementalAcsSnapshotTable.NextV2
          } else {
            AcsSnapshotStore.IncrementalAcsSnapshotTable.Next
          }
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
                snapshotTable,
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
      labels: SnapshotQueryLabels,
  )(implicit tc: TraceContext): Future[Either[String, T]] = {
    // Track use of optional filters.
    scanApiMetrics.snapshotQueryRequests.inc()(labels.context)
    val recordTimeTs = Codec.tryDecode(Codec.OffsetDateTime)(recordTime)
    if (recordTimeIsAtOrBefore) {
      val snapshotQueryResult = for {
        resolvedRecordTime <- OptionT(getSnapshotRecordTimeAtOrBefore(migrationId, recordTimeTs))
        _ = logSnapshotAccess(labels, resolvedRecordTime)
        snapshotQueryResult <- OptionT.liftF(exactQuery(resolvedRecordTime))
      } yield snapshotQueryResult
      snapshotQueryResult.fold[Either[String, T]](
        Left(s"No snapshots found before $recordTime")
      )(res => Right(toResponse(res)))
    } else {
      logSnapshotAccess(labels, recordTimeTs)
      exactQuery(recordTimeTs).map(res => Right(toResponse(res)))
    }
  }

  private def logSnapshotAccess(
      labels: SnapshotQueryLabels,
      servedRecordTime: CantonTimestamp,
  )(implicit tc: TraceContext): Unit = {
    val servedInstant = servedRecordTime.toInstant
    val age = java.time.Duration.between(servedInstant, clock.now.toInstant)
    val requestedSnapshotAge =
      if (age.compareTo(java.time.Duration.ofHours(3)) <= 0) "less_equal_3h"
      else if (age.compareTo(java.time.Duration.ofHours(24)) <= 0) "less_equal_24h"
      else if (age.compareTo(java.time.Duration.ofDays(7)) <= 0) "less_equal_7d"
      else "greater_than_7d"
    val slotStartHour = (servedInstant.atOffset(java.time.ZoneOffset.UTC).getHour / 3) * 3
    val snapshotSlot = f"$slotStartHour%02d:00"
    logger.debug(
      s"snapshot_access" +
        s" operation=${labels.operation}" +
        s" record_time_match=${if (labels.atOrBefore) "at_or_before" else "exact"}" +
        s" served_record_time=$servedRecordTime" +
        s" requested_snapshot_age=$requestedSnapshotAge" +
        s" snapshot_slot=$snapshotSlot"
    )
  }

  private def acsSnapshotQuery[T](
      operation: String,
      migrationId: Long,
      recordTime: java.time.OffsetDateTime,
      recordTimeIsAtOrBefore: Boolean,
      after: Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken],
      pageSize: Int,
      partyIds: Option[Vector[String]],
      templates: Option[Vector[String]],
      toResponse: QueryAcsSnapshotResult => T,
  )(implicit
      tc: TraceContext
  ): Future[Either[String, T]] = {
    val boundedPartyIds = maxSizeOrFail("party_ids", partyIds getOrElse Vector.empty)
    val boundedTemplates = maxSizeOrFail("templates", templates getOrElse Vector.empty)
    def exactQuery(recordTimeTs: CantonTimestamp) = snapshotStore
      .queryAcsSnapshot(
        migrationId,
        recordTimeTs,
        after,
        PageLimit.tryCreate(pageSize),
        boundedPartyIds
          .map(PartyId.tryFromProtoPrimitive),
        boundedTemplates
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
      recordTimeIsAtOrBefore,
      exactQuery,
      toResponse,
      SnapshotQueryLabels(
        operation = operation,
        partyFilter = Presence(boundedPartyIds.nonEmpty),
        templateFilter = Presence(boundedTemplates.nonEmpty),
        atOrBefore = recordTimeIsAtOrBefore,
        asOfRound = AsOfRound.NotApplicable,
      ),
    )

  }

  private def toAcsV2Response(migrationId: Long, result: QueryAcsSnapshotResult)(implicit
      tc: TraceContext
  ) =
    definitions.AcsResponseV2(
      Codec.encode(result.snapshotRecordTime),
      migrationId,
      result.createdEventsInPage
        .map(event =>
          CompactJsonScanHttpEncodings().javaToHttpActiveContract(
            event.eventId,
            event.recordTime,
            event.event,
          )
        ),
      result.afterToken.map(_.encodeToBase64),
    )

  override def getAcsSnapshotAtV2(respond: ScanResource.GetAcsSnapshotAtV2Response.type)(
      body: AcsRequestV2
  )(extracted: TraceContext): Future[ScanResource.GetAcsSnapshotAtV2Response] = {
    implicit val tc: TraceContext = extracted

    def toResponse(result: QueryAcsSnapshotResult) = {
      ScanResource.GetAcsSnapshotAtV2ResponseOK(
        toAcsV2Response(body.migrationId, result)
      )
    }
    val opId = "getAcsSnapshotAtV2"
    withSpan(s"$workflowId.$opId") { _ => _ =>
      acsSnapshotQuery(
        operation = opId,
        migrationId = body.migrationId,
        recordTime = body.recordTime,
        recordTimeIsAtOrBefore =
          body.recordTimeMatch.contains(AcsRequestV2.RecordTimeMatch.AtOrBefore),
        after =
          body.after.map(AcsSnapshotStore.QueryAcsSnapshotPaginationToken.tryDecodeFromBase64),
        pageSize = body.pageSize,
        partyIds = body.partyIds,
        templates = body.templates,
        toResponse = toResponse,
      ).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetAcsSnapshotAtV2ResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  private def holdingStateQuery[T](
      operation: String,
      migrationId: Long,
      recordTime: java.time.OffsetDateTime,
      recordTimeIsAtOrBefore: Boolean,
      after: Option[AcsSnapshotStore.QueryAcsSnapshotPaginationToken],
      pageSize: Int,
      ownerPartyIds: Vector[String],
      toResponse: QueryAcsSnapshotResult => T,
  )(implicit
      tc: TraceContext
  ): Future[Either[String, T]] = {
    val boundedOwnerPartyIds =
      nonEmptyOrFail("owner_party_ids", maxSizeOrFail("owner_party_ids", ownerPartyIds))
    def exactQuery(recordTimeTs: CantonTimestamp) = snapshotStore
      .getHoldingsState(
        migrationId,
        recordTimeTs,
        after,
        PageLimit.tryCreate(pageSize),
        boundedOwnerPartyIds.map(PartyId.tryFromProtoPrimitive),
      )

    queryWithOptionalAtOrBefore(
      migrationId,
      recordTime,
      recordTimeIsAtOrBefore,
      exactQuery,
      toResponse,
      SnapshotQueryLabels(
        operation = operation,
        // owner_party_ids is required on holdings/state.
        partyFilter = Presence.Present,
        // fixed to holding templates.
        templateFilter = Presence.NotApplicable,
        atOrBefore = recordTimeIsAtOrBefore,
        asOfRound = AsOfRound.NotApplicable,
      ),
    )
  }

  override def getHoldingsStateAtV2(respond: ScanResource.GetHoldingsStateAtV2Response.type)(
      body: HoldingsStateRequestV2
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsStateAtV2Response] = {
    implicit val tc: TraceContext = extracted
    def toResponse(result: QueryAcsSnapshotResult) =
      ScanResource.GetHoldingsStateAtV2ResponseOK(toAcsV2Response(body.migrationId, result))
    val opId = "getHoldingsStateAtV2"
    withSpan(s"$workflowId.$opId") { _ => _ =>
      holdingStateQuery(
        operation = opId,
        migrationId = body.migrationId,
        recordTime = body.recordTime,
        recordTimeIsAtOrBefore =
          body.recordTimeMatch.contains(HoldingsStateRequestV2.RecordTimeMatch.AtOrBefore),
        after =
          body.after.map(AcsSnapshotStore.QueryAcsSnapshotPaginationToken.tryDecodeFromBase64),
        pageSize = body.pageSize,
        ownerPartyIds = body.ownerPartyIds,
        toResponse,
      ).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsStateAtV2ResponseNotFound(
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
      val boundedPartyIds =
        nonEmptyOrFail("owner_party_ids", maxSizeOrFail("owner_party_ids", partyIds))

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
            boundedPartyIds.map(PartyId.tryFromProtoPrimitive),
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
        SnapshotQueryLabels(
          operation = "getHoldingsSummaryAt",
          partyFilter = Presence.Present,
          templateFilter = Presence.NotApplicable,
          atOrBefore = recordTimeMatch.contains(HoldingsSummaryRequest.RecordTimeMatch.AtOrBefore),
          asOfRound = if (asOfRound.isDefined) AsOfRound.Explicit else AsOfRound.Default,
        ),
      ).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsSummaryAtResponseNotFound(
            ErrorResponse(errorMessage)
          )
      }
    }
  }

  override def getHoldingsSummaryAtV1(
      respond: ScanResource.GetHoldingsSummaryAtV1Response.type
  )(
      body: HoldingsSummaryRequestV1
  )(extracted: TraceContext): Future[ScanResource.GetHoldingsSummaryAtV1Response] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getHoldingsSummaryAtV1") { _ => _ =>
      val HoldingsSummaryRequestV1(
        migrationId,
        recordTime,
        recordTimeMatch,
        partyIds,
      ) = body
      val boundedPartyIds =
        nonEmptyOrFail("owner_party_ids", maxSizeOrFail("owner_party_ids", partyIds))

      // The asOfRound parameter is only consumed by SpliceUtil.holdingFee, which feeds the
      // accumulated*HoldingFees* and totalAvailableCoin fields on HoldingsSummary. The v1
      // response only exposes totalUnlockedCoin, totalLockedCoin, and totalCoinHoldings, all
      // of which are sums of amulet.amount.initialAmount and do not depend on the round.
      // Any value for asOfRound therefore yields the same v1 result; we pass 0 as a sentinel.
      def exactQuery(recordTimeTs: CantonTimestamp) =
        snapshotStore
          .getHoldingsSummary(
            migrationId,
            recordTimeTs,
            boundedPartyIds.map(PartyId.tryFromProtoPrimitive),
            0L,
          )

      def toResponse(result: AcsSnapshotStore.HoldingsSummaryResult) =
        ScanResource.GetHoldingsSummaryAtV1Response.OK(
          definitions.HoldingsSummaryResponseV1(
            Codec.encode(result.recordTime),
            result.migrationId,
            result.summaries.map { case (partyId, holdings) =>
              definitions.HoldingsSummaryV1(
                partyId = Codec.encode(partyId),
                totalUnlockedCoin = Codec.encode(holdings.totalUnlockedCoin),
                totalLockedCoin = Codec.encode(holdings.totalLockedCoin),
                totalCoinHoldings = Codec.encode(holdings.totalCoinHoldings),
              )
            }.toVector,
          )
        )

      queryWithOptionalAtOrBefore(
        migrationId,
        recordTime,
        recordTimeMatch.contains(HoldingsSummaryRequestV1.RecordTimeMatch.AtOrBefore),
        exactQuery,
        toResponse,
        SnapshotQueryLabels(
          operation = "getHoldingsSummaryAtV1",
          partyFilter = Presence.Present,
          templateFilter = Presence.NotApplicable,
          atOrBefore =
            recordTimeMatch.contains(HoldingsSummaryRequestV1.RecordTimeMatch.AtOrBefore),
          // v1 has no as_of_round field.
          asOfRound = AsOfRound.NotApplicable,
        ),
      ).map {
        case Right(response) => response
        case Left(errorMessage) =>
          ScanResource.GetHoldingsSummaryAtV1ResponseNotFound(
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

  override def getMigrationId(
      respond: ScanResource.GetMigrationIdResponse.type
  )()(extracted: TraceContext): Future[ScanResource.GetMigrationIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getMigrationId") { _ => _ =>
      Future.successful(
        ScanResource.GetMigrationIdResponse.OK(
          definitions.GetMigrationIdResponse(updateHistory.domainMigrationId)
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
        amuletPriceVotes <- activeVotesStore.listAmuletPriceVotes()
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
        page <- historicalVotesStore.listVoteRequestResults(
          VoteResultsFilters(
            body.actionName,
            body.accepted,
            requester = body.requester,
            effectiveFrom = body.effectiveFrom,
            effectiveTo = body.effectiveTo,
          ),
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

  override def countVoteRequestResults(
      respond: ScanResource.CountVoteRequestResultsResponse.type
  )(
      body: CountVoteResultsRequest
  )(extracted: TraceContext): Future[ScanResource.CountVoteRequestResultsResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.countVoteRequestResults") { _ => _ =>
      for {
        count <- historicalVotesStore.countVoteRequestResults(
          VoteResultsFilters(
            body.actionName,
            body.accepted,
            requester = body.requester,
            effectiveFrom = body.effectiveFrom,
            effectiveTo = body.effectiveTo,
          )
        )
      } yield ScanResource.CountVoteRequestResultsResponse.OK(
        definitions.CountVoteResultsResponse(count)
      )
    }
  }

  override def getPreviousSvRewardWeight(
      respond: ScanResource.GetPreviousSvRewardWeightResponse.type
  )(
      body: PreviousSvRewardWeightRequest
  )(extracted: TraceContext): Future[ScanResource.GetPreviousSvRewardWeightResponse] = {
    implicit val tc: TraceContext = extracted
    withSpan(s"$workflowId.getPreviousSvRewardWeight") { _ => _ =>
      store
        .lookupLatestSvRewardWeightChange(
          PartyId.tryFromProtoPrimitive(body.svParty),
          body.effectiveBefore,
        )
        .map(weight =>
          ScanResource.GetPreviousSvRewardWeightResponse.OK(
            PreviousSvRewardWeightResponse(rewardWeight = weight.map(_.toString))
          )
        )
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
        targetTotalPurchased <- store.getTotalPurchasedMemberTraffic(StoreMemberId(member), domain)
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
                s"Party ${party} is hosted on multiple participants, which is not supported in this version of the API. Please use the /v1 version instead."
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
        .getValidatorLicenseByValidator(
          maxSizeOrFail("validator_ids", validators).map(v => PartyId.tryFromProtoPrimitive(v))
        )
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
                          bftSequencer.p2pUrl.toString,
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

  private def getStorageEncodings(
      damlValueEncoding: Option[DamlValueEncoding]
  ): NonEmptyList[ScanStorageConfig.Encoding] =
    NonEmptyList.one(
      ScanStorageConfig.Encoding
        .fromDamlValueEncoding(damlValueEncoding.getOrElse(DamlValueEncoding.CompactJson))
    )

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
      atOrBeforeRecordTime: OffsetDateTime,
      damlValueEncoding: Option[DamlValueEncoding],
  )(extracted: TraceContext): Future[ScanResource.ListBulkAcsSnapshotObjectsResponse] = {
    implicit val tc = extracted
    import cats.implicits.*
    withSpan(s"$workflowId.listBulkAcsSnapshotObjects") { _ => _ =>
      (bulkStorage, publicUrlO).tupled.fold(
        Future.failed[ScanResource.ListBulkAcsSnapshotObjectsResponse](
          Status.UNIMPLEMENTED
            .withDescription("Bulk storage or public URL is not configured")
            .asRuntimeException()
        )
      ) { case (bulkStorage, publicUrl) =>
        val recordTimeTs = Codec.tryDecode(Codec.OffsetDateTime)(atOrBeforeRecordTime)
        bulkStorage
          .getCommittedObjectsForAcsSnapshotAtOrBefore(
            recordTimeTs,
            getStorageEncodings(damlValueEncoding),
          )
          .map { case AcsSnapshotObjects(ts, objects) =>
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
    import cats.implicits.*
    withSpan(s"$workflowId.listBulkUpdateHistoryObjects") { _ => _ =>
      (bulkStorage, publicUrlO).tupled.fold(
        Future.failed[ScanResource.ListBulkUpdateHistoryObjectsResponse](
          Status.UNIMPLEMENTED
            .withDescription("Bulk storage or public URL is not configured")
            .asRuntimeException()
        )
      ) { case (bulkStorage, publicUrl) =>
        val afterTs = Codec.tryDecode(Codec.OffsetDateTime)(body.startRecordTime)
        val upToTs = Codec.tryDecode(Codec.OffsetDateTime)(body.endRecordTime)
        bulkStorage
          .getCommittedUpdatesBetweenDates(
            afterTs,
            upToTs,
            PageLimit.tryCreate(body.pageSize),
            body.nextPageToken,
            getStorageEncodings(body.damlValueEncoding),
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

  override def getBulkObjectChecksums(respond: ScanResource.GetBulkObjectChecksumsResponse.type)(
      body: GetBulkObjectChecksumsRequest
  )(extracted: TraceContext): Future[ScanResource.GetBulkObjectChecksumsResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getBulkObjectChecksums") { _ => _ =>
      bulkStorage.fold(
        Future.failed[ScanResource.GetBulkObjectChecksumsResponse](
          Status.UNIMPLEMENTED
            .withDescription("Bulk storage is not configured")
            .asRuntimeException()
        )
      ) { bulkStorage =>
        for {
          checksums <- bulkStorage.getObjectChecksums(maxSizeOrFail("object_keys", body.objectKeys))
        } yield {
          ScanResource.GetBulkObjectChecksumsResponse.OK(
            definitions.GetBulkObjectChecksumsResponse(
              checksums.map(definitions.GetBulkObjectChecksumsResponse.Checksums(_)).toVector
            )
          )
        }
      }
    }
  }

  override def getBulkObjectsProgress(respond: ScanResource.GetBulkObjectsProgressResponse.type)(
      atOrBeforeRecordTime: java.time.OffsetDateTime,
      bucket: definitions.BulkStorageBucket,
      kind: Option[definitions.BulkObjectKind],
  )(
      extracted: TraceContext
  ): scala.concurrent.Future[ScanResource.GetBulkObjectsProgressResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getBulkObjectProgress") { _ => _ =>
      bulkStorage.fold(
        Future.failed[ScanResource.GetBulkObjectsProgressResponse](
          Status.UNIMPLEMENTED
            .withDescription("Bulk storage is not configured")
            .asRuntimeException()
        )
      ) { bulkStorage =>
        val requested = CantonTimestamp.tryFromInstant(atOrBeforeRecordTime.toInstant)
        val objectKind = kind.map(HttpScanHandler.fromHttpObjectKind)
        for {
          progress <- bucket match {
            case definitions.BulkStorageBucket.members.Committed =>
              bulkStorage.getCommittedProgressTimestamp(objectKind)
            case definitions.BulkStorageBucket.members.Staging =>
              bulkStorage.getStagingProgressTimestamp(objectKind)
          }
          firstOwnSegmentStart <- bulkStorage.getFirstOwnSegmentStart()
        } yield {
          ScanResource.GetBulkObjectsProgressResponse.OK(
            definitions.GetBulkObjectsProgressResponse(
              HttpScanHandler.toHttpAvailability(
                BulkObjectsAvailability.of(progress, requested, firstOwnSegmentStart)
              )
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

  override def getLsu(respond: ScanResource.GetLsuResponse.type)()(
      extracted: TraceContext
  ): Future[ScanResource.GetLsuResponse] = {
    implicit val tc = extracted
    for {
      currentSynchronizerId <- synchronizerNodeService.nodes.current.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      maybeAnnouncement <- participantAdminConnection.lookupSynchronizerLsuAnnouncement(
        synchronizerId = currentSynchronizerId.logical,
        timeQuery = TimeQuery.HeadState,
        topologyTransactionType = TopologyTransactionType.AuthorizedState,
      )
    } yield ScanResource.GetLsuResponse.OK(
      definitions.GetLsuResponse(
        maybeAnnouncement.map { announcement =>
          definitions.Lsu(
            topologyFreezeTime = announcement.base.validFrom.atOffset(ZoneOffset.UTC),
            upgradeTime = announcement.mapping.upgradeTime.toInstant.atOffset(ZoneOffset.UTC),
            successorPhysicalSynchronizerId =
              announcement.mapping.successorSynchronizerId.toProtoPrimitive,
          )
        }
      )
    )
  }

  def getRewardAccountingEarliestAvailableRound(
      respond: ScanResource.GetRewardAccountingEarliestAvailableRoundResponse.type
  )()(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingEarliestAvailableRoundResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRewardAccountingEarliestAvailableRound") { _ => _ =>
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
    }
  }

  def getRewardAccountingActivityTotals(
      respond: ScanResource.GetRewardAccountingActivityTotalsResponse.type
  )(roundNumber: Long)(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingActivityTotalsResponse
  ] = {
    implicit val tc = extracted
    val undetermined = ScanResource.GetRewardAccountingActivityTotalsResponse.OK(
      definitions.GetRewardAccountingActivityTotalsResponse(
        definitions.RewardAccountingActivityTotalsUndetermined(status = "Undetermined")
      )
    )
    val cannotProvide = ScanResource.GetRewardAccountingActivityTotalsResponse.OK(
      definitions.GetRewardAccountingActivityTotalsResponse(
        definitions.RewardAccountingActivityTotalsCannotProvide(status = "CannotProvide")
      )
    )
    withSpan(s"$workflowId.getRewardAccountingActivityTotals") { _ => _ =>
      appRewardsStore.getAppActivityRoundTotalByRound(roundNumber).flatMap {
        case Some(activityTotal) =>
          appRewardsStore.getAppRewardRoundTotalByRound(roundNumber).map {
            case Some(rewardTotal) =>
              ScanResource.GetRewardAccountingActivityTotalsResponse.OK(
                definitions.GetRewardAccountingActivityTotalsResponse(
                  definitions.RewardAccountingActivityTotalsOk(
                    status = "Ok",
                    roundNumber = activityTotal.roundNumber,
                    totalAppActivityWeight = activityTotal.totalRoundAppActivityWeight,
                    activePartiesCount = activityTotal.activeAppProviderPartiesCount,
                    activityRecordsCount = activityTotal.activityRecordsCount,
                    totalAppRewardMintingAllowance =
                      rewardTotal.totalAppRewardMintingAllowance.toString,
                    totalAppRewardThresholded = rewardTotal.totalAppRewardThresholded.toString,
                    totalAppRewardUnclaimed = rewardTotal.totalAppRewardUnclaimed.toString,
                    rewardedAppProviderPartiesCount = rewardTotal.rewardedAppProviderPartiesCount,
                  )
                )
              )
            case None =>
              // We should never hit this, as both activity totals and round
              // totals are added in a single DB Tx
              undetermined
          }
        case None =>
          appActivityStore.ingestionStatusForRound(roundNumber).map {
            case RoundIngestionStatus.CannotProvide => cannotProvide
            case RoundIngestionStatus.Undetermined => undetermined
          }
      }
    }
  }

  def getRewardAccountingRootHash(
      respond: ScanResource.GetRewardAccountingRootHashResponse.type
  )(roundNumber: Long)(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingRootHashResponse
  ] = {
    implicit val tc = extracted
    val undetermined = ScanResource.GetRewardAccountingRootHashResponse.OK(
      definitions.GetRewardAccountingRootHashResponse(
        definitions.RewardAccountingRootHashUndetermined(status = "Undetermined")
      )
    )
    val cannotProvide = ScanResource.GetRewardAccountingRootHashResponse.OK(
      definitions.GetRewardAccountingRootHashResponse(
        definitions.RewardAccountingRootHashCannotProvide(status = "CannotProvide")
      )
    )
    withSpan(s"$workflowId.getRewardAccountingRootHash") { _ => _ =>
      appRewardsStore.getAppRewardRootHashByRound(roundNumber).flatMap {
        case Some(rootHash) =>
          Future.successful(
            ScanResource.GetRewardAccountingRootHashResponse.OK(
              definitions.GetRewardAccountingRootHashResponse(
                definitions.RewardAccountingRootHashOk(
                  status = "Ok",
                  roundNumber = rootHash.roundNumber,
                  rootHash = rootHash.rootHash.toHex,
                )
              )
            )
          )
        case None =>
          appActivityStore.ingestionStatusForRound(roundNumber).map {
            case RoundIngestionStatus.CannotProvide => cannotProvide
            case RoundIngestionStatus.Undetermined => undetermined
          }
      }
    }
  }

  def getRewardAccountingBatch(
      respond: ScanResource.GetRewardAccountingBatchResponse.type
  )(roundNumber: Long, batchHash: String)(extracted: TraceContext): Future[
    ScanResource.GetRewardAccountingBatchResponse
  ] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getRewardAccountingBatch") { _ => _ =>
      appRewardsStore
        .lookupBatchByHash(roundNumber, DbScanAppRewardsStore.RewardHash.fromHex(batchHash))
        .map {
          case None =>
            ScanResource.GetRewardAccountingBatchResponse.NotFound(
              ErrorResponse(
                s"Batch not (yet) found for round $roundNumber with hash $batchHash"
              )
            )
          case Some(batch: DbScanAppRewardsStore.BatchOfBatches) =>
            ScanResource.GetRewardAccountingBatchResponse.OK(
              definitions.GetRewardAccountingBatchResponse(
                definitions.RewardAccountingBatchOfBatches(
                  batchType = "BatchOfBatches",
                  childHashes = batch.childHashes.map(_.toHex).toVector,
                )
              )
            )
          case Some(batch: DbScanAppRewardsStore.BatchOfMintingAllowances) =>
            ScanResource.GetRewardAccountingBatchResponse.OK(
              definitions.GetRewardAccountingBatchResponse(
                definitions.RewardAccountingBatchOfMintingAllowances(
                  batchType = "BatchOfMintingAllowances",
                  mintingAllowances = batch.allowances
                    .map(a =>
                      definitions.RewardAccountingMintingAllowance(
                        provider = a.provider,
                        amount = a.amount.toString,
                      )
                    )
                    .toVector,
                )
              )
            )
        }
    }
  }
}

object HttpScanHandler {
  private def toHttpAvailability(
      availability: BulkObjectsAvailability
  ): definitions.BulkObjectsAvailability =
    availability match {
      case BulkObjectsAvailability.Available => definitions.BulkObjectsAvailability.Available
      case BulkObjectsAvailability.Processing => definitions.BulkObjectsAvailability.Processing
      case BulkObjectsAvailability.Backfilling => definitions.BulkObjectsAvailability.Backfilling
    }

  private def fromHttpObjectKind(kind: definitions.BulkObjectKind): BulkObjectKind =
    kind match {
      case definitions.BulkObjectKind.members.Updates => BulkObjectKind.Updates
      case definitions.BulkObjectKind.members.Snapshots => BulkObjectKind.Snapshots
    }

  // We expect a handful at most but want to somewhat guard against attacks
  // so we just hardcode a limit of 100.
  private val MAX_TRANSFER_COMMAND_CONTRACTS: Int = 100

  // for DsoSequencers that use the serial instead of the migration we set -1 as the migration id
  // we can't simply make it non required as it's part of the public API and it would break clients
  val NoMigrationIdSet = -1L
}
