package com.daml.network.integration.tests

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.auth.{AuthToken, AuthUtil}
import com.daml.network.codegen.java.cc.round.types.Round
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_AddMember
import com.daml.network.codegen.java.cn.svcrules.SvcRules_AddMember
import com.daml.network.console.{ScanAppBackendReference, SvAppBackendReference}
import com.daml.network.environment.{
  BaseLedgerConnection,
  CNLedgerClient,
  CNNodeEnvironmentImpl,
  MediatorAdminConnection,
  PackageIdResolver,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.integration.tests.GlobalDomainMigrationIntegrationTest.UpgradeDomainNode
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.sv.{DomainMigrationDump, LocalDomainNode}
import com.daml.network.util.{ProcessTestUtil, TemplateJsonDecoder}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DiscardOps, DomainAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  ApiLoggingConfig,
  ClientConfig,
  CommunityCryptoConfig,
  NonNegativeDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.topology.processing.SequencedTime
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransactionX,
  StoredTopologyTransactionsX,
  TopologyStoreId,
}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionX.GenericStoredTopologyTransactionX
import com.digitalasset.canton.topology.transaction.{TopologyChangeOpX, TopologyMappingX}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code
import com.digitalasset.canton.tracing.{NoReportingTracerProvider, TraceContext}
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.version.{DomainProtocolVersion, ProtocolVersion}
import com.google.protobuf.ByteString
import io.grpc.Status
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.scalatest.OptionValues
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minute, Minutes, Span}

import java.nio.file.Files
import java.time.Duration as JavaDuration
import scala.concurrent.{ExecutionContextExecutor, Future, blocking}
import scala.concurrent.duration.DurationInt
import scala.math.Ordered.orderingToOrdered
import scala.util.Using

// TODO(#9076) Create fresh database instances within the test to support running it multiple times.
class GlobalDomainMigrationIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistend stores
  registerPlugin(new UseInMemoryStores(loggerFactory))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromResources(Seq("global-upgrade-topology.conf"), this.getClass.getSimpleName)
      .withAllocatedUsers()
      .withTrafficTopupsEnabled
      .withResettedDecentralizedNamespace()
      .withZeroSequencerAvailabilityDelay
      .withManualStart

  "migrate global domain to new nodes with downtime" in { implicit env =>
    import env.{actorSystem, executionContext, executionSequencerFactory}
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
        protocolVersion = DomainProtocolVersion(ProtocolVersion.dev)
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
    actAndCheck("Create some transaction history", sv1WalletClient.tap(1337))(
      "Scan transaction history is recorded and wallet balance is updated",
      _ => {
        // buffer to account for domain fee payments
        assertInRange(sv1WalletClient.balance().unlockedQty, (1000, 2000))
        countTapsFromScan(sv1ScanBackend, 1337) shouldEqual (1)
      },
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
        val allNodes =
          Seq(upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)

        val svcPartyDecentralizedNamespace = sv1Backend.appState.svcStore.key.svcParty.uid.namespace
        val globalDomainDecentralizedNamespaceDefinition =
          sv1Backend.appState.participantAdminConnection
            .getDecentralizedNamespaceDefinition(globalDomainId, svcPartyDecentralizedNamespace)
            .futureValue

        val domainDynamicParams =
          sv1Backend.participantClientWithAdminToken.topology.domain_parameters
            .list(
              globalDomainId.filterString
            )
            .headOption
            .value
            .item
        val majorityUpgradeNodes = Seq(upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)
        // sv1 is the founder so specifically join it later to validate our replay
        val lateRejoiningUpgradeNodes = Seq(upgradeDomainNode1)
        val sv1Party = sv1Backend.appState.svStore.key.svParty
        def withCLueAndLog[T](clueMessage: String)(fun: => T) = withClue(clueMessage) {
          clue(clueMessage)(fun)
        }
        bracket(
          withCLueAndLog("Freeze the existing domain") {
            allNodes.parTraverse(n => Future(n.backend.pauseGlobalDomain())).futureValue.discard
          },
          // reset to not crash other tests
          changeDomainRatePerParticipant(
            allNodes.map(_.backend.appState.participantAdminConnection),
            domainDynamicParams.maxRatePerParticipant,
          ),
        ) {
          def migrateDomainOnNodes(
              nodes: Seq[UpgradeDomainNode],
              firstRun: Boolean = false,
          ): Unit = {
            val nodesWithMigrationDumps =
              nodes.map(node => node -> node.backend.getDomainMigrationDump())
            withCLueAndLog(s"Switch domain nodes ${nodes.map(_.id)}") {
              withCLueAndLog("Init new nodes with the same identity") {
                nodesWithMigrationDumps
                  .parTraverse { case (node, domainMigrationDump) =>
                    node.migrateDomainToNewNodes(domainMigrationDump)
                  }
                  .futureValue(
                    Timeout(
                      Span(
                        3,
                        Minutes,
                      )
                    )
                  )
              }
              withCLueAndLog(
                "Register new domain, connect to it in order to sync topology, disconnect and import acs snapshot, reconnect"
              ) {
                nodes.parTraverse(_.registerAndConnectToDomain()).futureValue.discard

                withCLueAndLog("party hosting is replicated on the new global domain") {
                  nodes.foreach { node =>
                    eventuallySucceeds(timeUntilSuccess = 1.minute) {
                      node.newParticipantConnection
                        .getPartyToParticipant(
                          globalDomainId,
                          svcParty,
                        )
                        .futureValue
                        .mapping
                        .participantIds
                        .size shouldBe 4
                    }
                  }
                }
                if (firstRun) {
                  withCLueAndLog("all topology is synced") {
                    nodes.foreach { node =>
                      val topologyTransactionsInOldStore =
                        node.backend.appState.participantAdminConnection
                          .listAllTransactions(
                            Some(TopologyStoreId.DomainStore(globalDomainId))
                          )
                          .futureValue
                      eventuallySucceeds(timeUntilSuccess = 1.minute) {
                        node.newParticipantConnection
                          .listAllTransactions(
                            Some(TopologyStoreId.DomainStore(globalDomainId))
                          )
                          .futureValue
                          .size == topologyTransactionsInOldStore.size
                      }
                    }
                  }

                  withCLueAndLog("new domain is frozen") {
                    nodes.foreach { node =>
                      eventuallySucceeds(timeUntilSuccess = 1.minute) {
                        node.newParticipantConnection
                          .getDomainParametersState(
                            globalDomainId
                          )
                          .futureValue
                          .mapping
                          .parameters
                          .maxRatePerParticipant shouldBe NonNegativeInt.zero
                      }
                    }
                  }
                  withCLueAndLog("decentralized namespace is replicated on the new global domain") {
                    eventuallySucceeds(timeUntilSuccess = 1.minute) {
                      nodes.foreach { node =>
                        node.newParticipantConnection
                          .getDecentralizedNamespaceDefinition(
                            globalDomainId,
                            svcPartyDecentralizedNamespace,
                          )
                          .futureValue
                          .mapping shouldBe globalDomainDecentralizedNamespaceDefinition.mapping
                      }
                    }
                  }
                }
              }
              changeDomainRatePerParticipant(
                nodes.map(_.newParticipantConnection),
                domainDynamicParams.maxRatePerParticipant,
              )

              withCLueAndLog("Domain nodes modified, migrating all dars to the new participant.") {
                nodes.parTraverse(_.migrateAllDars()).futureValue
              }
              nodes
                .parTraverse(_.newParticipantConnection.disconnectDomain(globalDomainAlias))
                .futureValue
                .discard
              withCLueAndLog("restore acs snapshot") {
                nodesWithMigrationDumps.parTraverse { case (node, domainMigrationDump) =>
                  node.restoreAcsSnapshot(domainMigrationDump.acsSnapshot)
                }.futureValue
              }
              withCLueAndLog("Reconnecting domain.") {
                nodes
                  .parTraverse(_.newParticipantConnection.reconnectDomain(globalDomainAlias))
                  .futureValue
                  .discard
              }
            }
          }

          migrateDomainOnNodes(majorityUpgradeNodes, firstRun = true)

          withCLueAndLog("decentralized namespace can be modified on the new domain") {
            majorityUpgradeNodes
              .parTraverse { upgradeNode =>
                val connection = upgradeNode.newParticipantConnection
                for {
                  id <- connection.getId()
                  _ <- connection.ensureDecentralizedNamespaceDefinitionOwnerChangeAccepted(
                    "keep just sv1",
                    globalDomainId,
                    svcPartyDecentralizedNamespace,
                    _ => NonEmpty(Set, sv1Party.uid.namespace),
                    id.namespace.fingerprint,
                    RetryFor.WaitingOnInitDependency,
                    isProposal = true,
                  )
                } yield {}
              }
              .futureValue
              .discard
          }

          withCLueAndLog("migrate domain and prepare sv1") {
            migrateDomainOnNodes(lateRejoiningUpgradeNodes)
            allNodes.foreach { node =>
              eventuallySucceeds() {
                node.newParticipantConnection
                  .getDecentralizedNamespaceDefinition(
                    globalDomainId,
                    svcPartyDecentralizedNamespace,
                  )
                  .futureValue
                  .mapping
                  .owners shouldBe Set(sv1Party.uid.namespace)
              }
            }
            upgradeDomainNode1.migrateUserAnnotation().futureValue
          }
          sv1LocalBackend.startSync()

          startAllSync(
            sv1LocalBackend,
            sv1ScanLocalBackend,
            sv1ValidatorLocalBackend,
          )
          sv1LocalBackend.getSvcInfo().svcRules.payload.members.size() shouldBe 4

          clue("Old wallet balance is recorded") {
            assertInRange(sv1WalletLocalClient.balance().unlockedQty, (1000, 2000))
          }
          // TODO(#9014) make this work (with persistent stores)
          // clue("Old scan transaction history is recorded"){
          //   countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual (1)
          //   countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual (0)
          // }
          actAndCheck("Create some new transaction history", sv1WalletLocalClient.tap(1338))(
            "New transaction history is recorded and balance is updated",
            _ => {
              // TODO(#9014) make this work (with persistent stores)
              // countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual (1)
              countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual (1)
              assertInRange(sv1WalletLocalClient.balance().unlockedQty, (2000, 4000))
            },
          )

          actAndCheck(
            "validate domain with create VoteRequest",
            sv1LocalBackend.createVoteRequest(
              sv1Party.toProtoPrimitive,
              new ARC_SvcRules(
                new SRARC_AddMember(
                  new SvcRules_AddMember(
                    "alice",
                    "Alice",
                    dummySvRewardWeight,
                    "alice-participant-id",
                    new Round(42),
                    globalDomainId.toProtoPrimitive,
                  )
                )
              ),
              "url",
              "description",
              sv1LocalBackend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
            ),
          )(
            "VoteRequest and Vote should be there",
            _ =>
              inside(sv1LocalBackend.listVoteRequests()) { case Seq(onlyReq) =>
                sv1LocalBackend.listVotes(Vector(onlyReq.contractId.contractId)) should have size 1
              },
          )
        }
      }
    }
  }

  private def changeDomainRatePerParticipant(
      nodes: Seq[ParticipantAdminConnection],
      rate: NonNegativeInt,
  )(implicit
      env: CNNodeTestConsoleEnvironment,
      ec: ExecutionContextExecutor,
  ): Unit = {
    nodes
      .parTraverse { node =>
        val id = node.getId().futureValue
        node
          .ensureDomainParameters(
            globalDomainId,
            _.tryUpdate(maxRatePerParticipant = rate),
            signedBy = id.namespace.fingerprint,
          )
      }
      .futureValue
      .discard
  }

  private def createUpgradeNode(
      sv: Int,
      backend: SvAppBackendReference,
      retryProvider: RetryProvider,
      wallClock: WallClock,
      staticParams: StaticDomainParameters,
  )(implicit
      ec: ExecutionContextExecutor,
      sys: ActorSystem,
      env: CNNodeTestConsoleEnvironment,
      esf: ExecutionSequencerFactory,
  ) = {
    implicit val httpClient: HttpRequest => Future[HttpResponse] = backend.appState.httpClient
    implicit val decoder: TemplateJsonDecoder = backend.appState.decoder
    val svOffset = sv * 100
    val loggerFactoryWithKey = loggerFactory.append("updateNode", sv.toString)
    UpgradeDomainNode(
      sv.toString,
      globalDomainAlias,
      globalDomainId,
      new LocalDomainNode(
        new SequencerAdminConnection(
          ClientConfig(address = "localhost", port = Port.tryCreate(27009 + svOffset)),
          loggerFactoryWithKey,
          retryProvider,
          wallClock,
        ),
        new MediatorAdminConnection(
          ClientConfig(port = Port.tryCreate(27007 + svOffset)),
          loggerFactoryWithKey,
          retryProvider,
          wallClock,
        ),
        staticParams,
        ClientConfig(port = Port.tryCreate(27008 + svOffset)),
        "",
        JavaDuration.ZERO,
        None,
        loggerFactoryWithKey,
        retryProvider,
      ),
      new ParticipantAdminConnection(
        ClientConfig(port = Port.tryCreate(27002 + svOffset)),
        loggerFactoryWithKey,
        retryProvider,
        wallClock,
      ),
      backend,
      wallClock,
      loggerFactoryWithKey,
      retryProvider,
      staticParams,
      svcParty,
      new CNLedgerClient(
        ClientConfig(
          port = Port.tryCreate(27001 + svOffset)
        ),
        globalDomainId.filterString,
        () =>
          Future.successful(
            Some(
              AuthToken(
                AuthUtil.LedgerApi.testToken(user = backend.config.ledgerApiUser, secret = "test")
              )
            )
          ),
        ApiLoggingConfig(),
        loggerFactory,
        NoReportingTracerProvider,
        retryProvider,
      ),
    )
  }

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: Double) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.coinAmount)) == Some(BigDecimal(tapAmount))
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  }
}

object GlobalDomainMigrationIntegrationTest extends OptionValues {

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
      newLedgerClient: CNLedgerClient,
  )(implicit ec: ExecutionContextExecutor) {

    private val logger = loggerFactory.getLogger(getClass)

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

    def migrateAllDars()(implicit tc: TraceContext): Future[Unit] = {
      val dars = backend.participantClientWithAdminToken.dars.list()
      val directoryToStoreDars = Files.createTempDirectory("dar_migration")
      dars
        .parTraverse { dar =>
          Future {
            blocking {
              backend.participantClientWithAdminToken.dars.download(
                dar.hash,
                directoryToStoreDars.toFile.getAbsolutePath,
              )
            }
          }
        }
        .flatMap { _ =>
          // Sequential uploads to avoid #8987
          // TODO(#5141) move back to parallel uploads
          MonadUtil
            .sequentialTraverse(dars) { dar =>
              newParticipantConnection.uploadDarFile(
                directoryToStoreDars.resolve(s"${dar.name}.dar"),
                RetryFor.ClientCalls,
              )
            }
            .map(_.discard)
        }
    }

    def migrateUserAnnotation(): Future[Unit] = {
      newLedgerClient
        .connection(
          "migration",
          loggerFactory,
          PackageIdResolver.staticTesting,
        )
        .ensureUserMetadataAnnotation(
          backend.config.ledgerApiUser,
          BaseLedgerConnection.SVC_PARTY_USER_METADATA_KEY,
          svcPartyId.toProtoPrimitive,
          RetryFor.WaitingOnInitDependency,
        )
    }

    def migrateDomainToNewNodes(
        domainMigrationDump: DomainMigrationDump
    )(implicit tc: TraceContext): Future[Unit] = {
      val domainTopologyTransactions = DomainTopologyTransactions(
        domainMigrationDump.topologySnapshot.result
      )
      logger.info(s"Init new domain nodes from snapshot $domainTopologyTransactions")
      val nodeIdentities = backend.getDomainMigrationDump().nodeIdentities
      val participantIdentities = nodeIdentities.participant
      for {
        _ <- newParticipantInitializer.initializeAndWait(participantIdentities)
        sequencerIdentities = nodeIdentities.sequencer
        _ <- newDomainNodeIdentities.sequencerInitializer.initializeFromDump(sequencerIdentities)
        _ = logger.info(
          s"Restoring sequencer topology with sequencer transactions ${domainTopologyTransactions.sequencerInitTopologyTransactions}"
        )
        _ <- newLocalDomainNode.sequencerAdminConnection
          .initialize(
            StoredTopologyTransactionsX(
              domainTopologyTransactions.sequencerInitTopologyTransactions
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
            if (id != sequencerIdentities.id) {
              throw Status.INTERNAL
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
            newLocalDomainNode.sequencerAdminConnection
              .addTopologyTransactionsAndEnsurePersisted(
                Some(globalDomainId),
                Seq(transactions.transaction),
              )
        )
        mediatorIdentities = nodeIdentities.mediator
        _ <-
          newDomainNodeIdentities.mediatorInitializer.initializeFromDump(mediatorIdentities)
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
            if (id != mediatorIdentities.id) {
              throw Status.INTERNAL
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
            sequencerTopology <- newLocalDomainNode.sequencerAdminConnection.listAllTransactions(
              Some(TopologyStoreId.DomainStore(globalDomainId))
            )
            mediatorTopology <- newLocalDomainNode.mediatorAdminConnection.listAllTransactions(
              Some(TopologyStoreId.DomainStore(globalDomainId))
            )
          } yield {
            sequencerTopology.size == mediatorTopology.size
          },
          loggerFactory.getTracedLogger(getClass),
        )
      } yield {}
    }

    def registerAndConnectToDomain()(implicit tc: TraceContext): Future[Unit] = {
      newParticipantConnection.registerDomain(
        DomainConnectionConfig(
          globalDomainAlias,
          domainId = Some(globalDomainId),
          sequencerConnections =
            SequencerConnections.single(newLocalDomainNode.sequencerConnection),
          initializeFromTrustedDomain = true,
        )
      )
    }

    def restoreAcsSnapshot(snapshot: ByteString)(implicit tc: TraceContext): Future[Unit] = {
      newParticipantConnection
        .uploadAcsSnapshot(
          snapshot
        )
        .flatMap { _ =>
          acsSnapshotForConnection(newParticipantConnection).map(newSnapshot =>
            require(snapshot == newSnapshot, "Snapshots must be identical after restore")
          )
        }
    }

    private def acsSnapshotForConnection(
        connection: ParticipantAdminConnection
    )(implicit tc: TraceContext) = {
      connection.downloadAcsSnapshot(
        Set(
          backend.appState.svcStore.key.svcParty,
          backend.appState.svcStore.key.svParty,
        ),
        Some(
          globalDomainId
        ),
      )
    }

  }

  implicit val upgradeDomainNodeReleasable: Using.Releasable[UpgradeDomainNode] =
    (resource: UpgradeDomainNode) => {
      resource.newParticipantConnection.close()
      resource.newLocalDomainNode.close()
      resource.newLedgerClient.close()
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
