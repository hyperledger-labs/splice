// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.cors.scaladsl.CorsDirectives.cors
import org.apache.pekko.http.cors.scaladsl.settings.CorsSettings
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round as roundCodegen
import org.lfdecentralizedtrust.splice.config.{NetworkAppClientConfig, SharedSpliceAppParameters}
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  DarResources,
  Node,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryFor,
  SequencerAdminConnection,
  SpliceLedgerClient,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.http.v0.scan.ScanResource
import org.lfdecentralizedtrust.splice.http.v0.scanStream.ScanStreamResource
import org.lfdecentralizedtrust.tokenstandard.metadata.v1.Resource as TokenStandardMetadataResource
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.Resource as TokenStandardTransferInstructionV1Resource
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v2.Resource as TokenStandardTransferInstructionV2Resource
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.Resource as TokenStandardAllocationV1Resource
import org.lfdecentralizedtrust.tokenstandard.allocation.v2.Resource as TokenStandardAllocationV2Resource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.Resource as TokenStandardAllocationInstructionV1Resource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v2.Resource as TokenStandardAllocationInstructionV2Resource
import org.lfdecentralizedtrust.splice.http.HttpRateLimiter
import org.lfdecentralizedtrust.splice.scan.admin.http.{
  HttpScanHandler,
  HttpScanStreamHandler,
  HttpTokenStandardAllocationHandler,
  HttpTokenStandardAllocationInstructionHandler,
  HttpTokenStandardMetadataHandler,
  HttpTokenStandardTransferInstructionHandler,
}
import org.lfdecentralizedtrust.splice.scan.automation.{
  ScanAutomationService,
  ScanVerdictAutomationService,
}
import org.lfdecentralizedtrust.splice.scan.rewards.AppActivityComputation
import org.lfdecentralizedtrust.splice.scan.admin.api.client.SingleScanConnection
import org.lfdecentralizedtrust.splice.scan.config.{
  ScanAppBackendConfig,
  ScanAppClientConfig,
  ScanSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs.scanStorageConfigV1
import org.lfdecentralizedtrust.splice.scan.dso.DsoAnsResolver
import org.lfdecentralizedtrust.splice.scan.metrics.ScanAppMetrics
import org.lfdecentralizedtrust.splice.scan.sequencer.SequencerTrafficClient
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  ScanEventStore,
  ScanKeyValueProvider,
  ScanKeyValueStore,
  ScanRewardsReferenceStore,
  ScanStore,
}
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorage
import org.lfdecentralizedtrust.splice.scan.store.historystart.{
  KvHistoryStartStore,
  ParticipantHistoryStartSources,
  ScanHistoryReadiness,
  ScanHistoryStart,
}
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbAppActivityRecordStore,
  DbScanAppRewardsStore,
  DbScanVerdictStore,
}
import org.lfdecentralizedtrust.splice.store.db.{DbAppStore, InternedStringStore}
import org.lfdecentralizedtrust.splice.store.{
  ChoiceContextContractFetcher,
  PageLimit,
  S3BucketConnection,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.concurrent.{ExecutionContextExecutor, Future}
import cats.implicits.*
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId

/** Class representing a Scan app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ScanApp(
    override val name: InstanceName,
    val config: ScanAppBackendConfig,
    val amuletAppParameters: SharedSpliceAppParameters,
    storage: DbStorage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    scanAppMetrics: ScanAppMetrics,
    adminRoutes: AdminRoutes,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends Node[ScanApp.State, Unit](
      config.svUser,
      config.participantClient,
      amuletAppParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      scanAppMetrics,
    ) {

  override def packagesForJsonDecoding =
    super.packagesForJsonDecoding ++ DarResources.amuletNameService.all ++ DarResources.dsoGovernance.all

  override def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit traceContext: TraceContext) = Future.unit

  def synchronizerNode(syncConfig: ScanSynchronizerConfig): ScanSynchronizerNode =
    new ScanSynchronizerNode(
      new SequencerAdminConnection(
        syncConfig.sequencer,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        scanAppMetrics.grpcClientMetrics,
        retryProvider,
      ),
      new SequencerTrafficClient(
        syncConfig.sequencer,
        retryProvider,
        scanAppMetrics.grpcClientMetrics,
        loggerFactory,
      ),
    )

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      // The primary party in scan as that points to the SV party
      serviceUserPrimaryParty: PartyId,
      preInitializeState: Unit,
  )(implicit tc: TraceContext): Future[ScanApp.State] = {
    val appInitConnection = ledgerClient
      .readOnlyConnection(
        this.getClass.getSimpleName,
        loggerFactory,
      )
    val bftSequencersWithAdminConnections = {
      val all = Seq(config.synchronizerNodes.current) ++
        config.synchronizerNodes.successor.toList ++
        config.synchronizerNodes.legacy.toList
      all.flatMap { syncConfig =>
        syncConfig.cantonBft.map { bftConfig =>
          new SequencerAdminConnection(
            syncConfig.sequencer,
            amuletAppParameters.loggingConfig.api,
            loggerFactory,
            scanAppMetrics.grpcClientMetrics,
            retryProvider,
          ) -> bftConfig
        }
      }
    }
    for {
      dsoParty <- appInitStep("Get DSO party from user metadata") {
        appInitConnection.getDsoPartyFromUserMetadata(config.svUser)
      }
      initialRound <- appInitStep("Get initial round from user metadata") {
        appInitConnection.getInitialRoundFromUserMetadata(config.svUser)
      }
      _ = logger.debug(s"Started with initial round $initialRound")
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        scanAppMetrics.grpcClientMetrics,
        retryProvider,
      )
      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      svName <- appInitStep(s"Get SV name from ${config.svUser}") {
        appInitConnection.getSvNameFromUserMetadata(config.svUser)
      }
      domainMigrationId <- appInitStep("Resolving domain migration id") {
        resolveDomainMigrationId()
      }
      store <- ScanStore(
        key = ScanStore.Key(dsoParty = dsoParty),
        storage,
        loggerFactory,
        retryProvider,
        domainMigrationId,
        participantId,
        config.cache,
        scanAppMetrics.dbScanStore,
        config.automation.ingestion,
        config.parameters.defaultLimit,
        config.acsStoreDescriptorUserVersion,
        config.txLogStoreDescriptorUserVersion,
      )
      internedStringStore <- InternedStringStore.createAndWarmupCache(
        storage,
        config.cache.internedStrings.maxSize,
        config.cache.internedStrings.ttl.underlying,
        loggerFactory,
        retryProvider.metricsFactory,
      )
      updateHistory <- UpdateHistory.create(
        storage,
        domainMigrationId,
        store.storeName,
        StoreMemberId(participantId),
        store.acsContractFilter.ingestionFilter.primaryParty,
        BackfillingRequirement.NeedsBackfilling,
        internedStringStore,
        config.analyzableTimeWindow.duration,
        loggerFactory,
        enableImportUpdateBackfill = config.updateHistoryBackfillImportUpdatesEnabled,
        scanAppMetrics.dbScanStore.history,
      )
      acsSnapshotStore = AcsSnapshotStore(
        storage,
        updateHistory,
        dsoParty,
        domainMigrationId,
        loggerFactory,
      )
      syncNodes = LocalSynchronizerNodes(
        current = synchronizerNode(
          config.synchronizerNodes.current
        ),
        successor = config.synchronizerNodes.successor.map(synchronizerNode(_)),
        legacy = config.synchronizerNodes.legacy.map(synchronizerNode(_)),
        additionalLegacy = Seq.empty,
      )
      syncService = new SynchronizerNodeService(
        syncNodes,
        participantAdminConnection,
        config.globalSynchronizerAlias,
        config.parameters.spliceCachingConfigs.physicalSynchronizerExpiration,
        retryProvider,
        loggerFactory,
      )
      kvStore <- ScanKeyValueStore(dsoParty, participantId, storage, loggerFactory)
      kvProvider = new ScanKeyValueProvider(kvStore, loggerFactory)
      historyStart = new ScanHistoryStart(
        new KvHistoryStartStore(kvProvider),
        new ParticipantHistoryStartSources(
          config.isFirstSv,
          config.updateHistoryBackfillEnabled && config.updateHistoryBackfillImportUpdatesEnabled,
          updateHistory,
          domainMigrationId,
          participantAdminConnection,
          config.globalSynchronizerAlias,
          participantId,
          dsoParty,
        ),
        loggerFactory,
      )
      _ <-
        if (config.bulkStorage.debugReresolveHistoryStart) historyStart.forgetRecorded()
        else Future.unit
      historyReadiness = new ScanHistoryReadiness(updateHistory, historyStart)
      bulkStorage <- (config.bulkStorage.staging, config.bulkStorage.committed).tupled.traverse(_ =>
        appInitStep("Initialize bulk storage") {
          BulkStorage(
            scanStorageConfigV1,
            config.bulkStorage,
            acsSnapshotStore,
            updateHistory,
            currentMigrationId = domainMigrationId,
            kvProvider,
            historyStart,
            historyReadiness,
            retryProvider.metricsFactory,
            config.automation,
            backoffClock = new WallClock(retryProvider.timeouts, loggerFactory),
            store,
            svName,
            ledgerClient,
            amuletAppParameters.upgradesConfig,
            retryProvider,
            loggerFactory,
          )
        }
      )
      appActivityRecordStore = new DbAppActivityRecordStore(
        storage,
        updateHistory,
        DbAppActivityRecordStore.IngestionVersions(
          AppActivityComputation.ActivityIngestionCodeVersion,
          config.activityIngestionUserVersion.fold(0)(_.toInt),
        ),
        config.isFirstSv,
        initialRound.toLong,
        loggerFactory,
      )
      appRewardsStore = new DbScanAppRewardsStore(
        storage,
        updateHistory,
        appActivityRecordStore,
        config.rewardMintingAllowanceTolerance,
        loggerFactory,
      )
      synchronizerId <-
        retryProvider.getValueWithRetries(
          RetryFor.WaitingOnInitDependency,
          "synchronizer id",
          "synchronizer id from participant",
          participantAdminConnection.getSynchronizerId(config.globalSynchronizerAlias),
          logger,
        )
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        synchronizerId,
        appInitConnection,
        loggerFactory,
      )
      automation = new ScanAutomationService(
        config,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        store,
        updateHistory,
        appRewardsStore,
        appActivityRecordStore,
        storage,
        acsSnapshotStore,
        serviceUserPrimaryParty,
        svName,
        amuletAppParameters.upgradesConfig,
        packageVersionSupport,
        historyReadiness,
      )
      _ = automation.registerHistoryStartTrigger(historyStart)
      scanVerdictStore = DbScanVerdictStore(
        storage,
        updateHistory,
        appActivityRecordStore,
        loggerFactory,
      )(ec)
      scanEventStore = new ScanEventStore(
        scanVerdictStore,
        updateHistory,
        loggerFactory,
      )(ec)
      _ <- appInitStep("Wait until there is an OpenMiningRound contract") {
        retryProvider.waitUntil(
          RetryFor.WaitingOnInitDependencyLong,
          "wait_open_mining",
          "there is an OpenMiningRound contract",
          store.multiDomainAcsStore
            .listContracts(roundCodegen.OpenMiningRound.COMPANION, limit = PageLimit.tryCreate(1))
            .map { cs =>
              if (cs.isEmpty) {
                throw Status.NOT_FOUND
                  .withDescription("OpenMiningRound contract")
                  .asRuntimeException()
              }
            },
          logger,
        )
      }
      dsoAnsResolver = new DsoAnsResolver(
        dsoParty,
        config.spliceInstanceNames.nameServiceNameAcronym.toLowerCase(),
      )
      rewardsReferenceStore <- ScanRewardsReferenceStore(
        key = ScanRewardsReferenceStore.Key(
          dsoParty = dsoParty,
          synchronizerId = synchronizerId,
        ),
        storage,
        loggerFactory,
        retryProvider,
        domainMigrationId,
        participantId,
        config.automation.ingestion,
        config.parameters.defaultLimit,
      )
      _ = {
        automation.registerRewardsReferenceStoreIngestion(rewardsReferenceStore)
        automation.registerRewardComputationTrigger(rewardsReferenceStore)
        automation.registerPruneRewardAccountingTrigger(rewardsReferenceStore, scanVerdictStore)
      }
      verdictAutomation = new ScanVerdictAutomationService(
        config,
        syncNodes,
        clock,
        retryProvider,
        loggerFactory,
        scanAppMetrics.grpcClientMetrics,
        scanVerdictStore,
        domainMigrationId,
        synchronizerId,
        scanAppMetrics.verdictIngestion,
        rewardsReferenceStore,
      )
      scanHandler = new HttpScanHandler(
        serviceUserPrimaryParty,
        config.svUser,
        config.spliceInstanceNames,
        participantAdminConnection,
        syncService,
        automation,
        updateHistory,
        appRewardsStore,
        appActivityRecordStore,
        acsSnapshotStore,
        scanEventStore,
        store, // TODO(#7728): switch to DbVotesStore once backfilling is ready
        bulkStorage.map(_.reader),
        scanAppMetrics.httpApi,
        dsoAnsResolver,
        config.miningRoundsCacheTimeToLiveOverride,
        config.enableForcedAcsSnapshots,
        config.perAcsSnapshotTablesEnabled,
        clock,
        loggerFactory,
        packageVersionSupport,
        bftSequencersWithAdminConnections,
        initialRound,
        externalTransactionHashThresholdTime = config.externalTransactionHashThresholdTime,
        config.updateHistoryMaxPageSize,
        config.publicUrl,
        config.rollForwardLsu,
      )
      scanStreamHandler = new HttpScanStreamHandler(
        // TODO(#5884): consider whether this should be going through bulkStorageReader instead of directly to the bucket.
        config.bulkStorage.committed.map(S3BucketConnection(_, loggerFactory))
      )
      contractFetcher = ChoiceContextContractFetcher.createStoreWithLedgerFallback(
        config.parameters.contractFetchLedgerFallbackConfig,
        store,
        appInitConnection,
        clock,
        loggerFactory,
      )

      tokenStandardTransferInstructionHandler = new HttpTokenStandardTransferInstructionHandler(
        store,
        contractFetcher,
        clock,
        loggerFactory,
      )
      tokenStandardAllocationHandler = new HttpTokenStandardAllocationHandler(
        store,
        contractFetcher,
        clock,
        config.tokenStandardSettlement,
        loggerFactory,
      )

      tokenStandardMetadataHandler = new HttpTokenStandardMetadataHandler(
        store,
        acsSnapshotStore,
        config.spliceInstanceNames,
        loggerFactory,
      )

      tokenStandardAllocationInstructionHandler = new HttpTokenStandardAllocationInstructionHandler(
        store,
        clock,
        config.tokenStandardSettlement,
        loggerFactory,
      )
      httpRateLimiter = new HttpRateLimiter(
        config.parameters.rateLimiting,
        scanAppMetrics.openTelemetryMetricsFactory,
        loggerFactory.getTracedLogger(classOf[HttpRateLimiter]),
      )
      route = cors(
        CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { traceContext =>
          {
            def buildRouteForOperation(operation: String, httpService: String) = {
              scanAppMetrics.httpServerMetrics
                .withMetrics(httpService)(operation)
                .tflatMap(_ =>
                  // rate limit after the metrics to capture the result in the http metrics
                  httpRateLimiter.withRateLimit(httpService)(operation).tflatMap { _ =>
                    val httpErrorHandler = new HttpErrorHandler(loggerFactory)
                    (httpService, config.parameters.customTimeouts.get(operation)) match {
                      // custom HTTP timeouts
                      case ("scan", Some(customTimeout)) =>
                        withRequestTimeout(
                          customTimeout.duration,
                          httpErrorHandler.timeoutHandler(customTimeout.duration, _),
                        ).tflatMap { _ =>
                          // only apply exceptions directive for custom timeout routes
                          httpErrorHandler.exceptionsDirective(traceContext).tflatMap { _ =>
                            provide(traceContext)
                          }
                        }
                      case _ =>
                        httpErrorHandler.directive(traceContext).tflatMap { _ =>
                          provide(traceContext)
                        }
                    }
                  }
                )
            }

            concat(
              ScanResource.routes(
                scanHandler,
                buildRouteForOperation(_, "scan"),
              ),
              ScanStreamResource.routes(
                scanStreamHandler,
                buildRouteForOperation(_, "scan_stream"),
              ),
              TokenStandardTransferInstructionV1Resource.routes(
                tokenStandardTransferInstructionHandler,
                buildRouteForOperation(_, "token_standard_transfer_instruction_v1"),
              ),
              TokenStandardTransferInstructionV2Resource.routes(
                tokenStandardTransferInstructionHandler,
                buildRouteForOperation(_, "token_standard_transfer_instruction_v2"),
              ),
              TokenStandardAllocationInstructionV1Resource.routes(
                tokenStandardAllocationInstructionHandler,
                buildRouteForOperation(_, "token_standard_allocation_instruction_v1"),
              ),
              TokenStandardAllocationInstructionV2Resource.routes(
                tokenStandardAllocationInstructionHandler,
                buildRouteForOperation(_, "token_standard_allocation_instruction_v2"),
              ),
              TokenStandardMetadataResource.routes(
                tokenStandardMetadataHandler,
                buildRouteForOperation(_, "token_standard_metadata"),
              ),
              TokenStandardAllocationV1Resource.routes(
                tokenStandardAllocationHandler,
                buildRouteForOperation(_, "token_standard_allocation_v1"),
              ),
              TokenStandardAllocationV2Resource.routes(
                tokenStandardAllocationHandler,
                buildRouteForOperation(_, "token_standard_allocation_v2"),
              ),
            )
          }
        }
      }
      _ = adminRoutes.updateRoute(route)
    } yield {
      ScanApp.State(
        participantAdminConnection,
        syncNodes,
        storage,
        store,
        automation,
        bulkStorage,
        historyStart,
        verdictAutomation,
        scanEventStore,
        rewardsReferenceStore,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
        timeouts,
        bftSequencersWithAdminConnections.map(_._1),
        Seq(httpRateLimiter),
      )
    }
  }

  private def resolveDomainMigrationId()(implicit tc: TraceContext): Future[Long] =
    DbAppStore.getHighestKnownMigrationId(storage).flatMap {
      case Some(migrationId) =>
        logger.info(s"Resolved domain migration id $migrationId from the local store offsets")
        Future.successful(migrationId)
      case None if config.isFirstSv =>
        logger.info("Resolved domain migration id 0 for the founding scan")
        Future.successful(0L)
      case None =>
        config.sponsorScanUrl match {
          case Some(sponsorScanUrl) =>
            migrationIdFromSponsorScan(sponsorScanUrl).map { migrationId =>
              logger.info(
                s"Resolved domain migration id $migrationId from the sponsor scan $sponsorScanUrl"
              )
              migrationId
            }
          case None =>
            Future.failed(
              Status.FAILED_PRECONDITION
                .withDescription(
                  "No migration id found in the DB and no sponsor scan configured. " +
                    "Set `sponsor-scan-url` in the scan config to bootstrap this node."
                )
                .asRuntimeException()
            )
        }
    }

  private def migrationIdFromSponsorScan(
      sponsorScanUrl: NetworkAppClientConfig
  )(implicit tc: TraceContext): Future[Long] =
    SingleScanConnection.withSingleScanConnection(
      ScanAppClientConfig(adminApi = sponsorScanUrl),
      amuletAppParameters.upgradesConfig,
      clock,
      retryProvider,
      loggerFactory,
    ) { connection =>
      retryProvider.getValueWithRetries(
        RetryFor.WaitingOnInitDependency,
        "get_migration_id",
        "Getting migration id from sponsor scan",
        connection.getMigrationId(),
        logger,
      )
    }(ec, tc, Materializer(ac), httpClient, templateDecoder)

  override lazy val ports = Map("admin" -> config.adminApi.port)

  protected[this] override def automationServices(st: ScanApp.State) =
    Seq(st.automation, st.verdictAutomation)
}

object ScanApp {

  case class State(
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerNodes: LocalSynchronizerNodes[ScanSynchronizerNode],
      storage: Storage,
      store: ScanStore,
      automation: ScanAutomationService,
      bulkStorage: Option[BulkStorage],
      historyStart: ScanHistoryStart,
      verdictAutomation: ScanVerdictAutomationService,
      eventStore: ScanEventStore,
      rewardsReferenceStore: ScanRewardsReferenceStore,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
      bftSequencersAdminConnections: Seq[SequencerAdminConnection],
      cleanups: Seq[AutoCloseable],
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive

    override def close(): Unit = {
      // Close everything in one LifeCycle.close call: it closes every instance left to right
      // even when some of them fail, whereas separate calls stop at the first failing call.
      val instances: Seq[AutoCloseable] =
        bftSequencersAdminConnections ++
          cleanups ++
          bulkStorage.toList ++
          Seq(
            automation,
            verdictAutomation,
            store,
            rewardsReferenceStore,
            storage,
            synchronizerNodes.current,
            participantAdminConnection,
          ) ++
          synchronizerNodes.successor.toList ++
          synchronizerNodes.legacy.toList ++
          synchronizerNodes.additionalLegacy
      LifeCycle.close(instances*)(logger)
    }
  }
}
