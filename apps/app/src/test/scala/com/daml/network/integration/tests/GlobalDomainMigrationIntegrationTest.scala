package com.daml.network.integration.tests

import better.files.File.apply
import cats.implicits.{catsSyntaxOptionId, catsSyntaxParallelTraverse1}
import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cn.svcrules.{DomainUpgradeSchedule, SvcRules_AddMember}
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_AddMember
import com.daml.network.config.{
  CNNodeConfigTransforms,
  CNParticipantClientConfig,
  NetworkAppClientConfig,
}
import com.daml.network.config.CNNodeConfigTransforms.{updateAutomationConfig, ConfigurableApp}
import com.daml.network.console.{
  ScanAppBackendReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
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
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.BracketSynchronous.bracket
import com.daml.network.integration.tests.GlobalDomainMigrationIntegrationTest.migrationDumpDir
import com.daml.network.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import com.daml.network.sv.automation.singlesv.SvRewardTrigger
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.config.SvOnboardingConfig.DomainMigration
import com.daml.network.sv.migration.GlobalDomainMigrationTrigger
import com.daml.network.sv.util.SvUtil.dummySvRewardWeight
import com.daml.network.util.{
  DomainMigrationUtil,
  ProcessTestUtil,
  StandaloneCanton,
  SvTestUtil,
  WalletTestUtil,
}
import com.daml.network.util.DomainMigrationUtil.testDumpDir
import com.daml.network.validator.config.{ValidatorDomainConfig, ValidatorGlobalDomainConfig}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{DiscardOps, DomainAlias}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{ClientConfig, NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}
import org.slf4j.event.Level

import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.Using

class GlobalDomainMigrationIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with DomainMigrationUtil
    with StandaloneCanton {

  override def dbsSuffix = "domain_migration"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withZeroSequencerAvailabilityDelay
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps ++
            Seq(1, 2, 3, 4).map(sv =>
              InstanceName.tryCreate(s"sv${sv}Local") ->
                config
                  .svApps(InstanceName.tryCreate(s"sv$sv"))
                  .copy(
                    onboarding = Some(
                      DomainMigration(
                        name = s"Canton-Foundation-$sv",
                        dumpFilePath = Path.of(""),
                      )
                    ),
                    domains = SvDomainConfig(global =
                      SvGlobalDomainConfig(
                        alias = DomainAlias.tryCreate("global"),
                        // changing the domain config since for a domain migration SVs connect directly to their own sequencer instead of SV1's sequencer.
                        url = s"http://localhost:27${sv}08",
                      )
                    ),
                    domainMigrationId = 1L,
                  )
            ) + (
              InstanceName.tryCreate(s"sv1LocalOnboarded") ->
                config
                  .svApps(InstanceName.tryCreate(s"sv1"))
                  .copy(
                    onboarding = None,
                    domainMigrationId = 1L,
                    domains = SvDomainConfig(global =
                      SvGlobalDomainConfig(
                        alias = DomainAlias.tryCreate("global"),
                        url = s"http://localhost:27108",
                      )
                    ),
                  )
            ),
          scanApps = config.scanApps + (
            InstanceName.tryCreate("sv1ScanLocal") ->
              config
                .scanApps(InstanceName.tryCreate("sv1Scan"))
                .copy(domainMigrationId = 1L)
          ),
          validatorApps = config.validatorApps + (
            InstanceName.tryCreate("sv1ValidatorLocal") ->
              config
                .validatorApps(InstanceName.tryCreate("sv1Validator"))
                .copy(
                  scanClient = TrustSingle(url = "http://127.0.0.1:27012"),
                  domains = ValidatorDomainConfig(global =
                    ValidatorGlobalDomainConfig(
                      alias = DomainAlias.tryCreate("global"),
                      url = Some("http://localhost:27109"),
                    )
                  ),
                  domainMigrationId = 1L,
                )
          ) + (
            InstanceName.tryCreate("aliceValidatorLocal") -> {
              val aliceValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("aliceValidator"))
              aliceValidatorConfig
                .copy(
                  participantClient = CNParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(5902)),
                    aliceValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        aliceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(5901)
                        )
                    ),
                  ),
                  domainMigrationId = 1L,
                )
            }
          ) + (
            InstanceName.tryCreate("bobValidatorLocal") -> {
              val bobValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("bobValidator"))
              bobValidatorConfig
                .copy(
                  adminApi =
                    bobValidatorConfig.adminApi.copy(internalPort = Some(Port.tryCreate(27603))),
                  scanClient = TrustSingle(url = "http://127.0.0.1:27012"),
                  domains = ValidatorDomainConfig(global =
                    ValidatorGlobalDomainConfig(
                      alias = DomainAlias.tryCreate("global"),
                      url = Some("http://localhost:27108"),
                    )
                  ),
                  participantClient = CNParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27502)),
                    bobValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        bobValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27501)
                        )
                    ),
                  ),
                  restoreFromMigrationDump = Some(
                    (migrationDumpDir("bobValidator") / "domain_migration_dump.json").path
                  ),
                  onboarding = bobValidatorConfig.onboarding.map(onboarding =>
                    onboarding.copy(
                      svClient = onboarding.svClient.copy(adminApi =
                        NetworkAppClientConfig(url = "http://localhost:27114")
                      )
                    )
                  ),
                  domainMigrationId = 1L,
                )

            }
          ),
          walletAppClients = config.walletAppClients + (
            InstanceName.tryCreate("sv1WalletLocal") ->
              config
                .walletAppClients(InstanceName.tryCreate("sv1Wallet"))
                .copy(
                  adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:27103")
                )
          ) + (
            InstanceName.tryCreate("bobWalletLocal") ->
              config
                .walletAppClients(InstanceName.tryCreate("bobWallet"))
                .copy(
                  adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:27603")
                )
          ),
        )
      })
      .addConfigTransforms((_, conf) =>
        (CNNodeConfigTransforms
          .bumpSomeSvAppPortsBy(
            22_000,
            Seq("sv1Local", "sv1LocalOnboarded", "sv2Local", "sv3Local", "sv4Local"),
          ) compose
          CNNodeConfigTransforms
            .bumpSomeScanAppPortsBy(22_000, Seq("sv1ScanLocal")) compose
          CNNodeConfigTransforms
            .bumpSomeValidatorAppPortsBy(22_000, Seq("sv1ValidatorLocal")))(conf)
      )
      .addConfigTransform(
        // update validator app config for the bobValidator to set the migrationDumpPath
        (_, conf) =>
          CNNodeConfigTransforms.updateAllValidatorConfigs((name, validatorConfig) =>
            if (name == "bobValidator")
              validatorConfig.copy(
                domainMigrationPath =
                  Some((migrationDumpDir(name) / "domain_migration_dump.json").path)
              )
            else validatorConfig
          )(conf)
      )
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
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[SvRewardTrigger]
          )(conf),
      )
      .addConfigTransforms(
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInSv(c) }
      )
      .addConfigTransformsToFront(
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) }
      )
      .addConfigTransformsToFront(
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInValidator(c) }
      )
      .withManualStart

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

    startAllSync(
      sv1ScanBackend, // Used by SV 1 & 3
      sv2ScanBackend, // Used by SV 2 & 4
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

    def startValidatorAndTapCoin(
        validatorBackend: ValidatorAppBackendReference,
        walletClient: WalletAppClientReference,
        tapAmount: BigDecimal = 50.0,
        expectedCoins: Range = 50 to 50,
    ) = {
      startAllSync(validatorBackend)
      val walletUserParty = onboardWalletUser(walletClient, validatorBackend)
      walletClient.tap(tapAmount)
      withClueAndLog(s"${validatorBackend.name} has tapped a coin") {
        checkWallet(walletUserParty, walletClient, Seq((expectedCoins.start, expectedCoins.end)))
      }
      validatorBackend.participantClientWithAdminToken.health.status.isActive shouldBe Some(
        true
      )
    }

    val aliceValidatorLocalBackend: ValidatorAppBackendReference = v("aliceValidatorLocal")

    withCanton(
      Seq(
        testResourcesPath / "unavailable-validator-topology-canton.conf"
      ),
      Seq(),
      "stop-alice-validator-before-domain-migration",
      "VALIDATOR_ADMIN_USER" -> aliceValidatorLocalBackend.config.ledgerApiUser,
    ) {
      startValidatorAndTapCoin(aliceValidatorLocalBackend, aliceWalletClient)
      aliceValidatorLocalBackend.stop()
    }

    withClueAndLog(
      s"the validator is no longer available"
    ) {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          aliceValidatorLocalBackend.participantClientWithAdminToken.health.status
        },
        logEntries => {
          forExactly(1, logEntries) { logEntry =>
            logEntry.message should startWith(
              s"""Request failed for remote participant for `aliceValidatorLocal`, with admin token. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (details logged as DEBUG)
                 |  GrpcServiceUnavailable: UNAVAILABLE/io exception""".stripMargin
            )
          }
        },
      )
    }

    withCantonSvNodes(
      (
        Some(sv1LocalBackend),
        Some(sv2LocalBackend),
        Some(sv3LocalBackend),
        Some(sv4LocalBackend),
      ),
      logSuffix = "global-domain-migration",
      autoInit = false,
      extraParticipant = true,
      extraParticipantUser = bobValidatorBackend.config.ledgerApiUser.some,
    )() {
      startValidatorAndTapCoin(bobValidatorBackend, bobWalletClient)
      Using.resources(
        createUpgradeNode(
          1,
          sv1Backend,
          sv1LocalBackend,
          retryProvider,
          wallClock,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          2,
          sv2Backend,
          sv2LocalBackend,
          retryProvider,
          wallClock,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          3,
          sv3Backend,
          sv3LocalBackend,
          retryProvider,
          wallClock,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          4,
          sv4Backend,
          sv4LocalBackend,
          retryProvider,
          wallClock,
          env.environment.config.monitoring.logging.api,
        ),
      ) { case (upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4) =>
        val allNodes =
          Seq(upgradeDomainNode1, upgradeDomainNode2, upgradeDomainNode3, upgradeDomainNode4)
        val svcPartyDecentralizedNamespace =
          sv1Backend.appState.svcStore.key.svcParty.uid.namespace

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
            // pausing DomainUpgradeTrigger of all all old SV to avoid them from setting the maxRatePerParticipant back to zero.
            allNodes.foreach { node =>
              node.oldBackend.svcAutomation
                .trigger[GlobalDomainMigrationTrigger]
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

          withClueAndLog("dump has been written in the configured location for the sv.") {
            eventually(timeUntilSuccess = 2.minute, maxPollInterval = 2.second) {
              allNodes.foreach { node =>
                (migrationDumpDir(
                  node.oldBackend.name
                ) / "domain_migration_dump.json").path.exists shouldBe true
              }
            }
          }

          withClueAndLog("dump has been written in the configured location for the validator.") {
            eventually(timeUntilSuccess = 2.minute, maxPollInterval = 2.second) {
              (migrationDumpDir(
                bobValidatorBackend.name
              ) / "domain_migration_dump.json").path.exists shouldBe true
            }
          }

          withClueAndLog("starting sv2-4 upgraded nodes") {
            startAllSync(
              sv2LocalBackend,
              sv3LocalBackend,
              sv4LocalBackend,
            )
          }

          checkMigrateDomainOnNodes(majorityUpgradeNodes)

          withClueAndLog("decentralized namespace can be modified on the new domain") {
            majorityUpgradeNodes
              .parTraverse { upgradeNode =>
                val connection = upgradeNode.newParticipantConnection
                for {
                  id <- connection.getId()
                  _ <- connection
                    .ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
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

          withClueAndLog("validator can migrate to the new domain") {
            val validatorThatMigrates = v("bobValidatorLocal")
            startValidatorAndTapCoin(
              validatorThatMigrates,
              uwc("bobWalletLocal"),
              expectedCoins = 99 to 100,
            )
          }

          sv1LocalBackend.getSvcInfo().svcRules.payload.members.size() shouldBe 4

          clue("Old wallet balance is recorded") {
            eventually() {
              assertInRange(sv1WalletLocalClient.balance().unlockedQty, (1000, 2000))
            }
          }
          clue("Old scan transaction history is recorded") {
            eventually() {
              countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual 1
              countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual 0
            }
          }
          actAndCheck("Create some new transaction history", sv1WalletLocalClient.tap(1338))(
            "New transaction history is recorded and balance is updated",
            _ => {
              countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual 1
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
                sv1LocalBackend.listVotes(
                  Vector(onlyReq.contractId.contractId)
                ) should have size 1
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

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: Double) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.coinAmount)).contains(BigDecimal(tapAmount))
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  }

  private def deleteDirectoryRecursively(directoryToBeDeleted: File): Unit = {
    val allContents = Option(directoryToBeDeleted.listFiles)
    // listFiles return null if directoryToBeDeleted is not a directory
    allContents.foreach(_.foreach(deleteDirectoryRecursively))
    directoryToBeDeleted.delete
  }
}

object GlobalDomainMigrationIntegrationTest extends OptionValues {
  val migrationDumpDir: Path = testDumpDir.resolve(s"domain-migration-dump")
  // Not using temp-files so test-generated outputs are easy to inspect.
  def migrationDumpDir(node: String): Path = {
    val dumpDir = migrationDumpDir.resolve(s"$node")
    if (!dumpDir.toFile.exists()) {
      dumpDir.toFile.mkdirs()
    }
    dumpDir
  }
}
