// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration

import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.config.{SpliceInstanceNamesConfig, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  MediatorAdminConnection,
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
  StatusAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import org.lfdecentralizedtrust.splice.migration.{
  DomainDataRestorer,
  DomainMigrationInfo,
  MigrationTimeInfo,
  ParticipantUsersDataRestorer,
}
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import org.lfdecentralizedtrust.splice.sv.cometbft.{CometBftClient, CometBftNode}
import org.lfdecentralizedtrust.splice.sv.config.{
  SvAppBackendConfig,
  SvCometBftConfig,
  SvOnboardingConfig,
}
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.{
  DsoPartyHosting,
  NodeInitializerUtil,
  SetupUtil,
  SynchronizerNodeInitializer,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration.DomainMigrationInitializer.loadDomainMigrationDump
import org.lfdecentralizedtrust.splice.sv.onboarding.joining.JoiningNodeInitializer
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{SynchronizerMigrationUtil, TemplateJsonDecoder}
import com.digitalasset.canton.admin.api.client.data.{NodeStatus, WaitingForInitialization}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.io.FileNotFoundException
import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Container for the methods required by the SvApp to initialize the SV node of upgraded domain. */
class DomainMigrationInitializer(
    localSynchronizerNode: LocalSynchronizerNode,
    domainMigrationConfig: SvOnboardingConfig.DomainMigration,
    participantId: ParticipantId,
    cometBftConfig: Option[SvCometBftConfig],
    cometBftClient: Option[CometBftClient],
    override protected val config: SvAppBackendConfig,
    upgradesConfig: UpgradesConfig,
    override protected val cometBftNode: Option[CometBftNode],
    override protected val ledgerClient: SpliceLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val domainTimeSync: DomainTimeSynchronization,
    override protected val domainUnpausedSync: DomainUnpausedSynchronization,
    override protected val storage: Storage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    override protected val spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    newJoiningNodeInitializer: (
        Option[SvOnboardingConfig.JoinWithKey],
        Option[CometBftNode],
    ) => JoiningNodeInitializer,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NodeInitializerUtil {

  private val readOnlyConnection = ledgerClient.readOnlyConnection(
    this.getClass.getSimpleName,
    loggerFactory,
  )
  private val domainDataRestorer = new DomainDataRestorer(
    participantAdminConnection,
    config.timeTrackerMinObservationDuration,
    config.timeTrackerObservationLatency,
    loggerFactory,
  )

  def migrateDomain(): Future[
    (
        SynchronizerId,
        DsoPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvDsoStore,
        SvDsoAutomationService,
    )
  ] = {
    val migrationDump = loadDomainMigrationDump(domainMigrationConfig.dumpFilePath)
    if (config.domainMigrationId != migrationDump.migrationId)
      throw Status.INVALID_ARGUMENT
        .withDescription(
          s"Migration id from the dump ${migrationDump.migrationId} does not match the configured migration id in ths SV ${config.domainMigrationId}. Please check if the SV app is configured with the correct migration id"
        )
        .asRuntimeException()
    val storeKey =
      SvStore.Key(migrationDump.nodeIdentities.svPartyId, migrationDump.nodeIdentities.dsoPartyId)
    val dsoPartyHosting = newDsoPartyHosting(storeKey.dsoParty)
    for {
      _ <- migrateToNewSynchronizerNode(migrationDump)
      decentralizedSynchronizerId = migrationDump.nodeIdentities.synchronizerId
      _ <- dsoPartyHosting.waitForDsoPartyToParticipantAuthorization(
        decentralizedSynchronizerId,
        ParticipantId(migrationDump.nodeIdentities.participant.id.uid),
        RetryFor.Automation,
      )
      _ = logger.info(
        s"DSO party hosting is replicated on the new global domain"
      )
      _ <- readOnlyConnection.ensureUserHasPrimaryParty(
        config.ledgerApiUser,
        storeKey.svParty,
      )
      // User metadata gets reset on domain migrations so to guard against the onboarding config being set back to sv1 one
      // and avoiding premature package uploads we set the metadata field here as well.
      _ <- readOnlyConnection.ensureUserMetadataAnnotation(
        config.ledgerApiUser,
        BaseLedgerConnection.SV1_INITIAL_PACKAGE_UPLOAD_METADATA_KEY,
        "true",
        RetryFor.WaitingOnInitDependency,
      )
      _ <- SetupUtil.ensureSvNameMetadataAnnotation(
        readOnlyConnection,
        config,
        domainMigrationConfig.name,
      )
      migrationInfo =
        DomainMigrationInfo(
          currentMigrationId = config.domainMigrationId,
          migrationTimeInfo = Some(
            MigrationTimeInfo(
              CantonTimestamp.assertFromInstant(migrationDump.domainDataSnapshot.acsTimestamp),
              synchronizerWasPaused = migrationDump.domainDataSnapshot.synchronizerWasPaused,
            )
          ),
        )
      svStore = newSvStore(storeKey, migrationInfo, participantId)
      dsoStore = newDsoStore(svStore.key, migrationInfo, participantId)
      svAutomation = newSvSvAutomationService(
        svStore,
        dsoStore,
        ledgerClient,
        participantAdminConnection,
        Some(localSynchronizerNode),
      )
      connection = svAutomation.connection(SpliceLedgerConnectionPriority.Low)
      _ <- SetupUtil
        .grantSvUserRightActAsDso(
          connection,
          config.ledgerApiUser,
          svStore.key.dsoParty,
        )
      _ <- DomainMigrationInfo.saveToUserMetadata(
        connection,
        config.ledgerApiUser,
        migrationInfo,
      )
      newCometBftNode <- SvUtil.mapToCometBftNode(
        cometBftClient,
        cometBftConfig,
        participantAdminConnection,
        logger,
        loggerFactory,
        retryProvider,
      )
      packageVersionSupport = PackageVersionSupport.createPackageVersionSupport(
        decentralizedSynchronizerId,
        connection,
        loggerFactory,
      )
      dsoAutomationService =
        new SvDsoAutomationService(
          clock,
          domainTimeSync,
          domainUnpausedSync,
          config,
          svStore,
          dsoStore,
          ledgerClient,
          participantAdminConnection,
          retryProvider,
          newCometBftNode,
          Some(localSynchronizerNode),
          upgradesConfig,
          spliceInstanceNamesConfig,
          loggerFactory,
          packageVersionSupport,
        )
      // We register the traffic triggers earlier for domain migrations to ensure that SV nodes obtain
      // unlimited traffic and prevent lock-out issues due to lack of traffic (see #13868)
      _ = dsoAutomationService.registerTrafficReconciliationTriggers()
      _ <- ensureCometBftGovernanceKeysAreSet(
        cometBftNode,
        svStore.key.svParty,
        dsoStore,
        dsoAutomationService,
      )
      _ <- rotateGenesisGovernanceKeyForSV1(newCometBftNode, domainMigrationConfig.name)
      _ <- newJoiningNodeInitializer(None, newCometBftNode).onboard(
        decentralizedSynchronizerId,
        dsoAutomationService,
        svAutomation,
        skipTrafficReconciliationTriggers = true,
      )
      _ <- new ParticipantUsersDataRestorer(
        connection,
        loggerFactory,
      ).restoreParticipantUsersData(migrationDump.participantUsers)
      _ <- establishInitialRound(
        readOnlyConnection,
        upgradesConfig,
        packageVersionSupport,
        svStore.key.svParty,
      )
    } yield (
      decentralizedSynchronizerId,
      dsoPartyHosting,
      svStore,
      svAutomation,
      dsoStore,
      dsoAutomationService,
    )
  }

  private def migrateToNewSynchronizerNode(
      domainMigrationDump: DomainMigrationDump
  ): Future[Unit] = {
    val synchronizerAlias = domainMigrationDump.nodeIdentities.synchronizerAlias
    for {
      _ <- initializeSynchronizerNode(
        domainMigrationDump.nodeIdentities,
        domainMigrationDump.domainDataSnapshot.genesisState.getOrElse(
          sys.error("Domain nodes cannot be initialized without a genesis dump")
        ),
      )
      _ <- domainDataRestorer.connectDomainAndRestoreData(
        synchronizerAlias,
        domainMigrationDump.nodeIdentities.synchronizerId,
        SequencerConnections.single(localSynchronizerNode.sequencerConnection),
        domainMigrationDump.domainDataSnapshot.dars,
        domainMigrationDump.domainDataSnapshot.acsSnapshot,
      )
      _ <- SynchronizerMigrationUtil.ensureSynchronizerIsUnpaused(
        participantAdminConnection,
        domainMigrationDump.nodeIdentities.synchronizerId,
      )
      _ = logger.info("resumed domain")
    } yield {}
  }

  private val mediatorAdminConnection: MediatorAdminConnection =
    localSynchronizerNode.mediatorAdminConnection

  private def initializeSynchronizerNode(
      nodeIdentities: SynchronizerNodeIdentities,
      genesisState: ByteString,
  ): Future[Unit] = {
    val synchronizerNodeInitiaizer = SynchronizerNodeInitializer(
      localSynchronizerNode,
      clock,
      loggerFactory,
      retryProvider,
    )
    logger.info("Init new domain nodes from snapshot")
    for {
      _ <- initializeSequencer(
        synchronizerNodeInitiaizer,
        nodeIdentities.sequencer,
        genesisState,
      )
      _ <- initializeMediator(
        nodeIdentities.synchronizerId,
        synchronizerNodeInitiaizer,
        nodeIdentities.mediator,
      )
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "mediator_up_to_date",
        "mediator synced topology",
        for {
          sequencerTopology <- localSynchronizerNode.sequencerAdminConnection.listAllTransactions(
            TopologyStoreId.SynchronizerStore(nodeIdentities.synchronizerId)
          )
          mediatorTopology <- mediatorAdminConnection.listAllTransactions(
            TopologyStoreId.SynchronizerStore(nodeIdentities.synchronizerId)
          )
        } yield {
          if (sequencerTopology.size != mediatorTopology.size) {
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"""Mediator topology is not synchronized.
                   |Sequencer topology size [${sequencerTopology.size}], mediator topology size [${mediatorTopology.size}].""".stripMargin
              )
              .asRuntimeException()
          }
        },
        loggerFactory.getTracedLogger(getClass),
      )
    } yield {}
  }

  private def initializeSequencer(
      synchronizerNodeInitializer: SynchronizerNodeInitializer,
      identity: NodeIdentitiesDump,
      genesisState: ByteString,
  ) = {
    synchronizerNodeInitializer.synchronizerNode.sequencerAdminConnection
      .isNodeInitialized()
      .flatMap { isInitialized =>
        if (isInitialized) {
          logger.info(s"Sequencer is already initialized with id ${identity.id}")
          Future.unit
        } else {
          logger.info(s"Sequencer is not initialized, initializing from dump")
          for {
            _ <- synchronizerNodeInitializer.sequencerInitializer.initializeFromDump(identity)
            _ = logger.info(
              s"Restoring sequencer topology from genesis state"
            )
            _ = waitForNodeReadyToInitialize(
              localSynchronizerNode.sequencerAdminConnection,
              identity,
            )
            _ <- retryProvider.retry(
              RetryFor.ClientCalls,
              "init_sequencer_genesis",
              s"Initialize sequencer ${identity.id} from genesis state",
              localSynchronizerNode.sequencerAdminConnection
                .initializeFromGenesisState(
                  genesisState,
                  localSynchronizerNode.staticDomainParameters,
                ),
              logger,
            )
            _ <- retryProvider.waitUntil(
              RetryFor.ClientCalls,
              "sequencer_initialized",
              "sequencer is initialized",
              localSynchronizerNode.sequencerAdminConnection.isNodeInitialized().map {
                initialized =>
                  if (!initialized) {
                    throw Status.FAILED_PRECONDITION
                      .withDescription("Sequencer is not initialized")
                      .asRuntimeException()
                  }
              },
              loggerFactory.getTracedLogger(getClass),
            )
          } yield {}
        }
      }
      .flatMap { _ =>
        retryProvider.waitUntil(
          RetryFor.ClientCalls,
          "sequencer_initialized_id",
          "sequencer is initialized with restored id",
          localSynchronizerNode.sequencerAdminConnection.getSequencerId.map { id =>
            if (id != identity.id) {
              throw Status.FAILED_PRECONDITION
                .withDescription("Sequencer is not initialized with dump id")
                .asRuntimeException()
            }
          },
          loggerFactory.getTracedLogger(getClass),
        )
      }
  }

  private def waitForNodeReadyToInitialize(
      connection: StatusAdminConnection,
      identityDump: NodeIdentitiesDump,
  ) = {
    retryProvider.waitUntil(
      RetryFor.WaitingOnInitDependency,
      "sequencer_genesis",
      s"Sequencer ${identityDump.id} is ready to be initialized with the genesis state",
      connection.getStatus.map {
        case NodeStatus.Failure(msg) =>
          throw Status.FAILED_PRECONDITION
            .withDescription("Sequencer is in failure state: " + msg)
            .asRuntimeException()
        case NodeStatus.NotInitialized(_, Some(WaitingForInitialization)) =>
          logger.info(
            "Sequencer is in waiting for initialization state, proceeding with genesis import"
          )
          ()
        case NodeStatus.NotInitialized(_, other) =>
          throw Status.FAILED_PRECONDITION
            .withDescription(
              s"Sequencer is waiting for $other, we can initialize it only when it's ready."
            )
            .asRuntimeException()
        case NodeStatus.Success(_) => ()
      },
      logger,
    )
  }

  private def initializeMediator(
      synchronizerId: SynchronizerId,
      synchronizerNodeInitiaizer: SynchronizerNodeInitializer,
      identity: NodeIdentitiesDump,
  ) = {
    for {
      isMediatorInitialized <- synchronizerNodeInitiaizer.synchronizerNode.mediatorAdminConnection
        .isNodeInitialized()
      _ <-
        if (isMediatorInitialized) {
          logger.info(s"Mediator is already initialized with id ${identity.id}")
          Future.unit
        } else {
          for {
            _ <-
              synchronizerNodeInitiaizer.mediatorInitializer
                .initializeFromDump(identity)
            _ <- waitForNodeReadyToInitialize(mediatorAdminConnection, identity)
            _ <- retryProvider.retry(
              RetryFor.ClientCalls,
              "init_mediator",
              s"Initialize the mediator ${identity.id}",
              mediatorAdminConnection
                .initialize(
                  synchronizerId,
                  localSynchronizerNode.sequencerConnection,
                  localSynchronizerNode.mediatorSequencerAmplification,
                ),
              logger,
            )
          } yield ()
        }
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
        "init_mediator",
        "mediator is initialized as expected",
        mediatorAdminConnection.getMediatorId.map { id =>
          if (id != identity.id) {
            throw Status.INVALID_ARGUMENT
              .withDescription("Mediator is not initialized with dump id")
              .asRuntimeException()
          }
        },
        loggerFactory.getTracedLogger(getClass),
      )
    } yield {}
  }
}

object DomainMigrationInitializer {
  def loadDomainMigrationDump(
      path: Path
  ): DomainMigrationDump = {
    val dumpFile = better.files.File(path)
    if (!dumpFile.exists) {
      throw new FileNotFoundException(s"Failed to find domain migration dump file at $path")
    }
    val jsonString: String = dumpFile.contentAsString
    io.circe.parser
      .decode[http.GetDomainMigrationDumpResponse](
        jsonString
      )
      .leftMap(_.getMessage)
      .flatMap(DomainMigrationDump.fromHttp)
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to parse domain migration dump file: $err"
          ),
        result => result,
      )
  }
}
