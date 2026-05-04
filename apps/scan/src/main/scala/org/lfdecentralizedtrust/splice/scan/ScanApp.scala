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
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.TraceContextDirectives.withTraceContext
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpErrorHandler}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round as roundCodegen
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
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
import org.lfdecentralizedtrust.tokenstandard.transferinstruction.v1.Resource as TokenStandardTransferInstructionResource
import org.lfdecentralizedtrust.tokenstandard.allocation.v1.Resource as TokenStandardAllocationV1Resource
import org.lfdecentralizedtrust.tokenstandard.allocation.v2.Resource as TokenStandardAllocationV2Resource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v1.Resource as TokenStandardAllocationInstructionV1Resource
import org.lfdecentralizedtrust.tokenstandard.allocationinstruction.v2.Resource as TokenStandardAllocationInstructionV2Resource
import org.lfdecentralizedtrust.splice.http.HttpRateLimiter
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
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
import org.lfdecentralizedtrust.splice.scan.config.{ScanAppBackendConfig, ScanSynchronizerConfig}
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
import org.lfdecentralizedtrust.splice.scan.store.db.{
  DbAppActivityRecordStore,
  DbScanAppRewardsStore,
  DbScanVerdictStore,
  ScanAggregatesReader,
  ScanAggregatesReaderContext,
}
import org.lfdecentralizedtrust.splice.store.{
  ChoiceContextContractFetcher,
  PageLimit,
  S3BucketConnection,
  UpdateHistory,
}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingRequirement
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.concurrent.{ExecutionContextExecutor, Future}

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
    nodeMetrics: ScanAppMetrics,
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
      nodeMetrics,
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
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      ),
      if (config.enableAppActivityRecordAndTrafficIngestion) {
        Some(
          new SequencerTrafficClient(
            syncConfig.sequencer,
            retryProvider,
            nodeMetrics.grpcClientMetrics,
            loggerFactory,
          )
        )
      } else None,
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
        syncConfig.bftSequencerConfig.map { bftConfig =>
          new SequencerAdminConnection(
            syncConfig.sequencer,
            amuletAppParameters.loggingConfig.api,
            loggerFactory,
            nodeMetrics.grpcClientMetrics,
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
      scanAggregatesReaderContext = new ScanAggregatesReaderContext(
        clock,
        ledgerClient,
        amuletAppParameters.upgradesConfig,
        loggerFactory,
        retryProvider,
        ec,
        Materializer(ac),
        httpClient,
        templateDecoder,
      )
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        amuletAppParameters.loggingConfig.api,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        retryProvider,
      )
      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      migrationInfo <- appInitStep(s"Get domain migration info from ${config.svUser}") {
        DomainMigrationInfo.loadFromUserMetadata(
          appInitConnection,
          config.svUser,
        )
      }
      svName <- appInitStep(s"Get SV name from ${config.svUser}") {
        appInitConnection.getSvNameFromUserMetadata(config.svUser)
      }
      _ = if (config.domainMigrationId != migrationInfo.currentMigrationId) {
        throw Status.INVALID_ARGUMENT
          .withDescription(
            s"Migration id ${migrationInfo.currentMigrationId} from the the SV user metadata does not match the configured migration id ${config.domainMigrationId} in the scan app. Please check if the scan app is configured with the correct migration id"
          )
          .asRuntimeException()
      }
      store = ScanStore(
        key = ScanStore.Key(dsoParty = dsoParty),
        storage,
        isFirstSv = config.isFirstSv,
        loggerFactory,
        retryProvider,
        { store =>
          ScanAggregatesReader(store, scanAggregatesReaderContext)
        },
        migrationInfo,
        participantId,
        config.cache,
        nodeMetrics.dbScanStore,
        config.automation.ingestion,
        initialRound.toLong,
        config.parameters.defaultLimit,
        config.acsStoreDescriptorUserVersion,
        config.txLogStoreDescriptorUserVersion,
      )
      updateHistory = new UpdateHistory(
        storage,
        migrationInfo,
        store.storeName,
        participantId,
        store.acsContractFilter.ingestionFilter.primaryParty,
        BackfillingRequirement.NeedsBackfilling,
        loggerFactory,
        enableissue12777Workaround = true,
        enableImportUpdateBackfill = config.updateHistoryBackfillImportUpdatesEnabled,
        nodeMetrics.dbScanStore.history,
      )
      acsSnapshotStore = AcsSnapshotStore(
        storage,
        updateHistory,
        dsoParty,
        migrationInfo.currentMigrationId,
        loggerFactory,
      )
      syncNodes = LocalSynchronizerNodes(
        current = synchronizerNode(
          config.synchronizerNodes.current
        ),
        successor = config.synchronizerNodes.successor.map(synchronizerNode(_)),
        legacy = config.synchronizerNodes.legacy.map(synchronizerNode(_)),
      )
      syncService = new SynchronizerNodeService(
        syncNodes,
        participantAdminConnection,
        config.globalSynchronizerAlias,
        config.parameters.spliceCachingConfigs.physicalSynchronizerExpiration,
        loggerFactory,
      )
      kvStore <- ScanKeyValueStore(dsoParty, participantId, storage, loggerFactory)
      kvProvider = new ScanKeyValueProvider(kvStore, loggerFactory)
      bulkStorage = BulkStorage(
        scanStorageConfigV1,
        config.bulkStorage,
        acsSnapshotStore,
        updateHistory,
        currentMigrationId = migrationInfo.currentMigrationId,
        kvProvider,
        retryProvider.metricsFactory,
        config.automation,
        backoffClock = new WallClock(retryProvider.timeouts, loggerFactory),
        retryProvider,
        loggerFactory,
      )
      // Conditionally create traffic summary ingestion dependencies
      appActivityRecordStoreO =
        if (config.enableAppActivityRecordAndTrafficIngestion) {
          Some(
            new DbAppActivityRecordStore(
              storage,
              updateHistory,
              loggerFactory,
            )
          )
        } else None
      appRewardsStoreO = appActivityRecordStoreO.map(appActivityRecordStore =>
        new DbScanAppRewardsStore(storage, updateHistory, appActivityRecordStore, loggerFactory)
      )
      automation = new ScanAutomationService(
        config,
        clock,
        ledgerClient,
        retryProvider,
        loggerFactory,
        store,
        updateHistory,
        appRewardsStoreO,
        appActivityRecordStoreO,
        storage,
        acsSnapshotStore,
        serviceUserPrimaryParty,
        svName,
        amuletAppParameters.upgradesConfig,
        initialRound.toLong,
      )
      scanVerdictStore = DbScanVerdictStore(
        storage,
        updateHistory,
        appActivityRecordStoreO,
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
      synchronizerId <- appInitStep("Get synchronizer id") {
        retryProvider.getValueWithRetries(
          RetryFor.WaitingOnInitDependency,
          "amulet synchronizer id",
          "amulet rules synchronizer id",
          store.getAmuletRulesWithState().map {
            _.state.fold(
              identity,
              throw Status.FAILED_PRECONDITION
                .withDescription("Amulet rules in fllight")
                .asRuntimeException(),
            )
          },
          logger,
        )
      }
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        synchronizerId,
        appInitConnection,
        loggerFactory,
      )
      rewardsReferenceStoreO =
        if (config.enableAppActivityRecordAndTrafficIngestion) {
          val rewardsStore = ScanRewardsReferenceStore(
            key = ScanRewardsReferenceStore.Key(
              dsoParty = dsoParty,
              synchronizerId = synchronizerId,
            ),
            storage,
            loggerFactory,
            retryProvider,
            migrationInfo,
            participantId,
            config.automation.ingestion,
            config.parameters.defaultLimit,
          )
          automation.registerRewardsReferenceStoreIngestion(rewardsStore)
          Some(rewardsStore)
        } else None
      verdictAutomation = new ScanVerdictAutomationService(
        config,
        syncNodes,
        clock,
        retryProvider,
        loggerFactory,
        nodeMetrics.grpcClientMetrics,
        scanVerdictStore,
        migrationInfo.currentMigrationId,
        synchronizerId,
        nodeMetrics.verdictIngestion,
        rewardsReferenceStoreO,
      )
      scanHandler = new HttpScanHandler(
        serviceUserPrimaryParty,
        config.svUser,
        config.spliceInstanceNames,
        participantAdminConnection,
        syncService,
        automation,
        updateHistory,
        appRewardsStoreO,
        appActivityRecordStoreO,
        acsSnapshotStore,
        scanEventStore,
        bulkStorage,
        dsoAnsResolver,
        config.miningRoundsCacheTimeToLiveOverride,
        config.enableForcedAcsSnapshots,
        config.serveAppActivityRecordsAndTraffic,
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
        config.bulkStorage.s3.map(S3BucketConnection(_, loggerFactory))
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
        loggerFactory,
      )
      httpRateLimiter = new HttpRateLimiter(
        config.parameters.rateLimiting,
        nodeMetrics.openTelemetryMetricsFactory,
        loggerFactory.getTracedLogger(classOf[HttpRateLimiter]),
      )
      route = cors(
        CorsSettings(ac).withExposedHeaders(Seq("traceparent"))
      ) {
        withTraceContext { traceContext =>
          {
            def buildRouteForOperation(operation: String, httpService: String) = {
              nodeMetrics.httpServerMetrics
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
              TokenStandardTransferInstructionResource.routes(
                tokenStandardTransferInstructionHandler,
                buildRouteForOperation(_, "token_standard_transfer_instruction"),
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
        verdictAutomation,
        scanEventStore,
        loggerFactory.getTracedLogger(ScanApp.State.getClass),
        timeouts,
        bftSequencersWithAdminConnections.map(_._1),
        Seq(httpRateLimiter),
      )
    }
  }
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
      bulkStorage: BulkStorage,
      verdictAutomation: ScanVerdictAutomationService,
      eventStore: ScanEventStore,
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
      bftSequencersAdminConnections: Seq[SequencerAdminConnection],
      cleanups: Seq[AutoCloseable],
  ) extends AutoCloseable
      with HasHealth {
    override def isHealthy: Boolean =
      storage.isActive && automation.isHealthy && verdictAutomation.isHealthy

    override def close(): Unit = {
      LifeCycle.close(bftSequencersAdminConnections*)(logger)
      LifeCycle.close(cleanups*)(logger)
      LifeCycle.close(
        bulkStorage,
        automation,
        verdictAutomation,
        store,
        storage,
        synchronizerNodes.current,
        participantAdminConnection,
      )(logger)
      synchronizerNodes.successor.foreach(
        LifeCycle.close(_)(logger)
      )
    }
  }
}
