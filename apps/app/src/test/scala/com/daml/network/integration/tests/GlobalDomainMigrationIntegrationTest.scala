package com.daml.network.integration.tests

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.{
  CNNodeEnvironmentImpl,
  MediatorAdminConnection,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.tests.GlobalDomainMigrationIntegrationTest.UpgradeDomainNode
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.LocalDomainNode
import com.daml.network.util.{ProcessTestUtil, TemplateJsonDecoder}
import com.daml.network.validator.store.NodeIdentitiesStore
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DiscardOps, DomainAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  ClientConfig,
  CommunityCryptoConfig,
  NonNegativeDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionsX,
  TimeQueryX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.transaction.DomainTrustCertificateX
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import com.google.protobuf.ByteString
import io.grpc.Status
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}

import java.nio.file.Files
import java.time.Duration as JavaDuration
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*
import scala.util.Using

class GlobalDomainMigrationIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromResources(Seq("global-upgrade-topology.conf"), this.getClass.getSimpleName)
      .withAllocatedUsers()
      .withTrafficTopupsEnabled
      .withResettedUnionspace()
      .withManualStart

  "can replay unionspace definition on new domain" in { implicit env =>
    import env.{actorSystem, executionContext}
    import env.environment.scheduler
    val retryProvider = new RetryProvider(
      loggerFactory,
      ProcessingTimeout(),
      new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds)),
      NoOpMetricsFactory,
    )
    val wallClock = new WallClock(
      ProcessingTimeout(),
      loggerFactory,
    )
    val staticParams =
      DomainParametersConfig(
        protocolVersion = DomainProtocolVersion(ProtocolVersion.dev),
        devVersionSupport = true,
      ).toStaticDomainParameters(CommunityCryptoConfig())
        .valueOrFail("static")
    startAllSync(
      sv1ScanBackend,
      sv1Backend,
      sv2Backend,
      sv3Backend,
      sv4Backend,
      sv1ValidatorBackend,
      sv2ValidatorBackend,
      sv3ValidatorBackend,
      sv4ValidatorBackend,
    )
    withCanton(
      Seq(
        testResourcesPath / "global-upgrade-domain-node.conf"
      ),
      Seq(),
      "global-domain-migration",
      "SV1_ADMIN_USER" -> sv1LocalBackend.config.ledgerApiUser,
    ) {
      Using.resources(
        createUpgradeNode(1, sv1Backend, retryProvider, wallClock, staticParams),
        createUpgradeNode(2, sv2Backend, retryProvider, wallClock, staticParams),
        createUpgradeNode(3, sv3Backend, retryProvider, wallClock, staticParams),
        createUpgradeNode(4, sv4Backend, retryProvider, wallClock, staticParams),
      ) { case (upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4) =>
        val allUpgradeNodes =
          Seq(upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)

        val svcPartyUnionspace = sv2Backend.appState.svcStore.key.svcParty.uid.namespace
        val globalDomainUnionspaceDefinition = sv1Backend.appState.participantAdminConnection
          .getUnionspaceDefinition(globalDomainId, svcPartyUnionspace)
          .futureValue

        val domainDynamicParams =
          sv1Backend.participantClientWithAdminToken.topology.domain_parameters
            .list(
              globalDomainId.filterString
            )
            .headOption
            .value
            .item
        bracket(
          withClue("Freeze the existing domain") {
            val zeroRate = NonNegativeInt.zero
            changeDomainRatePerParticipant(allUpgradeNodes, zeroRate)
          },
          // reset to not crash other tests
          changeDomainRatePerParticipant(
            allUpgradeNodes,
            domainDynamicParams.maxRatePerParticipant,
          ),
        ) {
          withClue("Switch domain nodes") {
            withClue("Init new nodes with the same identity") {
              allUpgradeNodes.parTraverse(_.initNewDomainNodes()).futureValue
            }
            withClue(
              "Register new domain, connect to sync topology, disconnect and import acs snapshot, reconnect"
            ) {
              allUpgradeNodes.parTraverse(_.registerAndConnectToDomain()).futureValue.discard
              withClue("unionspace is replicated on the new global domain") {
                eventuallySucceeds(timeUntilSuccess = 1.minute) {
                  upgradeDomainNode1.newParticipantConnection
                    .getUnionspaceDefinition(
                      globalDomainId,
                      svcPartyUnionspace,
                    )
                    .futureValue
                    .mapping shouldBe globalDomainUnionspaceDefinition.mapping
                }
              }
            }
            logger.info("Domain nodes modified, migrating all dars to the new participant.")
            allUpgradeNodes.parTraverse(_.migrateAllDars()).futureValue
            allUpgradeNodes
              .parTraverse(_.newParticipantConnection.disconnectDomain(globalDomainAlias))
              .futureValue
              .discard
            logger.info("Downloading ACS snapshot.")
            // TODO(#8761) wait until it's safe, based on params described in the design
            // also consider(from Martin): If a node was offline and joins the (old) domain a bit later, how will it know that it has caught up to the final ACS state so it's safe to take the snapshot?
            val acsSnapshots = withClue("take acs snapshots") {
              allUpgradeNodes.parTraverse(_.takeAcsSnapshot()).futureValue
            }
            logger.info("Restoring ACS snapshot.")
            allUpgradeNodes
              .zip(acsSnapshots)
              .parTraverse { case (node, snapshot) =>
                node.restoreAcsSnapshot(snapshot)
              }
              .futureValue
            logger.info("Reconnecting domain.")
            allUpgradeNodes
              .parTraverse(_.newParticipantConnection.reconnectDomain(globalDomainAlias))
              .futureValue
              .discard
          }
        }

        val sv2Party = sv2Backend.appState.svStore.key.svParty
        withClue("unionspace can be modified on the new domain") {
          allUpgradeNodes
            .parTraverse { upgradeNode =>
              val connection = upgradeNode.newParticipantConnection
              for {
                id <- connection.getId()
                _ <- connection.ensureUnionspaceDefinitionOwnerChangeProposalAccepted(
                  "keep just sv2",
                  globalDomainId,
                  svcPartyUnionspace,
                  _ => NonEmpty(Set, sv2Party.uid.namespace),
                  id.namespace.fingerprint,
                  RetryFor.WaitingOnInitDependency,
                )
              } yield {}
            }
            .futureValue
            .discard
          eventuallySucceeds() {
            upgradeDomainNode1.newParticipantConnection
              .getUnionspaceDefinition(
                globalDomainId,
                svcPartyUnionspace,
              )
              .futureValue
              .mapping
              .owners shouldBe Set(sv2Party.uid.namespace)
          }
        }
      // TODO(#8791) start new sv app and validate
//      sv1LocalBackend.startSync()
//      sv1LocalBackend.getSvcInfo().svcRules.payload.members.size() shouldBe 4
      }
    }
  }

  private def changeDomainRatePerParticipant(
      allUpgradeNodes: Seq[UpgradeDomainNode],
      zeroRate: NonNegativeInt,
  )(implicit
      env: CNNodeTestConsoleEnvironment,
      ec: ExecutionContextExecutor,
  ): Unit = {
    allUpgradeNodes
      .parTraverse(node =>
        node.backend.appState.participantAdminConnection
          .ensureDomainParameters(
            globalDomainId,
            params => {
              params.copy(maxRatePerParticipant = zeroRate)(
                params.representativeProtocolVersion
              )
            },
            node.backend.appState.svStore.key.svParty.uid.namespace.fingerprint,
          )
      )
      .futureValue
      .discard
  }

  private def createUpgradeNode(
      sv: Int,
      backend: SvAppBackendReference,
      retryProvider: RetryProvider,
      wallClock: WallClock,
      staticParams: StaticDomainParameters,
  )(implicit ec: ExecutionContextExecutor, sys: ActorSystem, env: CNNodeTestConsoleEnvironment) = {
    implicit val httpClient: HttpRequest => Future[HttpResponse] = backend.appState.httpClient
    implicit val decoder: TemplateJsonDecoder = backend.appState.decoder
    val svOffset = sv * 100
    UpgradeDomainNode(
      sv.toString,
      globalDomainAlias,
      globalDomainId,
      new LocalDomainNode(
        new SequencerAdminConnection(
          ClientConfig(port = Port.tryCreate(27009 + svOffset)),
          loggerFactory,
          retryProvider,
          wallClock,
        ),
        new MediatorAdminConnection(
          ClientConfig(port = Port.tryCreate(27007 + svOffset)),
          loggerFactory,
          retryProvider,
          wallClock,
        ),
        staticParams,
        ClientConfig(port = Port.tryCreate(27008 + svOffset)),
        "",
        JavaDuration.ZERO,
        loggerFactory,
        retryProvider,
      ),
      new ParticipantAdminConnection(
        ClientConfig(port = Port.tryCreate(27002 + svOffset)),
        loggerFactory,
        retryProvider,
        wallClock,
      ),
      backend,
      wallClock,
      loggerFactory,
      retryProvider,
      staticParams,
      svcParty,
    )
  }
}

object GlobalDomainMigrationIntegrationTest extends OptionValues {

  case class DomainNodeIdentities(
      domainNode: LocalDomainNode,
      clock: Clock,
      logger: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContextExecutor) {
    val sequencerIdentityStore = new NodeIdentitiesStore(
      domainNode.sequencerAdminConnection,
      None,
      clock,
      logger,
    )
    val mediatorIdentityStore = new NodeIdentitiesStore(
      domainNode.mediatorAdminConnection,
      None,
      clock,
      logger,
    )

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
  case class UpgradeDomainNode(
      id: String,
      globalDomainAlias: DomainAlias,
      globalDomainId: DomainId,
      newLocalDomainNode: LocalDomainNode,
      newParticipantConnection: ParticipantAdminConnection,
      backend: SvAppBackendReference,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      staticParams: StaticDomainParameters,
      svcPartyId: PartyId,
  )(implicit ec: ExecutionContextExecutor) {

    private lazy val loggerFactoryWithKey = loggerFactory.append("updateNode", id)
    private val logger = loggerFactoryWithKey.getLogger(getClass)

    val existingParticipantIdentities = new NodeIdentitiesStore(
      backend.appState.participantAdminConnection,
      None,
      clock,
      loggerFactory,
    )
    val newParticipantInitializer =
      new NodeInitializer(newParticipantConnection, retryProvider, loggerFactory)
    val newDomainNodeIdentities: DomainNodeIdentities = DomainNodeIdentities(
      newLocalDomainNode,
      clock,
      loggerFactory,
      retryProvider,
    )

    val existingDomainNodeIdentites: DomainNodeIdentities = DomainNodeIdentities(
      backend.appState.localDomainNode.value,
      clock,
      loggerFactory,
      retryProvider,
    )

    // queries during init to avoid including the freeze domain transaction
    private val sequencerTopologySnapshot =
      backend.appState.localDomainNode.value.sequencerAdminConnection
        .listALlTransactions(
          Some(TopologyStoreId.DomainStore(globalDomainId)),
          timeQuery = TimeQueryX.Range(
            None,
            None,
          ),
        )(TraceContext.empty)

    def migrateAllDars()(implicit tc: TraceContext): Future[Unit] = {
      val dars = backend.participantClientWithAdminToken.dars.list()
      val directoryToStoreDars = Files.createTempDirectory("dar_migration")
      dars
        .parTraverse { dar =>
          backend.participantClientWithAdminToken.dars.download(
            dar.hash,
            directoryToStoreDars.toFile.getAbsolutePath,
          )
          newParticipantConnection.uploadDarFile(
            directoryToStoreDars.resolve(s"${dar.name}.dar"),
            RetryFor.ClientCalls,
          )
        }
        .map(_.discard)
    }

    def initNewDomainNodes()(implicit tc: TraceContext): Future[Unit] = {
      for {
        participantDump <- existingParticipantIdentities.getNodeIdentitiesDump()
        _ <- newParticipantInitializer.initializeAndWait(participantDump)
        sequencerDump <- existingDomainNodeIdentites.sequencerIdentityStore.getNodeIdentitiesDump()
        _ <- newDomainNodeIdentities.sequencerInitializer.initializeFromDump(sequencerDump)
        _ = logger.info(s"Restored sequencer from dump identity $sequencerDump")
        // remove domain trust certificates so that the participants init with all the topology state
        // can we somehow not do this? doing it basically re-registers the participant up to the current point and
        // breaks party allocations on the ledger api, and maybe other things that count on streaming updates
        domainTopologyTransactions <- sequencerTopologySnapshot.map(
          _.filterNot(transaction =>
            transaction.mapping
              .select[DomainTrustCertificateX]
              .isDefined
          )
        )
        _ = logger.info(
          s"Restoring sequencer topology with sequencer transactions $domainTopologyTransactions"
        )
        _ <- newLocalDomainNode.sequencerAdminConnection
          .initialize(
            StoredTopologyTransactionsX(
              domainTopologyTransactions
            ),
            staticParams,
            None,
          )
        _ <- retryProvider.waitUntil(
          RetryFor.ClientCalls,
          "sequencer is initialized",
          newLocalDomainNode.sequencerAdminConnection.isNodeInitialized().map { initialized =>
            if (!initialized) {
              throw Status.INTERNAL
                .withDescription("Sequencer is not initialized")
                .asRuntimeException()
            }
          },
          loggerFactory.getTracedLogger(getClass),
        )
        _ <- retryProvider.waitUntil(
          RetryFor.ClientCalls,
          "sequencer is initialized with restored id",
          newLocalDomainNode.sequencerAdminConnection.getSequencerId.map { id =>
            if (id != sequencerDump.id) {
              throw Status.INTERNAL
                .withDescription("Sequencer is not initialized with dump id")
                .asRuntimeException()
            }
          },
          loggerFactory.getTracedLogger(getClass),
        )
        _ = logger.info("Sequencer is initialized")
        mediatorDump <- existingDomainNodeIdentites.mediatorIdentityStore.getNodeIdentitiesDump()
        _ <-
          newDomainNodeIdentities.mediatorInitializer.initializeFromDump(mediatorDump)
        _ <- newLocalDomainNode.mediatorAdminConnection
          .initialize(
            globalDomainId,
            staticParams,
            newLocalDomainNode.sequencerConnection,
          )
        _ <- retryProvider.waitUntil(
          RetryFor.ClientCalls,
          "mediator is initialized as expected",
          newLocalDomainNode.mediatorAdminConnection.getMediatorId.map { id =>
            if (id != mediatorDump.id) {
              throw Status.INTERNAL
                .withDescription("Mediator is not initialized with dump id")
                .asRuntimeException()
            }
          },
          loggerFactoryWithKey.getTracedLogger(getClass),
        )
        _ = logger.info(s"Mediator is initialized from dump $mediatorDump")
      } yield {}
    }

    def registerAndConnectToDomain()(implicit tc: TraceContext): Future[Unit] = {
      newParticipantConnection.registerDomain(
        DomainConnectionConfig(
          globalDomainAlias,
          domainId = Some(globalDomainId),
          sequencerConnections = SequencerConnections.single(newLocalDomainNode.sequencerConnection),
        )
      )
    }

    def takeAcsSnapshot()(implicit tc: TraceContext): Future[ByteString] = {
      backend.appState.participantAdminConnection.downloadAcsSnapshot(
        Set(
          backend.appState.svcStore.key.svcParty,
          backend.appState.svcStore.key.svParty,
        ),
        Some(
          globalDomainId
        ),
      )
    }

    def restoreAcsSnapshot(snapshot: ByteString)(implicit tc: TraceContext): Future[Unit] = {
      newParticipantConnection.uploadAcsSnapshot(
        snapshot
      )
    }

  }

  implicit val upgradeDomainNodeReleasable: Using.Releasable[UpgradeDomainNode] =
    (resource: UpgradeDomainNode) => {
      resource.newLocalDomainNode.close()
      resource.newParticipantConnection.close()
    }

}
