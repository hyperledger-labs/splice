package com.daml.network.integration.tests

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_AddMember
import com.daml.network.codegen.java.cn.svcrules.SvcRules_AddMember
import com.daml.network.config.{CNNodeConfigTransforms, ParticipantBootstrapDumpConfig}
import com.daml.network.console.{ScanAppBackendReference, SvAppBackendReference}
import com.daml.network.environment.{
  CNNodeEnvironmentImpl,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.integration.tests.GlobalDomainMigrationIntegrationTest.UpgradeDomainNode
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.sv.DomainMigrationDump
import com.daml.network.sv.config.SvOnboardingConfig.DomainMigration
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.util.ProcessTestUtil
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.config.{ClientConfig, NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}

import java.nio.file.{Path, Paths}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Using

// TODO(#9076) Create fresh database instances within the test to support running it multiple times.
class GlobalDomainMigrationIntegrationTest extends CNNodeIntegrationTest with ProcessTestUtil {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistent stores
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
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.updateAllSvAppConfigs((name, c) =>
          if (name.endsWith("Local")) {
            c.copy(
              participantBootstrappingDump = Some(
                ParticipantBootstrapDumpConfig.File(
                  migrationParticipantIdentitiesFilePath(name).path
                )
              ),
              onboarding = Some(
                DomainMigration(c.onboarding.value.name, migrationDumpFilePath(name).path)
              ),
            )
          } else c
        )(conf)
      )

  "migrate global domain to new nodes with downtime" in { implicit env =>
    import env.environment.scheduler
    import env.executionContext
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

    val sv2LocalBackend = svb("sv2Local")
    val sv3LocalBackend = svb("sv3Local")
    val sv4LocalBackend = svb("sv4Local")

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
        countTapsFromScan(sv1ScanBackend, 1337) shouldBe 1
      },
    )
    withCanton(
      Seq(
        testResourcesPath / "global-upgrade-domain-node.conf"
      ),
      Seq(),
      "global-domain-migration",
      "SV1_ADMIN_USER" -> sv1LocalBackend.config.ledgerApiUser,
      "SV2_ADMIN_USER" -> sv2LocalBackend.config.ledgerApiUser,
      "SV3_ADMIN_USER" -> sv3LocalBackend.config.ledgerApiUser,
      "SV4_ADMIN_USER" -> sv4LocalBackend.config.ledgerApiUser,
    ) {
      Using.resources(
        createUpgradeNode(1, sv1Backend, retryProvider, wallClock),
        createUpgradeNode(2, sv2Backend, retryProvider, wallClock),
        createUpgradeNode(3, sv3Backend, retryProvider, wallClock),
        createUpgradeNode(4, sv4Backend, retryProvider, wallClock),
      ) { case (upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4) =>
        val allNodes =
          Seq(upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)
        val svcPartyDecentralizedNamespace = sv1Backend.appState.svcStore.key.svcParty.uid.namespace

        val domainDynamicParams =
          sv1Backend.participantClientWithAdminToken.topology.domain_parameters
            .list(
              globalDomainId.filterString
            )
            .headOption
            .value
            .item
        val majorityUpgradeNodes = Seq(upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)

        val sv1Party = sv1Backend.appState.svStore.key.svParty
        bracket(
          withClueAndLog(
            s"Freeze the existing domain from ${domainDynamicParams.maxRatePerParticipant}"
          ) {
            allNodes.parTraverse(n => Future(n.oldBackend.pauseGlobalDomain())).futureValue.discard
          },
          // reset to not crash other tests
          changeDomainRatePerParticipant(
            allNodes.map(_.oldBackend.appState.participantAdminConnection),
            domainDynamicParams.maxRatePerParticipant,
          ),
        ) {
          // TODO(#8761) wait until it's safe, based on params described in the design
          // also consider(from Martin): If a node was offline and joins the (old) domain a bit later, how will it know that it has caught up to the final ACS state so it's safe to take the snapshot?
          Threading.sleep(
            domainDynamicParams.mediatorReactionTimeout.duration.toMillis + domainDynamicParams.participantResponseTimeout.duration.toMillis
          )
          majorityUpgradeNodes.foreach { node =>
            val dump = node.oldBackend.getDomainMigrationDump()
            writeMigrationDump(s"${node.oldBackend.name}Local", dump)
          }

          // TODO(#8761) restart the apps with onboarding type none
          startAllSync(
            sv2LocalBackend,
            sv3LocalBackend,
            sv4LocalBackend,
          )

          checkMigrateDomainOnNodes(majorityUpgradeNodes)

          withClueAndLog("decentralized namespace can be modified on the new domain") {
            majorityUpgradeNodes
              .parTraverse { upgradeNode =>
                val connection = upgradeNode.newParticipantConnection
                for {
                  id <- connection.getId()
                  _ <- connection.ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
                    "keep just sv1",
                    globalDomainId,
                    svcPartyDecentralizedNamespace,
                    _ => NonEmpty(Set, sv1Party.uid.namespace),
                    id.namespace.fingerprint,
                    RetryFor.WaitingOnInitDependency,
                  )
                } yield {}
              }
              .futureValue
              .discard
          }

          withClueAndLog("migrate the late joining node") {
            // sv1 is the founder so specifically join it later to validate our replay
            val dump = upgradeDomainNode1.oldBackend.getDomainMigrationDump()
            writeMigrationDump(s"${upgradeDomainNode1.oldBackend.name}Local", dump)
            sv1LocalBackend.startSync()

            eventuallySucceeds() {
              upgradeDomainNode1.newParticipantConnection
                .getDecentralizedNamespaceDefinition(
                  globalDomainId,
                  svcPartyDecentralizedNamespace,
                )
                .futureValue
                .mapping
                .owners shouldBe Set(sv1Party.uid.namespace)
            }
            withClue("domain is unpaused on the new node") {
              eventuallySucceeds() {
                upgradeDomainNode1.newParticipantConnection
                  .getDomainParametersState(
                    globalDomainId
                  )
                  .futureValue
                  .mapping
                  .parameters
                  .maxRatePerParticipant should be > NonNegativeInt.zero
              }
            }
          }

          withClue("domain is unchanged on the old nodes") {
            eventuallySucceeds() {
              sv1Backend.appState.participantAdminConnection
                .getDecentralizedNamespaceDefinition(
                  globalDomainId,
                  svcPartyDecentralizedNamespace,
                )
                .futureValue
                .mapping
                .owners
                .forgetNE should have size 4
              sv1Backend.appState.participantAdminConnection
                .getDomainParametersState(
                  globalDomainId
                )
                .futureValue
                .mapping
                .parameters
                .maxRatePerParticipant should be(NonNegativeInt.zero)
            }
          }

          startAllSync(
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
              countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual 1
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
  )(implicit
      ec: ExecutionContextExecutor
  ) = {
    val svOffset = sv * 100
    val loggerFactoryWithKey = loggerFactory.append("updateNode", sv.toString)
    UpgradeDomainNode(
      new ParticipantAdminConnection(
        ClientConfig(port = Port.tryCreate(27002 + svOffset)),
        loggerFactoryWithKey,
        retryProvider,
        wallClock,
      ),
      backend,
    )
  }

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: Double) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.coinAmount)).contains(BigDecimal(tapAmount))
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  }

  private def writeMigrationDump(
      nodeName: String,
      domainMigrationDump: DomainMigrationDump,
  ): Unit = {
    val participantIdDumpFile = migrationParticipantIdentitiesFilePath(nodeName)
    if (participantIdDumpFile.exists) participantIdDumpFile.clear()
    else participantIdDumpFile.createFile()

    participantIdDumpFile.write(domainMigrationDump.nodeIdentities.participant.toJson.spaces2)
    val dumpFile = migrationDumpFilePath(nodeName)
    if (dumpFile.exists) dumpFile.clear()
    else dumpFile.createFile()
    dumpFile.write(domainMigrationDump.toJson.spaces2)
  }

  private def migrationDumpFilePath(nodeName: String) = {
    import better.files.File
    val filename = s"${nodeName}_migration_dump.json"
    File(GlobalDomainMigrationIntegrationTest.migrationDumpDir) / filename
  }

  private def migrationParticipantIdentitiesFilePath(nodeName: String) = {
    import better.files.File
    val filename = s"${nodeName}_participant_identities_dump.json"
    File(GlobalDomainMigrationIntegrationTest.migrationDumpDir) / filename
  }

  private def checkMigrateDomainOnNodes(
      nodes: Seq[UpgradeDomainNode]
  )(implicit env: CNNodeTestConsoleEnvironment): Unit = {
    withClueAndLog("party hosting is replicated on the new global domain") {
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
    withClueAndLog("all topology is synced") {
      nodes.foreach { node =>
        val topologyTransactionsInOldStore =
          node.oldBackend.appState.participantAdminConnection
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

    withClueAndLog("decentralized namespace is replicated on the new global domain") {
      val globalDomainDecentralizedNamespaceDefinition =
        sv1Backend.appState.participantAdminConnection
          .getDecentralizedNamespaceDefinition(globalDomainId, svcParty.uid.namespace)
          .futureValue
      eventuallySucceeds(timeUntilSuccess = 1.minute) {
        nodes.foreach { node =>
          node.newParticipantConnection
            .getDecentralizedNamespaceDefinition(
              globalDomainId,
              svcParty.uid.namespace,
            )
            .futureValue
            .mapping shouldBe globalDomainDecentralizedNamespaceDefinition.mapping
        }
      }
    }
    withClue("domain is unpaused on the new nodes") {
      eventuallySucceeds() {
        nodes.foreach(
          _.newParticipantConnection
            .getDomainParametersState(
              globalDomainId
            )
            .futureValue
            .mapping
            .parameters
            .maxRatePerParticipant should be > NonNegativeInt.zero
        )
      }
    }
  }

  private def withClueAndLog[T](clueMessage: String)(fun: => T) = withClue(clueMessage) {
    clue(clueMessage)(fun)
  }
}

object GlobalDomainMigrationIntegrationTest extends OptionValues {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  // Not using temp-files so test-generated outputs are easy to inspect.
  val migrationDumpDir: Path = testDumpDir.resolve("domain-migration-dump")
  if (!migrationDumpDir.toFile.exists())
    migrationDumpDir.toFile.mkdirs()

  case class UpgradeDomainNode(
      newParticipantConnection: ParticipantAdminConnection,
      oldBackend: SvAppBackendReference,
  )

  implicit val upgradeDomainNodeReleasable: Using.Releasable[UpgradeDomainNode] =
    (resource: UpgradeDomainNode) => {
      resource.newParticipantConnection.close()
    }
}
