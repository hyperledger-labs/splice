package com.daml.network.sv.onboarding.domainmigration

import com.daml.network.environment.{
  CNLedgerClient,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.http.v0.definitions as http
import com.daml.network.sv.{DomainMigrationDump, LocalDomainNode}
import com.daml.network.sv.automation.SvSvcAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SequencerPruningConfig, SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.{SetupUtil, SvcPartyHosting}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.util.{TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math.Ordered.orderingToOrdered
import cats.syntax.either.*
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.onboarding.domainmigration.DomainMigrationInitializer.{
  DomainNodeIdentities,
  DomainTopologyTransactions,
}
import com.daml.network.sv.DomainMigrationDump.DomainMigrationDumpNodeIdentities
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.processing.SequencedTime
import com.digitalasset.canton.topology.store.StoredTopologyTransactionX.GenericStoredTopologyTransactionX
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code
import com.digitalasset.canton.topology.transaction.{TopologyChangeOpX, TopologyMappingX}
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}

/** Container for the methods required by the SvApp to initialize the SV node of upgraded domain. */
class DomainMigrationInitializer(
    config: SvAppBackendConfig,
    domainMigrationConfig: SvOnboardingConfig.DomainMigration,
    cometBftNode: Option[CometBftNode],
    override protected val loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    clock: Clock,
    storage: Storage,
    localDomainNode: LocalDomainNode,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
    mat: Materializer,
    tc: TraceContext,
    tracer: Tracer,
) extends NamedLogging {
  private val readOnlyConnection = ledgerClient.readOnlyConnection(
    this.getClass.getSimpleName,
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
    for {
      migrationDump <- loadDomainMigrationDump(domainMigrationConfig.dumpFilePath)
      storeKey = SvStore.Key(migrationDump.svPartyId, migrationDump.svcPartyId)
      svcPartyHosting = newSvcPartyHosting(
        storeKey,
        participantAdminConnection,
      )
      _ <- migrateToNewDomainNode(migrationDump, svcPartyHosting)
      _ <- SetupUtil.setupSvParty(
        readOnlyConnection,
        config,
        participantAdminConnection,
        clock,
        retryProvider,
        loggerFactory,
      )
      svStore = newSvStore(storeKey)
      svcStore = newSvcStore(svStore.key)
      svAutomation = newSvSvAutomationService(
        svStore,
        svcStore,
        ledgerClient,
      )
      _ <- SetupUtil.grantSvUserRightReadAsSvc(
        svAutomation.connection,
        config.ledgerApiUser,
        svStore.key.svcParty,
      )
      _ <- SetupUtil.ensureSvcPartyMetadataAnnotation(
        svAutomation.connection,
        config,
        migrationDump.svcPartyId,
      )
      svcAutomation =
        newSvSvcAutomationService(
          svStore,
          svcStore,
          ledgerClient,
          cometBftNode,
          Some(localDomainNode),
        )

      globalDomain <- svStore.domains.waitForDomainConnection(config.domains.global.alias)
      _ <- svcStore.domains.waitForDomainConnection(config.domains.global.alias)
    } yield (
      globalDomain,
      svcPartyHosting,
      svStore,
      svAutomation,
      svcStore,
      svcAutomation,
    )
  }

  private def loadDomainMigrationDump(path: Path): Future[DomainMigrationDump] = Future {
    val jsonString: String = better.files.File(path).contentAsString
    (
      for {
        httpMigrationDump <- io.circe.parser
          .decode[http.GetDomainMigrationDumpResponse](
            jsonString
          )
          .leftMap(_.getMessage)
        migrationDump <- DomainMigrationDump.fromHttp(httpMigrationDump)
      } yield migrationDump
    )
      .fold(
        err =>
          throw new IllegalArgumentException(
            s"Failed to parse domain migration dump file: $err"
          ),
        result => result,
      )
  }

  private def migrateToNewDomainNode(
      domainMigrationDump: DomainMigrationDump,
      svcPartyHosting: SvcPartyHosting,
  ): Future[Unit] = {
    val domainTopologyTransactions = DomainTopologyTransactions(
      domainMigrationDump.topologySnapshot.result
    )
    for {
      _ <- initializeDomainNode(
        domainMigrationDump.domainId,
        domainMigrationDump.nodeIdentities,
        domainTopologyTransactions,
      )
      _ = logger.info("Registering and connecting to new domain")
      _ <- participantAdminConnection.registerDomain(
        DomainConnectionConfig(
          domainMigrationDump.domainAlias,
          domainId = Some(domainMigrationDump.domainId),
          sequencerConnections = SequencerConnections.single(localDomainNode.sequencerConnection),
          initializeFromTrustedDomain = true,
        )
      )
      _ <- svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
        domainMigrationDump.domainId,
        ParticipantId(domainMigrationDump.nodeIdentities.participant.id.uid),
      )
      _ = logger.info(
        s"SVC party hosting is replicated on the new global domain"
      )

      _ <- retryProvider.waitUntil(
        RetryFor.Automation,
        "participant caught up with the domain state topology",
        participantAdminConnection
          .getDomainParametersState(
            domainMigrationDump.domainId
          )
          .map { domainParameters =>
            if (
              domainParameters.base.serial < domainTopologyTransactions.lastDomainStateParametersState.transaction.transaction.serial
            ) {
              throw Status.FAILED_PRECONDITION
                .withDescription(
                  s"Domain state topology is not yet up to date with the dump. Dump state is ${domainTopologyTransactions.lastDomainStateParametersState.transaction.transaction} and current state is $domainParameters"
                )
                .asRuntimeException()
            }
          },
        loggerFactory.getTracedLogger(getClass),
      )

      _ <- participantAdminConnection
        .ensureDomainParameters(
          domainMigrationDump.domainId,
          // TODO(#8761) hard code for now
          _.tryUpdate(maxRatePerParticipant = NonNegativeInt.tryCreate(1000000)),
          signedBy = domainMigrationDump.nodeIdentities.participant.id.uid.namespace.fingerprint,
        )
      _ = logger.info("resumed domain")

      dars = domainMigrationDump.dars.map { dar =>
        UploadablePackage.fromByteString(dar.hash.toHexString, dar.content)
      }
      _ <- participantAdminConnection.uploadDarFiles(
        dars,
        RetryFor.WaitingOnInitDependency,
      )
      _ = logger.info("uploaded all dars to the participant.")

      _ <- participantAdminConnection.disconnectFromAllDomains()
      _ = logger.info("Disconnected from all domains")

      _ <- participantAdminConnection.uploadAcsSnapshot(domainMigrationDump.acsSnapshot)
      _ = logger.info("Acs snapshot is restored")

      _ <- participantAdminConnection.reconnectDomain(domainMigrationDump.domainAlias)
      _ = logger.info("domain is reconnected")

    } yield {}
  }

  private def initializeDomainNode(
      domainId: DomainId,
      nodeIdentities: DomainMigrationDumpNodeIdentities,
      domainTopologyTransactions: DomainTopologyTransactions,
  ): Future[Unit] = {
    val domainNodeIdentities: DomainNodeIdentities = DomainNodeIdentities(
      localDomainNode,
      clock,
      loggerFactory,
      retryProvider,
    )
    logger.info(s"Init new domain nodes from snapshot $domainTopologyTransactions")
    val sequencerIdentities = nodeIdentities.sequencer
    for {
      _ <- domainNodeIdentities.sequencerInitializer.initializeFromDump(sequencerIdentities)
      _ = logger.info(
        s"Restoring sequencer topology with sequencer transactions ${domainTopologyTransactions.sequencerInitTopologyTransactions}"
      )
      _ <- localDomainNode.sequencerAdminConnection
        .initialize(
          StoredTopologyTransactionsX(
            domainTopologyTransactions.sequencerInitTopologyTransactions
          ),
          localDomainNode.staticDomainParameters,
          None,
        )
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
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
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
        "sequencer is initialized with restored id",
        localDomainNode.sequencerAdminConnection.getSequencerId.map { id =>
          if (id != sequencerIdentities.id) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Sequencer is not initialized with dump id")
              .asRuntimeException()
          }
        },
        loggerFactory.getTracedLogger(getClass),
      )
      _ = logger.info(
        s"Adding topology transactions after sequencer initialization ${domainTopologyTransactions.topologyTransactionsToSubmit}"
      )
      _ <- MonadUtil.sequentialTraverse_(domainTopologyTransactions.topologyTransactionsToSubmit)(
        transactions =>
          localDomainNode.sequencerAdminConnection
            .addTopologyTransactionsAndEnsurePersisted(
              Some(domainId),
              Seq(transactions.transaction),
            )
      )
      mediatorIdentities = nodeIdentities.mediator
      _ <-
        domainNodeIdentities.mediatorInitializer.initializeFromDump(mediatorIdentities)
      _ <- localDomainNode.mediatorAdminConnection
        .initialize(
          domainId,
          localDomainNode.staticDomainParameters,
          localDomainNode.sequencerConnection,
        )
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
        "mediator is initialized as expected",
        localDomainNode.mediatorAdminConnection.getMediatorId.map { id =>
          if (id != mediatorIdentities.id) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Mediator is not initialized with dump id")
              .asRuntimeException()
          }
        },
        loggerFactory.getTracedLogger(getClass),
      )
      _ <- retryProvider.waitUntil(
        RetryFor.ClientCalls,
        "mediator synced topology",
        for {
          sequencerTopology <- localDomainNode.sequencerAdminConnection.listAllTransactions(
            Some(TopologyStoreId.DomainStore(domainId))
          )
          mediatorTopology <- localDomainNode.mediatorAdminConnection.listAllTransactions(
            Some(TopologyStoreId.DomainStore(domainId))
          )
        } yield {
          if (sequencerTopology.size != mediatorTopology.size) {
            throw Status.FAILED_PRECONDITION
              .withDescription("Mediator topology is not synchronized")
              .asRuntimeException()
          }
        },
        loggerFactory.getTracedLogger(getClass),
      )
    } yield {}
  }

  private def newSvStore(key: SvStore.Key) = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
  )

  private def newSvcStore(key: SvStore.Key) = {
    SvSvcStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
    )
  }

  private def newSvSvAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
  ) =
    new SvSvAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  private def newSvSvcAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
      cometBftNode: Option[CometBftNode],
      localDomainNode: Option[LocalDomainNode],
  ) =
    new SvSvcAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      localDomainNode.map(cfg =>
        LocalSequencerClientContext(
          cfg.sequencerAdminConnection,
          Some(
            LocalSequencerClientConfig(
              cfg.sequencerInternalConfig,
              config.domains.global.alias,
            )
          ),
          cfg.sequencerPruningConfig.map(pruningConfig =>
            SequencerPruningConfig(
              pruningConfig.pruningInterval,
              pruningConfig.retentionPeriod,
            )
          ),
        )
      ),
      loggerFactory,
    )

  private def newSvcPartyHosting(
      storeKey: SvStore.Key,
      participantAdminConnection: ParticipantAdminConnection,
  ) = new SvcPartyHosting(
    participantAdminConnection,
    storeKey.svcParty,
    retryProvider,
    loggerFactory,
  )
}

object DomainMigrationInitializer {
  case class DomainNodeIdentities(
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

  case class DomainTopologyTransactions(
      transactions: Seq[StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]]
  ) {

    private val sortedTransactions = transactions.sorted

    private val sequencerInitTransactions = Seq(
      // TODO(#8761) reduce the number of identity we import just to the nodes we actually need (sequencer most likely)
      TopologyMappingX.Code.NamespaceDelegationX,
      TopologyMappingX.Code.OwnerToKeyMappingX,
      TopologyMappingX.Code.IdentifierDelegationX,
      // start with all the sequencers authorized
      TopologyMappingX.Code.SequencerDomainStateX,
      // TODO(#8761) fix issue where replaying this fails with not authorized
      TopologyMappingX.Code.TrafficControlStateX,
    )

    val (sequencerInitTopologyTransactions, topologyTransactionsToSubmit) =
      sortedTransactions
        .map { transaction =>
          if (sequencerInitTransactions.contains(transaction.mapping.code)) {
            // reset sequenced time to ensure it's included in all the init calls
            // use same value as for the founding bootstrap
            transaction.copy(
              sequenced = SequencedTime(CantonTimestamp.MinValue.immediateSuccessor)
            )
          } else {
            if (
              isFoundingTopologyTransaction(transaction) && !sequencerInitTransactions.contains(
                transaction.mapping.code
              )
            ) {
              // ensure transaction is valid to be able to replay
              transaction.copy(validUntil = None)
            } else {
              transaction
            }
          }
        }
        .partition(transaction => isFoundingTopologyTransaction(transaction))

    private def isFoundingTopologyTransaction(
        transaction: StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]
    ) = {
      transaction.sequenced.value == CantonTimestamp.MinValue || transaction.sequenced.value == CantonTimestamp.MinValue.immediateSuccessor
    }

    val lastDomainStateParametersState
        : StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX] =
      sortedTransactions.reverse
        .collectFirst {
          case transaction
              if transaction.mapping.code == TopologyMappingX.Code.DomainParametersStateX =>
            transaction
        }
        .getOrElse(
          throw new IllegalArgumentException(
            s"Failed to find last topology transaction in $sortedTransactions"
          )
        )
  }

  implicit val storedTopologyTransactionOrdering: Ordering[GenericStoredTopologyTransactionX] = {
    // it seems some topology transactions can have the same sequenced time for the same transaction type
    // so to be able to successfully replay we need to sort by serial
    (x: GenericStoredTopologyTransactionX, y: GenericStoredTopologyTransactionX) =>
      {
        val sequencerTimeCompared = x.sequenced.compare(y.sequenced)
        if (sequencerTimeCompared == 0) {
          val xCode = x.transaction.transaction.mapping.code
          val yCode = y.transaction.transaction.mapping.code
          if (xCode == yCode)
            x.transaction.transaction.serial.compare(y.transaction.transaction.serial)
          else if (xCode == Code.DecentralizedNamespaceDefinitionX) {
            // as the decentralized namespace controls the domain authorization is safer to just apply it afterwards
            -1
          } else 1

        } else {
          sequencerTimeCompared
        }
      }
  }
}
