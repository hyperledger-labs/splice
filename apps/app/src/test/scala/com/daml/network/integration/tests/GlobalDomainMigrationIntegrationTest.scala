package com.daml.network.integration.tests

import better.files.File.apply
import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.sv.automation.singlesv.{DomainUpgradeTrigger, SvRewardTrigger}
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.{
  SRARC_AddMember,
  SRARC_SetConfig,
}
import com.daml.network.codegen.java.cn.svcrules.{
  DomainUpgradeSchedule,
  SvcRules,
  SvcRulesConfig,
  SvcRules_AddMember,
  SvcRules_SetConfig,
  VoteRequest,
}
import com.daml.network.config.CNNodeConfigTransforms
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
import com.daml.network.integration.tests.GlobalDomainMigrationIntegrationTest.{
  UpgradeDomainNode,
  migrationDumpDir,
}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.sv.config.SvOnboardingConfig.DomainMigration
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.util.{Contract, ProcessTestUtil}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DiscardOps
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{ClientConfig, NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.metrics.MetricHandle.NoOpMetricsFactory
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}

import java.io.File
import java.nio.file.{Path, Paths}
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Using
import scala.jdk.OptionConverters.*

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
      .addConfigTransforms(
        (_, conf) =>
          CNNodeConfigTransforms.updateAllSvAppConfigs((name, c) =>
            if (name.endsWith("Local")) {
              c.copy(
                onboarding = Some(
                  DomainMigration(
                    c.onboarding.value.name,
                    (migrationDumpDir(
                      name.stripSuffix("Local")
                    ) / "domain_migration_dump.json").path,
                  )
                )
              )
            } else
              c.copy(
                domainMigrationDumpPath =
                  Some((migrationDumpDir(name) / "domain_migration_dump.json").path)
              )
          )(conf),
        // TODO(#9014) Consider keeping this running and instead
        // making the test check history instead of balance once our
        // stores handle hard domain migrations properly.
        (_, conf) =>
          CNNodeConfigTransforms.updateAllAutomationConfigs(
            _.withPausedTrigger[SvRewardTrigger]
          )(conf),
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
            s"schedule domain migration"
          ) {
            // Ideally we'd like the config to take effect immediately. However, we
            // can only schedule configs in the future and this is enforced at the Daml level.
            // So we pick a date that is far enough in the future that we can complete the voting process
            // before it is reached but close enough that we don't need to wait for long.
            // 12 seconds seems to work well empirically.
            val scheduledTime = Instant.now().plus(12, ChronoUnit.SECONDS)
            scheduleDomainMigration(
              sv1Backend,
              Seq(sv2Backend, sv3Backend),
              Some(new DomainUpgradeSchedule(scheduledTime, 1L)),
            )
          },
          // reset to not crash other tests
          {
            allNodes.foreach { node =>
              node.oldBackend.svcAutomation
                .trigger[DomainUpgradeTrigger]
                .pause()
                .futureValue
            }
            clue(
              s"reset maxRatePerParticipant to ${domainDynamicParams.maxRatePerParticipant} to not crash other tests"
            ) {
              changeDomainRatePerParticipant(
                allNodes.map(_.oldBackend.appState.participantAdminConnection),
                domainDynamicParams.maxRatePerParticipant,
              )
            }
            deleteDirectoryRecursively(migrationDumpDir.toFile)
          },
        ) {

          withClueAndLog("dump has be written in the configured location.") {
            eventually(timeUntilSuccess = 2.minute, maxPollInterval = 2.second) {
              allNodes.foreach { node =>
                (migrationDumpDir(
                  node.oldBackend.name
                ) / "domain_migration_dump.json").path.exists shouldBe true
              }
            }
          }

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
            upgradeDomainNode1.oldBackend.triggerGlobalDomainMigrationDump()
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
            withClueAndLog("domain is unpaused on the new node") {
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

          withClueAndLog("domain is unchanged on the old nodes") {
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

          withClueAndLog("apps can be restarted") {
            withClueAndLog("sv1 restarts with dump onboarding type") {
              sv1LocalBackend.stop()
              sv1LocalBackend.startSync()
            }

            withClueAndLog("sv1 restarts without any onboarding type") {
              sv1LocalBackend.stop()
              svb("sv1LocalOnboarded").startSync()
            }
          }
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
    withClueAndLog("domain is unpaused on the new nodes") {
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

  private def getSvcRulesConfig(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      domainUpgradeSchedule: Option[DomainUpgradeSchedule],
  ) = new SvcRulesConfig(
    svcRules.payload.config.numUnclaimedRewardsThreshold,
    svcRules.payload.config.numMemberTrafficContractsThreshold,
    svcRules.payload.config.actionConfirmationTimeout,
    svcRules.payload.config.svOnboardingRequestTimeout,
    svcRules.payload.config.svOnboardingConfirmedTimeout,
    svcRules.payload.config.voteRequestTimeout,
    svcRules.payload.config.leaderInactiveTimeout,
    svcRules.payload.config.domainNodeConfigLimits,
    svcRules.payload.config.maxTextLength,
    svcRules.payload.config.initialTrafficGrant,
    svcRules.payload.config.svChallengeDeadline,
    svcRules.payload.config.globalDomain,
    domainUpgradeSchedule.toJava,
  )

  private def scheduleDomainMigration(
      svToCreateVoteRequest: SvAppBackendReference,
      svsToCastVotes: Seq[SvAppBackendReference],
      domainUpgradeSchedule: Option[DomainUpgradeSchedule],
  )(implicit
      ec: ExecutionContextExecutor
  ): Unit = {
    val svcRules = svToCreateVoteRequest.getSvcInfo().svcRules
    val action = new ARC_SvcRules(
      new SRARC_SetConfig(
        new SvcRules_SetConfig(
          getSvcRulesConfig(svcRules, domainUpgradeSchedule)
        )
      )
    )

    actAndCheck(
      "Voting on an SvcRules config change for scheduled migration", {
        def onlySetConfigVoteRequests(
            voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]]
        ) =
          voteRequests.filter {
            _.payload.action match {
              case action: ARC_SvcRules =>
                action.svcAction match {
                  case _: SRARC_SetConfig => true
                  case _ => false
                }
              case _ => false
            }
          }

        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            svToCreateVoteRequest.createVoteRequest(
              svToCreateVoteRequest.getSvcInfo().svParty.toProtoPrimitive,
              action,
              "url",
              "description",
              svToCreateVoteRequest.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
            )
          },
        )(
          "vote request has been created",
          _ => onlySetConfigVoteRequests(svToCreateVoteRequest.listVoteRequests()).loneElement,
        )

        svsToCastVotes.parTraverse { sv =>
          Future {
            clue(s"${svsToCastVotes.map(_.name)} see the vote request") {
              val svVoteRequest = eventually() {
                onlySetConfigVoteRequests(sv.listVoteRequests()).loneElement
              }
              svVoteRequest.contractId shouldBe voteRequest.contractId
            }
            clue(s"${sv.name} accepts vote") {
              eventuallySucceeds() {
                sv.castVote(
                  voteRequest.contractId,
                  true,
                  "url",
                  "description",
                )
              }
            }
          }
        }.futureValue
      },
    )(
      "observing SvcRules with changed config",
      _ => {
        val newSvcRules = svToCreateVoteRequest.getSvcInfo().svcRules
        newSvcRules.payload.config.nextScheduledDomainUpgrade.toScala shouldBe domainUpgradeSchedule
      },
    )
  }

  private def withClueAndLog[T](clueMessage: String)(fun: => T) = withClue(clueMessage) {
    clue(clueMessage)(fun)
  }

  private def deleteDirectoryRecursively(directoryToBeDeleted: File): Unit = {
    val allContents = Option(directoryToBeDeleted.listFiles)
    // listFiles return null if directoryToBeDeleted is not a directory
    allContents.foreach(_.foreach(deleteDirectoryRecursively))
    directoryToBeDeleted.delete
  }
}

object GlobalDomainMigrationIntegrationTest extends OptionValues {
  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val migrationDumpDir: Path = testDumpDir.resolve(s"domain-migration-dump")
  // Not using temp-files so test-generated outputs are easy to inspect.
  def migrationDumpDir(node: String): Path = {
    val dumpDir = migrationDumpDir.resolve(s"$node")
    if (!dumpDir.toFile.exists()) {
      dumpDir.toFile.mkdirs()
    }
    dumpDir
  }

  case class UpgradeDomainNode(
      newParticipantConnection: ParticipantAdminConnection,
      oldBackend: SvAppBackendReference,
  )

  implicit val upgradeDomainNodeReleasable: Using.Releasable[UpgradeDomainNode] =
    (resource: UpgradeDomainNode) => {
      resource.newParticipantConnection.close()
    }
}
