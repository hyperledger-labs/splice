package com.daml.network.sv.onboarding.domainmigration

import cats.syntax.either.*
import com.daml.network.environment.{
  CNLedgerClient,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.http.v0.definitions as http
import com.daml.network.identities.NodeIdentitiesDump
import com.daml.network.migration.DomainDataRestorer
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.migration.{DomainMigrationDump, DomainNodeIdentities}
import com.daml.network.sv.onboarding.{NodeInitializerUtil, SetupUtil, SvcPartyHosting}
import com.daml.network.sv.onboarding.domainmigration.DomainMigrationInitializer.{
  loadDomainMigrationDump,
  DomainNodeInitializer,
}
import com.daml.network.sv.onboarding.joining.JoiningNodeInitializer
import com.daml.network.sv.store.{SvStore, SvSvcStore, SvSvStore}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import java.io.FileNotFoundException
import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Container for the methods required by the SvApp to initialize the SV node of upgraded domain. */
class DomainMigrationInitializer(
    localDomainNode: LocalDomainNode,
    domainMigrationConfig: SvOnboardingConfig.DomainMigration,
    participantId: ParticipantId,
    override protected val config: SvAppBackendConfig,
    override protected val cometBftNode: Option[CometBftNode],
    override protected val ledgerClient: CNLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val storage: Storage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    joiningNodeInitializer: JoiningNodeInitializer,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
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
    loggerFactory,
  )

  def migrateDomain(): Future[
    (
        DomainId,
        SvcPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvSvcStore,
        SvSvcAutomationService,
    )
  ] = {
    val migrationDump = loadDomainMigrationDump(domainMigrationConfig.dumpFilePath)
    if (config.domainMigrationId != migrationDump.migrationId)
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Migration id from the dump does not match the configured migration id in ths SV. Please check if the SV app is configured with the correct migration id"
        )
        .asRuntimeException()
    val storeKey =
      SvStore.Key(migrationDump.nodeIdentities.svPartyId, migrationDump.nodeIdentities.svcPartyId)
    val svcPartyHosting = newSvcPartyHosting(storeKey)
    for {
      _ <- migrateToNewDomainNode(migrationDump)
      globalDomainId = migrationDump.nodeIdentities.domainId
      _ <- svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
        globalDomainId,
        ParticipantId(migrationDump.nodeIdentities.participant.id.uid),
        RetryFor.Automation,
      )
      _ = logger.info(
        s"SVC party hosting is replicated on the new global domain"
      )
      _ <- readOnlyConnection.ensureUserHasPrimaryParty(
        config.ledgerApiUser,
        storeKey.svParty,
      )
      svStore = newSvStore(storeKey, config.domainMigrationId, participantId)
      svcStore = newSvcStore(svStore.key, config.domainMigrationId, participantId)
      svAutomation = newSvSvAutomationService(
        svStore,
        svcStore,
        ledgerClient,
      )
      _ <- SetupUtil
        .grantSvUserRightReadAsSvc(
          svAutomation.connection,
          config.ledgerApiUser,
          svStore.key.svcParty,
        )
      svcAutomationService =
        newSvSvcAutomationService(svStore, svcStore, Some(localDomainNode))
      _ <- joiningNodeInitializer.onboard(
        globalDomainId,
        svcAutomationService,
        svAutomation,
      )
    } yield (
      globalDomainId,
      svcPartyHosting,
      svStore,
      svAutomation,
      svcStore,
      svcAutomationService,
    )
  }

  private def migrateToNewDomainNode(
      domainMigrationDump: DomainMigrationDump
  ): Future[Unit] = {
    val domainAlias = domainMigrationDump.nodeIdentities.domainAlias
    for {
      _ <- initializeDomainNode(
        domainMigrationDump.nodeIdentities,
        domainMigrationDump.domainDataSnapshot.genesisState.getOrElse(
          sys.error("Domain nodes cannot be initialized without a genesis dump")
        ),
        domainMigrationDump.domainDataSnapshot.vettedPackages.getOrElse(
          sys.error("Domain nodes cannot be initialized without vetted packages snapshot")
        ),
      )
      _ <- domainDataRestorer.connectDomainAndRestoreData(
        readOnlyConnection,
        config.ledgerApiUser,
        domainAlias,
        domainMigrationDump.nodeIdentities.domainId,
        SequencerConnections.single(localDomainNode.sequencerConnection),
        domainMigrationDump.domainDataSnapshot.dars,
        domainMigrationDump.domainDataSnapshot.acsSnapshot,
      )
      _ <- participantAdminConnection
        .ensureDomainParameters(
          domainMigrationDump.nodeIdentities.domainId,
          // TODO(#8761) hard code for now
          _.tryUpdate(confirmationRequestsMaxRate =
            DynamicDomainParameters.defaultConfirmationRequestsMaxRate
          ),
          signedBy = domainMigrationDump.nodeIdentities.participant.id.uid.namespace.fingerprint,
        )
      _ = logger.info("resumed domain")
    } yield {}
  }

  private def initializeDomainNode(
      nodeIdentities: DomainNodeIdentities,
      genesisState: ByteString,
      vettedPackages: GenericStoredTopologyTransactionsX,
  ): Future[Unit] = {
    val domainNodeInitiaizer = DomainNodeInitializer(
      localDomainNode,
      clock,
      loggerFactory,
      retryProvider,
    )
    logger.info("Init new domain nodes from snapshot")
    for {
      _ <- initializeSequencer(
        domainNodeInitiaizer,
        nodeIdentities.sequencer,
        genesisState,
      )
      _ = logger.info(
        s"Adding ${vettedPackages.result.size} vetted packages topology transactions after sequencer initialization"
      )
      _ <- MonadUtil.sequentialTraverse_(vettedPackages.result.zipWithIndex) { case (tx, index) =>
        logger.info(s"Replaying vetted packages $index / ${vettedPackages.result.size}")
        localDomainNode.sequencerAdminConnection
          .addVettedPackageTransactionAndEnsurePersisted(
            nodeIdentities.domainId,
            tx.transaction,
          )
      }
      _ <- initializeMediator(
        nodeIdentities.domainId,
        domainNodeInitiaizer,
        nodeIdentities.mediator,
      )
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "mediator_up_to_date",
        "mediator synced topology",
        for {
          sequencerTopology <- localDomainNode.sequencerAdminConnection.listAllTransactions(
            Some(TopologyStoreId.DomainStore(nodeIdentities.domainId))
          )
          mediatorTopology <- localDomainNode.mediatorAdminConnection.listAllTransactions(
            Some(TopologyStoreId.DomainStore(nodeIdentities.domainId))
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
      domainNodeInitializer: DomainNodeInitializer,
      identity: NodeIdentitiesDump,
      genesisState: ByteString,
  ) = {
    domainNodeInitializer.domainNode.sequencerAdminConnection
      .isNodeInitialized()
      .flatMap { isInitialized =>
        if (isInitialized) {
          logger.info(s"Sequencer is already initialized with id ${identity.id}")
          Future.unit
        } else {
          logger.info(s"Sequencer is not initialized, initializing from dump")
          for {
            _ <- domainNodeInitializer.sequencerInitializer.initializeFromDump(identity)
            _ = logger.info(
              s"Restoring sequencer topology from genesis state"
            )
            _ <- localDomainNode.sequencerAdminConnection
              .initializeFromGenesisState(
                genesisState,
                localDomainNode.staticDomainParameters,
              )
            _ <- retryProvider.waitUntil(
              RetryFor.ClientCalls,
              "init_sequencer",
              "sequencer is initialized",
              localDomainNode.sequencerAdminConnection.isNodeInitialized().map { initialized =>
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
          "sequencer_initialized",
          "sequencer is initialized with restored id",
          localDomainNode.sequencerAdminConnection.getSequencerId.map { id =>
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

  private def initializeMediator(
      domainId: DomainId,
      domainNodeInitiaizer: DomainNodeInitializer,
      identity: NodeIdentitiesDump,
  ) = {
    for {
      isMediatorInitialized <- domainNodeInitiaizer.domainNode.mediatorAdminConnection
        .isNodeInitialized()
      _ <-
        if (isMediatorInitialized) {
          logger.info(s"Mediator is already initialized with id ${identity.id}")
          Future.unit
        } else
          domainNodeInitiaizer.mediatorInitializer
            .initializeFromDump(identity)
            .flatMap(_ =>
              localDomainNode.mediatorAdminConnection
                .initialize(
                  domainId,
                  localDomainNode.staticDomainParameters,
                  localDomainNode.sequencerConnection,
                )
            )
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
        "init_mediator",
        "mediator is initialized as expected",
        localDomainNode.mediatorAdminConnection.getMediatorId.map { id =>
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

  case class DomainNodeInitializer(
      domainNode: LocalDomainNode,
      clock: Clock,
      logger: NamedLoggerFactory,
      retryProvider: RetryProvider,
  ) {
    val sequencerInitializer = new NodeInitializer(
      domainNode.sequencerAdminConnection,
      retryProvider,
      logger,
    )

    val mediatorInitializer = new NodeInitializer(
      domainNode.mediatorAdminConnection,
      retryProvider,
      logger,
    )

  }
}
