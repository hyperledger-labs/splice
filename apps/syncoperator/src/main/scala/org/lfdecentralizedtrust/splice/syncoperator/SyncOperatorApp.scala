// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.syncoperator

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.LifeCycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.{TraceContext, TracerProvider}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  MediatorAdminConnection,
  Node,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryFor,
  SequencerAdminConnection,
  SpliceLedgerClient,
  SynchronizerNode,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.ScanConnection
import org.lfdecentralizedtrust.splice.syncoperator.automation.SyncOperatorAutomationService
import org.lfdecentralizedtrust.splice.syncoperator.config.{
  SyncOperatorAppBackendConfig,
  SyncOperatorSynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.syncoperator.metrics.SyncOperatorAppMetrics
import org.lfdecentralizedtrust.splice.syncoperator.store.SyncOperatorStore
import org.lfdecentralizedtrust.splice.util.HasHealth

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class representing a sync operator app instance.
  *
  * Ingests the traffic purchases made for the synchronizer it operates and grants them on that
  * synchronizer's sequencer.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SyncOperatorApp(
    override val name: InstanceName,
    val config: SyncOperatorAppBackendConfig,
    val appParameters: SharedSpliceAppParameters,
    storage: DbStorage,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    metrics: SyncOperatorAppMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends Node[SyncOperatorApp.State, Unit](
      config.operatorUser,
      config.participantClient,
      appParameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      metrics,
    ) {

  override lazy val ports = Map("admin" -> config.adminApi.port)

  override def preInitializeAfterLedgerConnection(
      connection: BaseLedgerConnection,
      ledgerClient: SpliceLedgerClient,
  )(implicit traceContext: TraceContext): Future[Unit] = Future.unit

  override def initialize(
      ledgerClient: SpliceLedgerClient,
      partyId: PartyId,
      preInitializeState: Unit,
  )(implicit traceContext: TraceContext): Future[SyncOperatorApp.State] = {
    for {
      scanConnection <- appInitStep(s"Get scan connection") {
        ScanConnection.singleCached(
          ledgerClient,
          config.scanClient,
          appParameters.upgradesConfig,
          clock,
          retryProvider,
          loggerFactory,
        )
      }
      participantAdminConnection = new ParticipantAdminConnection(
        config.participantClient.adminApi,
        appParameters.loggingConfig.api,
        loggerFactory,
        metrics.grpcClientMetrics,
        retryProvider,
      )
      participantId <- appInitStep("Get participant id") {
        participantAdminConnection.getParticipantId()
      }
      // Scan may still be initializing when this app starts, so wait for it rather than failing.
      dsoParty <- appInitStep("Get DSO party id") { scanConnection.getDsoPartyIdWithRetries() }
      synchronizerNodes = SynchronizerNode.LocalSynchronizerNodes(
        current = synchronizerNode(config.synchronizerNodes.current),
        successor = config.synchronizerNodes.successor.map(synchronizerNode),
        legacy = None,
        additionalLegacy = Seq.empty,
      )
      synchronizerNodeService = new SynchronizerNodeService(
        synchronizerNodes,
        config.parameters.spliceCachingConfigs.physicalSynchronizerExpiration,
        retryProvider,
        loggerFactory,
      )
      sequencerAdminConnection = synchronizerNodes.current.sequencerAdminConnection
      synchronizerId <- appInitStep("Get the synchronizer id from the sequencer") {
        servedSynchronizerId(sequencerAdminConnection)
      }
      _ <- appInitStep("Check the configured synchronizer has not already been upgraded") {
        requireCurrentNodeIsLive(synchronizerNodes)
      }
      _ <- appInitStep("Check the synchronizer runs traffic control") {
        requireTrafficControl(sequencerAdminConnection, synchronizerId)
      }
      storeKey = SyncOperatorStore.Key(
        operatorParty = partyId,
        dsoParty = dsoParty,
        synchronizerId = synchronizerId,
      )
      store = SyncOperatorStore(
        storeKey,
        storage,
        loggerFactory,
        retryProvider,
        // MIGRATION_ID is frozen network-wide and logical synchronizer upgrades carry a serial id
        // instead, so the store's partition never has to move.
        0L,
        participantId,
        config.automation.ingestion,
        config.parameters.defaultLimit,
      )
      globalSynchronizerId <- appInitStep("Get the global synchronizer id") {
        scanConnection.getAmuletRulesDomain()(traceContext)
      }
      readOnlyLedgerConnection = ledgerClient
        .readOnlyConnection(
          this.getClass.getSimpleName,
          loggerFactory,
        )
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        globalSynchronizerId,
        readOnlyLedgerConnection,
        loggerFactory,
      )
      automation = new SyncOperatorAutomationService(
        config.automation,
        clock,
        store,
        storage,
        ledgerClient,
        retryProvider,
        config.parameters,
        synchronizerNodeService,
        scanConnection,
        config.lsu,
        config.lsuDumpPath,
        config.trafficBalanceReconciliationDelay,
        loggerFactory,
        packageVersionSupport,
      )
    } yield {
      SyncOperatorApp.State(
        automation,
        storage,
        store,
        scanConnection,
        participantAdminConnection,
        synchronizerNodes,
        loggerFactory.getTracedLogger(SyncOperatorApp.State.getClass),
        timeouts,
      )
    }
  }

  private def synchronizerNode(
      nodeConfig: SyncOperatorSynchronizerNodeConfig
  ): SyncOperatorSynchronizerNode =
    new SyncOperatorSynchronizerNode(
      new SequencerAdminConnection(
        nodeConfig.sequencer.adminApi,
        appParameters.loggingConfig.api,
        loggerFactory,
        metrics.grpcClientMetrics,
        retryProvider,
      ),
      nodeConfig.mediator.map(mediator =>
        new MediatorAdminConnection(
          mediator.adminApi,
          appParameters.loggingConfig.api,
          loggerFactory,
          metrics.grpcClientMetrics,
          retryProvider,
        )
      ),
      nodeConfig,
      loggerFactory.getTracedLogger(classOf[SyncOperatorSynchronizerNode]),
    )

  /** An upgraded node still reports the same logical synchronizer id, so without a successor to
    * switch to this app would grant traffic on a synchronizer nobody is connected to. The
    * announcement alone is enough to refuse: it is only ever published with a successor configured.
    */
  private def requireCurrentNodeIsLive(
      nodes: SynchronizerNode.LocalSynchronizerNodes[SyncOperatorSynchronizerNode]
  )(implicit traceContext: TraceContext): Future[Unit] =
    if (nodes.successor.isDefined) Future.unit
    else
      for {
        psid <- nodes.current.sequencerAdminConnection.getPhysicalSynchronizerId()
        announcements <- nodes.current.sequencerAdminConnection
          .listLsuAnnouncements(psid.logical)
        superseded = announcements.filter(
          _.mapping.successorSynchronizerId.serial > psid.serial
        )
        _ <- superseded.headOption.fold(Future.unit) { announcement =>
          Future.failed(
            Status.FAILED_PRECONDITION
              .withDescription(
                s"The configured synchronizer node is at $psid but is upgraded to " +
                  s"${announcement.mapping.successorSynchronizerId} at " +
                  s"${announcement.mapping.upgradeTime}. Set synchronizer-nodes.successor, or " +
                  "point synchronizer-nodes.current at the successor once the upgrade has landed."
              )
              .asRuntimeException()
          )
        }
      } yield ()

  /** This app adjusts traffic control but never turns it on, which would strand members that have
    * no traffic yet. Waits, since the synchronizer's bootstrap may turn it on after the sequencer
    * is up.
    */
  private def requireTrafficControl(
      sequencerAdminConnection: SequencerAdminConnection,
      synchronizerId: SynchronizerId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "sync_operator_traffic_control",
      s"synchronizer $synchronizerId runs traffic control",
      sequencerAdminConnection
        .getSynchronizerParametersState(synchronizerId)
        .map(_.mapping.parameters.trafficControl)
        .flatMap {
          case Some(_) => Future.unit
          case None =>
            Future.failed(
              Status.FAILED_PRECONDITION
                .withDescription(
                  s"Synchronizer $synchronizerId does not run traffic control. Enable it on the " +
                    "synchronizer before starting the sync operator."
                )
                .asRuntimeException()
            )
        },
      logger,
    )

  /** The synchronizer the configured sequencer serves. Waits while it is still initializing. */
  private def servedSynchronizerId(
      sequencerAdminConnection: SequencerAdminConnection
  )(implicit traceContext: TraceContext): Future[SynchronizerId] =
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "sync_operator_served_synchronizer_id",
      "the sequencer reports the synchronizer it serves",
      sequencerAdminConnection.getStatus.map(
        _.successOption
          .map(_.synchronizerId.logical)
          .getOrElse(
            throw Status.UNAVAILABLE
              .withDescription("Sequencer is not yet initialized")
              .asRuntimeException()
          )
      ),
      logger,
    )

  protected[this] override def automationServices(st: SyncOperatorApp.State) =
    Seq(st.automation)
}

object SyncOperatorApp {
  case class State(
      automation: SyncOperatorAutomationService,
      storage: DbStorage,
      store: SyncOperatorStore,
      scanConnection: ScanConnection,
      participantAdminConnection: ParticipantAdminConnection,
      synchronizerNodes: SynchronizerNode.LocalSynchronizerNodes[SyncOperatorSynchronizerNode],
      logger: TracedLogger,
      timeouts: ProcessingTimeout,
  ) extends AutoCloseable
      with HasHealth {

    /** The configured current node's sequencer. Automation follows the successor across an
      * upgrade, see `SynchronizerNodeService`.
      */
    def sequencerAdminConnection: SequencerAdminConnection =
      synchronizerNodes.current.sequencerAdminConnection

    override def isHealthy: Boolean = storage.isActive

    override def close(): Unit = {
      val instances =
        Seq[AutoCloseable](
          automation,
          storage,
          store,
          scanConnection,
          participantAdminConnection,
          synchronizerNodes.current,
        ) ++ synchronizerNodes.successor.toList
      LifeCycle.close(instances*)(logger)
    }
  }
}
