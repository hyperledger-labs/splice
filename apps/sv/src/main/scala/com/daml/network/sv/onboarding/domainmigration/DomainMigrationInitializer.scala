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
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.{DomainMigrationDump, LocalDomainNode}
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.{SetupUtil, SvcPartyHosting}
import com.daml.network.sv.onboarding.domainmigration.DomainMigrationInitializer.{
  DomainNodeInitializer,
  DomainTopologyTransactions,
  loadDomainMigrationDump,
}
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.sv.DomainNodeIdentitiesDump.DomainNodeIdentities
import com.daml.network.util.{TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.DynamicDomainParameters
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId}
import com.digitalasset.canton.topology.processing.SequencedTime
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionX.GenericStoredTopologyTransactionX
import com.digitalasset.canton.topology.transaction.{TopologyChangeOpX, TopologyMappingX}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import java.io.FileNotFoundException
import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.math.Ordered.orderingToOrdered

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
    val migrationDump = loadDomainMigrationDump(domainMigrationConfig.dumpFilePath)
    if (!config.domainMigrationId.contains(migrationDump.migrationId))
      throw Status.INVALID_ARGUMENT
        .withDescription(
          "Migration id from the dump does not match the configured migration id in ths SV. Please check if the SV app is configured with the correct migration id"
        )
        .asRuntimeException()
    val storeKey = SvStore.Key(migrationDump.svPartyId, migrationDump.svcPartyId)
    val svcPartyHosting = newSvcPartyHosting(
      storeKey,
      participantAdminConnection,
    )
    for {
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

  private def migrateToNewDomainNode(
      domainMigrationDump: DomainMigrationDump,
      svcPartyHosting: SvcPartyHosting,
  ): Future[Unit] = {
    val domainTopologyTransactions = DomainTopologyTransactions(
      domainMigrationDump.topologySnapshot.result
    )
    val domainAlias = domainMigrationDump.domainAlias
    def importDarsAndAcs() = {
      for {
        _ <- participantAdminConnection.disconnectFromAllDomains()
        _ = logger.info("Disconnected from all domains")
        // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
        _ <- MonadUtil.sequentialTraverse(domainMigrationDump.dars.map { dar =>
          UploadablePackage.fromByteString(dar.hash.toHexString, dar.content)
        }) { dar =>
          participantAdminConnection.uploadDarFileLocally(
            dar,
            RetryFor.WaitingOnInitDependency,
          )
        }
        _ = logger.info("uploaded all dars to the participant.")

        _ <- participantAdminConnection.uploadAcsSnapshot(domainMigrationDump.acsSnapshot)
        _ = logger.info("Acs snapshot is restored")

        _ <- participantAdminConnection.reconnectDomain(domainAlias)

        _ <- participantAdminConnection.modifyDomainConnectionConfig(
          domainAlias,
          c =>
            Some(
              c.copy(
                initializeFromTrustedDomain = false,
                manualConnect = false,
              )
            ),
        )
        _ = logger.info("domain is reconnected")
      } yield ()
    }
    for {
      _ <- initializeDomainNode(
        domainMigrationDump.domainId,
        domainMigrationDump.nodeIdentities,
        domainTopologyTransactions,
      )
      _ = logger.info("Registering and connecting to new domain")
      _ <- participantAdminConnection
        .lookupDomainConnectionConfig(
          domainAlias
        )
        .flatMap {
          case Some(config) if config.initializeFromTrustedDomain =>
            importDarsAndAcs()
          case None =>
            participantAdminConnection
              .ensureDomainRegistered(
                DomainConnectionConfig(
                  domainAlias,
                  domainId = Some(domainMigrationDump.domainId),
                  sequencerConnections =
                    SequencerConnections.single(localDomainNode.sequencerConnection),
                  initializeFromTrustedDomain = true,
                  manualConnect = true,
                ),
                RetryFor.ClientCalls,
              )
              .flatMap(_ => importDarsAndAcs())
          case Some(_) => Future.unit
        }
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
          _.tryUpdate(maxRatePerParticipant = DynamicDomainParameters.defaultMaxRatePerParticipant),
          signedBy = domainMigrationDump.nodeIdentities.participant.id.uid.namespace.fingerprint,
        )
      _ = logger.info("resumed domain")
      _ <- svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
        domainMigrationDump.domainId,
        ParticipantId(domainMigrationDump.nodeIdentities.participant.id.uid),
      )
      _ = logger.info(
        s"SVC party hosting is replicated on the new global domain"
      )

    } yield {}
  }

  private def initializeDomainNode(
      domainId: DomainId,
      nodeIdentities: DomainNodeIdentities,
      domainTopologyTransactions: DomainTopologyTransactions,
  ): Future[Unit] = {
    val domainNodeInitiaizer = DomainNodeInitializer(
      localDomainNode,
      clock,
      loggerFactory,
      retryProvider,
    )
    logger.info(s"Init new domain nodes from snapshot $domainTopologyTransactions")
    for {
      _ <- initializeSequencer(
        domainNodeInitiaizer,
        nodeIdentities.sequencer,
        domainTopologyTransactions.sequencerInitTopologyTransactions,
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
      _ <- initializeMediator(
        domainId,
        domainNodeInitiaizer,
        nodeIdentities.mediator,
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

  private def initializeSequencer(
      domainNodeInitializer: DomainNodeInitializer,
      identity: NodeIdentitiesDump,
      sequencerInitTopologyTransactions: Seq[
        StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]
      ],
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
              s"Restoring sequencer topology with sequencer transactions $sequencerInitTopologyTransactions"
            )
            _ <- localDomainNode.sequencerAdminConnection
              .initialize(
                StoredTopologyTransactionsX(
                  sequencerInitTopologyTransactions
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
          } yield {}
        }
      }
      .flatMap { _ =>
        retryProvider.waitUntil(
          RetryFor.ClientCalls,
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
      localDomainNode,
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

  case class DomainTopologyTransactions(
      transactions: Seq[StoredTopologyTransactionX[TopologyChangeOpX, TopologyMappingX]]
  ) {

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    private val transactionsSortedAndDeduplicatedBySignatures = transactions
      .groupBy(transaction =>
        transaction.transaction.mapping -> transaction.transaction.transaction.serial
      )
      .view
      // keep just the entry with the most signatures, ensuring it will be accepted
      .mapValues(_.maxBy(_.transaction.signatures.size))
      .values
      .toSeq
      .sorted

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
      transactionsSortedAndDeduplicatedBySignatures
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
      transactionsSortedAndDeduplicatedBySignatures.reverse
        .collectFirst {
          case transaction
              if transaction.mapping.code == TopologyMappingX.Code.DomainParametersStateX =>
            transaction
        }
        .getOrElse(
          throw new IllegalArgumentException(
            s"Failed to find last topology transaction in $transactionsSortedAndDeduplicatedBySignatures"
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
