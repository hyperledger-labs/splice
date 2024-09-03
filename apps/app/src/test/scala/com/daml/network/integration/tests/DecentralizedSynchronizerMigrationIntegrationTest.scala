package com.daml.network.integration.tests

import better.files.File.apply
import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules
import com.daml.network.codegen.java.splice.ans.AnsRules
import com.daml.network.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_AddSv,
  SynchronizerUpgradeSchedule,
}
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import com.daml.network.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import com.daml.network.codegen.java.splice.splitwell.Group
import com.daml.network.codegen.java.splice.splitwell.balanceupdatetype.Transfer
import com.daml.network.codegen.java.splice.types.Round
import com.daml.network.codegen.java.splice.wallet.payment.ReceiverAmuletAmount
import com.daml.network.config.{
  ConfigTransforms,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  SynchronizerConfig,
}
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.environment.{
  EnvironmentImpl,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.BracketSynchronous.bracket
import com.daml.network.integration.tests.DecentralizedSynchronizerMigrationIntegrationTest.migrationDumpDir
import com.daml.network.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import com.daml.network.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import com.daml.network.splitwell.config.{SplitwellDomains, SplitwellSynchronizerConfig}
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.sv.automation.singlesv.SvNamespaceMembershipTrigger
import com.daml.network.sv.config.SvOnboardingConfig.DomainMigration
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.{
  DomainMigrationUtil,
  PackageQualifiedName,
  ProcessTestUtil,
  SpliceUtil,
  SplitwellTestUtil,
  StandaloneCanton,
  SvTestUtil,
  WalletTestUtil,
}
import com.daml.network.util.DomainMigrationUtil.testDumpDir
import com.daml.network.validator.config.{
  ValidatorDecentralizedSynchronizerConfig,
  ValidatorSynchronizerConfig,
}
import com.daml.network.wallet.automation.ExpireTransferOfferTrigger
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  ClientConfig,
  NonNegativeDuration,
  NonNegativeFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.apache.pekko.http.scaladsl.model.Uri
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}
import org.slf4j.event.Level

import java.io.File
import java.nio.file.Path
import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.Using

class DecentralizedSynchronizerMigrationIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with DomainMigrationUtil
    with StandaloneCanton
    with SplitwellTestUtil {

  override def dbsSuffix = "domain_migration"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-current.dar"

  // We want the scan instance after the migration which contains both old and new data.
  override def updateHistoryScanName = "sv1ScanLocal"

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
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
                        name = getSvName(sv),
                        dumpFilePath = Path.of(""),
                      )
                    ),
                    domainMigrationId = 1L,
                    legacyMigrationId = Some(0L),
                  )
            ) + (
              InstanceName.tryCreate(s"sv1LocalOnboarded") ->
                config
                  .svApps(InstanceName.tryCreate(s"sv1"))
                  .copy(
                    onboarding = None,
                    domainMigrationId = 1L,
                    legacyMigrationId = None,
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
                  domains = ValidatorSynchronizerConfig(global =
                    ValidatorDecentralizedSynchronizerConfig(
                      alias = DomainAlias.tryCreate("global"),
                      url = Some("http://localhost:27108"),
                    )
                  ),
                  domainMigrationId = 1L,
                )
          ) + (
            InstanceName.tryCreate("bobValidatorLocal") -> {
              val bobValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("bobValidator"))
              bobValidatorConfig
                .copy(
                  participantClient = ParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(5902)),
                    bobValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        bobValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(5901)
                        )
                    ),
                  )
                )
            }
          ) + (
            InstanceName.tryCreate("aliceValidatorLocal") -> {
              val aliceValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("aliceValidator"))
              aliceValidatorConfig
                .copy(
                  adminApi =
                    aliceValidatorConfig.adminApi.copy(internalPort = Some(Port.tryCreate(27603))),
                  scanClient = TrustSingle(url = "http://127.0.0.1:27012"),
                  domains = ValidatorSynchronizerConfig(global =
                    ValidatorDecentralizedSynchronizerConfig(
                      alias = DomainAlias.tryCreate("global"),
                      url = None,
                    )
                  ),
                  participantClient = ParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27502)),
                    aliceValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        aliceValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27501)
                        )
                    ),
                  ),
                  restoreFromMigrationDump = Some(
                    (migrationDumpDir("aliceValidator") / "domain_migration_dump.json").path
                  ),
                  onboarding = aliceValidatorConfig.onboarding.map(onboarding =>
                    onboarding.copy(
                      svClient = onboarding.svClient.copy(adminApi =
                        NetworkAppClientConfig(url = "http://localhost:27114")
                      )
                    )
                  ),
                  domainMigrationId = 1L,
                )

            }
          ) + (
            InstanceName.tryCreate("splitwellValidatorLocal") -> {
              val splitwellValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("splitwellValidator"))
              splitwellValidatorConfig
                .copy(
                  adminApi = splitwellValidatorConfig.adminApi.copy(internalPort =
                    Some(Port.tryCreate(27703))
                  ),
                  scanClient = TrustSingle(url = "http://127.0.0.1:27012"),
                  domains = ValidatorSynchronizerConfig(global =
                    ValidatorDecentralizedSynchronizerConfig(
                      alias = DomainAlias.tryCreate("global"),
                      url = Some("http://localhost:27108"),
                    )
                  ),
                  participantClient = ParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27702)),
                    splitwellValidatorConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        splitwellValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27701)
                        )
                    ),
                  ),
                  restoreFromMigrationDump = Some(
                    (migrationDumpDir("splitwellValidator") / "domain_migration_dump.json").path
                  ),
                  onboarding = splitwellValidatorConfig.onboarding.map(onboarding =>
                    onboarding.copy(
                      svClient = onboarding.svClient.copy(adminApi =
                        NetworkAppClientConfig(url = "http://localhost:27114")
                      )
                    )
                  ),
                  appManager = splitwellValidatorConfig.appManager.map(
                    _.copy(initialRegisteredApps = Map.empty)
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
            InstanceName.tryCreate("aliceWalletLocal") ->
              config
                .walletAppClients(InstanceName.tryCreate("aliceWallet"))
                .copy(
                  adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:27603")
                )
          ) + (
            InstanceName.tryCreate("charlieWalletLocal") ->
              config
                .walletAppClients(InstanceName.tryCreate("charlieWallet"))
                .copy(
                  adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:27603")
                )
          ) + (
            InstanceName.tryCreate("splitwellProviderWalletLocal") ->
              config
                .walletAppClients(InstanceName.tryCreate("splitwellProviderWallet"))
                .copy(
                  adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:27703")
                )
          ),
          splitwellApps = config.splitwellApps + (
            InstanceName.tryCreate("providerSplitwellBackendLocal") -> {
              val splitwellBackendConfig =
                config.splitwellApps(InstanceName.tryCreate("providerSplitwellBackend"))
              splitwellBackendConfig
                .copy(
                  scanClient = ScanAppClientConfig(
                    adminApi = NetworkAppClientConfig(
                      Uri("http://127.0.0.1:27012")
                    )
                  ),
                  adminApi = splitwellBackendConfig.adminApi.copy(internalPort =
                    Some(Port.tryCreate(27113))
                  ),
                  participantClient = ParticipantClientConfig(
                    ClientConfig(port = Port.tryCreate(27702)),
                    splitwellBackendConfig.participantClient.ledgerApi.copy(
                      clientConfig =
                        splitwellBackendConfig.participantClient.ledgerApi.clientConfig.copy(
                          port = Port.tryCreate(27701)
                        )
                    ),
                  ),
                  domains = SplitwellSynchronizerConfig(
                    splitwell = SplitwellDomains(
                      preferred = SynchronizerConfig(
                        alias = DomainAlias.tryCreate("global")
                      ),
                      others = Seq.empty,
                    )
                  ),
                  domainMigrationId = 1L,
                )
            }
          ),
          splitwellAppClients = config.splitwellAppClients + (
            InstanceName.tryCreate("aliceSplitwellLocal") -> {
              val aliceSplitwellAppClientConfig = config
                .splitwellAppClients(InstanceName.tryCreate("aliceSplitwell"))
              aliceSplitwellAppClientConfig.copy(
                scanClient = ScanAppClientConfig(
                  adminApi = NetworkAppClientConfig(
                    Uri("http://127.0.0.1:27012")
                  )
                ),
                adminApi =
                  aliceSplitwellAppClientConfig.adminApi.copy(url = Uri("http://127.0.0.1:27113")),
                participantClient = ParticipantClientConfig(
                  ClientConfig(port = Port.tryCreate(27502)),
                  aliceSplitwellAppClientConfig.participantClient.ledgerApi.copy(
                    clientConfig =
                      aliceSplitwellAppClientConfig.participantClient.ledgerApi.clientConfig.copy(
                        port = Port.tryCreate(27501)
                      )
                  ),
                ),
              )
            }
          ),
        )
      })
      .addConfigTransforms((_, conf) =>
        (ConfigTransforms
          .bumpSomeSvAppPortsBy(
            22_000,
            Seq("sv1Local", "sv1LocalOnboarded", "sv2Local", "sv3Local", "sv4Local"),
          ) compose
          ConfigTransforms.bumpSomeSvAppCantonDomainPortsBy(
            22_000,
            Seq("sv1Local", "sv1LocalOnboarded", "sv2Local", "sv3Local", "sv4Local"),
          )
          compose
          ConfigTransforms
            .bumpSomeScanAppPortsBy(22_000, Seq("sv1ScanLocal")) compose
          ConfigTransforms
            .bumpSomeValidatorAppPortsBy(22_000, Seq("sv1ValidatorLocal")))(conf)
      )
      .addConfigTransform(
        // update validator app config for the aliceValidator and splitwellValidator to set the migrationDumpPath
        (_, conf) =>
          ConfigTransforms.updateAllValidatorConfigs((name, validatorConfig) =>
            if (name == "aliceValidator" || name == "splitwellValidator")
              validatorConfig.copy(
                domainMigrationDumpPath =
                  Some((migrationDumpDir(name) / "domain_migration_dump.json").path)
              )
            else validatorConfig
          )(conf)
      )
      .addConfigTransforms(
        (_, conf) =>
          ConfigTransforms.updateAllSvAppConfigs((name, c) =>
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
            _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
          )(conf),
      )
      .withManualStart
      // TODO (#10859) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#10859) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "migrate global domain to new nodes with downtime" in { implicit env =>
    import env.environment.scheduler
    import env.executionContext
    val retryProvider = new RetryProvider(
      loggerFactory,
      ProcessingTimeout(),
      new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds)),
      NoOpMetricsFactory,
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
        assertInRange(
          sv1WalletClient.balance().unlockedQty,
          (walletUsdToAmulet(1000), walletUsdToAmulet(2000)),
        )
        countTapsFromScan(sv1ScanBackend, walletUsdToAmulet(1337)) shouldBe 1
      },
    )

    clue("All sequencers are registered") {
      eventually() {
        inside(sv1ScanBackend.listDsoSequencers()) {
          case Seq(DomainSequencers(domainId, sequencers)) =>
            domainId shouldBe decentralizedSynchronizerId
            sequencers should have size 4
            sequencers.foreach { sequencer =>
              sequencer.migrationId shouldBe 0
            }
        }
      }
    }

    def startValidatorAndTapAmulet(
        validatorBackend: ValidatorAppBackendReference,
        walletClient: WalletAppClientReference,
        tapAmount: BigDecimal = 50.0,
        expectedAmulets: Range = 50 to 50,
    ) = {
      startAllSync(validatorBackend)
      val walletUserParty = onboardWalletUser(walletClient, validatorBackend)
      walletClient.tap(tapAmount)
      withClueAndLog(s"${validatorBackend.name} has tapped a amulet") {
        checkWallet(
          walletUserParty,
          walletClient,
          Seq((walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))),
        )
      }
      validatorBackend.participantClientWithAdminToken.health.status.isActive shouldBe Some(
        true
      )
      walletUserParty
    }

    val bobValidatorLocalBackend: ValidatorAppBackendReference = v("bobValidatorLocal")

    withCanton(
      Seq(
        testResourcesPath / "unavailable-validator-topology-canton.conf"
      ),
      Seq(),
      "stop-bob-validator-before-domain-migration",
      "VALIDATOR_ADMIN_USER" -> bobValidatorLocalBackend.config.ledgerApiUser,
    ) {
      startValidatorAndTapAmulet(bobValidatorLocalBackend, bobWalletClient)
      bobValidatorLocalBackend.stop()
    }

    withClueAndLog(
      s"the validator is no longer available"
    ) {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          bobValidatorLocalBackend.participantClientWithAdminToken.health.status
        },
        logEntries => {
          forExactly(1, logEntries) { logEntry =>
            logEntry.message should startWith(
              s"""Request failed for remote participant for `bobValidatorLocal`, with admin token. Is the server running? Did you configure the server address as 0.0.0.0? Are you using the right TLS settings? (details logged as DEBUG)
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
      extraParticipantsConfigFileName = Some("standalone-participant-extra-splitwell.conf"),
      extraParticipantsEnvMap = Map(
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> s"participant_extra_${dbsSuffix}",
        "SPLITWELL_PARTICIPANT_DB" -> s"participant_splitwell_${dbsSuffix}",
        "SPLITWELL_PARTICIPANT_ADMIN_USER" -> splitwellValidatorBackend.config.ledgerApiUser,
      ),
    )() {
      aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      val aliceUserParty = startValidatorAndTapAmulet(aliceValidatorBackend, aliceWalletClient)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val splitwellGroupKey = createSplitwellGroupAndTransfer(aliceUserParty, charlieUserParty)

      val sequencerUrlSetBeforeUpgrade =
        clue("validator should connect to all sequencer urls on the old network") {
          eventually() {
            val urlSet =
              getSequencerUrlSet(
                aliceValidatorBackend.participantClientWithAdminToken,
                decentralizedSynchronizerAlias,
              )
            urlSet should have size 4
            urlSet
          }
        }

      Using.resources(
        createUpgradeNode(
          1,
          sv1Backend,
          sv1LocalBackend,
          retryProvider,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          2,
          sv2Backend,
          sv2LocalBackend,
          retryProvider,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          3,
          sv3Backend,
          sv3LocalBackend,
          retryProvider,
          env.environment.config.monitoring.logging.api,
        ),
        createUpgradeNode(
          4,
          sv4Backend,
          sv4LocalBackend,
          retryProvider,
          env.environment.config.monitoring.logging.api,
        ),
      ) {
        case (
              upgradeSynchronizerNode1,
              upgradeSynchronizerNode2,
              upgradeSynchronizerNode3,
              upgradeSynchronizerNode4,
            ) =>
          val allNodes =
            Seq(
              upgradeSynchronizerNode1,
              upgradeSynchronizerNode2,
              upgradeSynchronizerNode3,
              upgradeSynchronizerNode4,
            )
          val testDsoParty = dsoParty(env)
          val dsoPartyDecentralizedNamespace = testDsoParty.uid.namespace
          val domainDynamicParams =
            sv1Backend.participantClientWithAdminToken.topology.domain_parameters
              .list(
                decentralizedSynchronizerId.filterString
              )
              .headOption
              .value
              .item
          val majorityUpgradeNodes =
            Seq(upgradeSynchronizerNode2, upgradeSynchronizerNode3, upgradeSynchronizerNode4)

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
              val scheduledTime = Instant
                .now()
                .truncatedTo(
                  ChronoUnit.MICROS
                )
                .plus(12, ChronoUnit.SECONDS)
              scheduleDomainMigration(
                sv1Backend,
                Seq(sv2Backend, sv3Backend, sv4Backend),
                Some(new SynchronizerUpgradeSchedule(scheduledTime, 1L)),
              )
            },
            // reset to not crash other tests
            {
              clue(
                s"reset confirmationRequestsMaxRate to ${domainDynamicParams.confirmationRequestsMaxRate} to not crash other tests"
              ) {
                changeDomainRatePerParticipant(
                  allNodes.map(_.oldParticipantConnection),
                  domainDynamicParams.confirmationRequestsMaxRate,
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
                Seq(aliceValidatorBackend, splitwellValidatorBackend).foreach { validator =>
                  (migrationDumpDir(
                    validator.name
                  ) / "domain_migration_dump.json").path.exists shouldBe true
                }
              }
            }

            withClueAndLog("stopping old apps") {
              stopAllAsync(
                sv1Backend,
                sv2Backend,
                sv3Backend,
                sv4Backend,
                sv1ValidatorBackend,
                sv2ValidatorBackend,
                sv3ValidatorBackend,
                sv4ValidatorBackend,
                sv1ScanBackend,
                sv2ScanBackend,
                splitwellBackend,
                splitwellValidatorBackend,
                aliceValidatorBackend,
              ).futureValue
            }

            withClueAndLog("starting sv2-4 upgraded nodes") {
              startAllSync(
                sv2LocalBackend,
                sv3LocalBackend,
                sv4LocalBackend,
              )
            }

            checkMigrateDomainOnNodes(majorityUpgradeNodes, testDsoParty)

            clue("SvNamespaceMembershipTrigger is running") {
              loggerFactory.assertEventuallyLogsSeq_(SuppressionRule.LevelAndAbove(Level.DEBUG))(
                logs =>
                  forAtLeast(1, logs) {
                    _.loggerName should include("SvNamespaceMembershipTrigger")
                  }
              )
            }

            // Pause the triggers because they'll otherwise try to fight the manual modification of the decentralized namespace.
            Seq(sv2LocalBackend, sv3LocalBackend, sv4LocalBackend).foreach { sv =>
              sv.dsoAutomation.trigger[SvNamespaceMembershipTrigger].pause().futureValue
            }

            val namespaceChangeResult =
              withClueAndLog("decentralized namespace can be modified on the new domain") {
                majorityUpgradeNodes.parTraverse { upgradeNode =>
                  val connection = upgradeNode.newParticipantConnection
                  for {
                    id <- connection.getId()
                    result <- connection
                      .ensureDecentralizedNamespaceDefinitionOwnerChangeProposalAccepted(
                        "keep just sv1",
                        decentralizedSynchronizerId,
                        dsoPartyDecentralizedNamespace,
                        _ => NonEmpty(Set, sv1Party.uid.namespace),
                        id.namespace.fingerprint,
                        RetryFor.WaitingOnInitDependency,
                      )
                  } yield result
                }.futureValue
              }

            withClueAndLog("migrate the late joining node") {
              // sv1 is the sv1 so specifically join it later to validate our replay
              sv1LocalBackend.startSync()

              val changeSerial = namespaceChangeResult.map(_.base.serial).max
              // reconciliation loops will restore the removed namespaces once the sv1 starts
              eventuallySucceeds(timeUntilSuccess = 2.minute, maxPollInterval = 2.second) {
                upgradeSynchronizerNode1.newParticipantConnection
                  .getDecentralizedNamespaceDefinition(
                    decentralizedSynchronizerId,
                    dsoPartyDecentralizedNamespace,
                  )
                  .futureValue
                  .base
                  .serial should be >= changeSerial
              }
              withClueAndLog("domain is unpaused on the new node") {
                eventuallySucceeds() {
                  upgradeSynchronizerNode1.newParticipantConnection
                    .getDomainParametersState(
                      decentralizedSynchronizerId
                    )
                    .futureValue
                    .mapping
                    .parameters
                    .confirmationRequestsMaxRate should be > NonNegativeInt.zero
                }
              }
            }

            withClueAndLog("domain is unchanged on the old nodes") {
              eventuallySucceeds() {
                upgradeSynchronizerNode1.oldParticipantConnection
                  .getDecentralizedNamespaceDefinition(
                    decentralizedSynchronizerId,
                    dsoPartyDecentralizedNamespace,
                  )
                  .futureValue
                  .mapping
                  .owners
                  .forgetNE should have size 4
                upgradeSynchronizerNode1.oldParticipantConnection
                  .getDomainParametersState(
                    decentralizedSynchronizerId
                  )
                  .futureValue
                  .mapping
                  .parameters
                  .confirmationRequestsMaxRate should be(NonNegativeInt.zero)
              }
            }

            startAllSync(
              sv1ScanLocalBackend,
              sv1ValidatorLocalBackend,
              v("splitwellValidatorLocal"),
              sw("providerSplitwellBackendLocal"),
            )

            val aliceValidatorLocal = v("aliceValidatorLocal")
            val aliceWalletLocalClient = uwc("aliceWalletLocal")
            withClueAndLog("validator can migrate to the new domain") {
              val validatorThatMigrates = aliceValidatorLocal
              startValidatorAndTapAmulet(
                validatorThatMigrates,
                aliceWalletLocalClient,
                // tap 2 times (100) minus splitwell transfer (42)
                expectedAmulets = 57 to 58,
              )
            }
            withClueAndLog("User automation works before user is reonboarded") {
              val aliceUser = aliceWalletLocalClient.config.ledgerApiUser
              val charlieUser = uwc("charlieWalletLocal").config.ledgerApiUser
              clue("Pause expiration so we can catch the contract") {
                aliceValidatorLocal
                  .userWalletAutomation(aliceUser)
                  .futureValue
                  .trigger[ExpireTransferOfferTrigger]
                  .pause()
                  .futureValue
                aliceValidatorLocal
                  .userWalletAutomation(charlieUser)
                  .futureValue
                  .trigger[ExpireTransferOfferTrigger]
                  .pause()
                  .futureValue
              }
              actAndCheck(
                "Alice creates almost expired transfer",
                aliceWalletLocalClient.createTransferOffer(
                  charlieUserParty,
                  10,
                  "transfer 10 amulets to Charlie",
                  CantonTimestamp.now().plus(Duration.ofSeconds(2)),
                  UUID.randomUUID.toString,
                ),
              )(
                "Alice sees expired transfer offer",
                _ => aliceWalletLocalClient.listTransferOffers() should have length 1,
              )
              actAndCheck(
                "Unpause Charlie's expiration automation",
                aliceValidatorLocal
                  .userWalletAutomation(charlieUser)
                  .futureValue
                  .trigger[ExpireTransferOfferTrigger]
                  .resume(),
              )(
                "Transfer offer was expired by Charlie's automation",
                _ => {
                  aliceWalletLocalClient.listTransferOffers() should have length 0
                },
              )
            }

            clue(s"scan should expose sequencers in both pre-upgrade or upgraded domain") {
              eventually() {
                inside(sv1ScanLocalBackend.listDsoSequencers()) {
                  case Seq(DomainSequencers(domainId, sequencers)) =>
                    domainId shouldBe decentralizedSynchronizerId
                    sequencers.foreach { sequencer =>
                      if (sequencer.migrationId != 0 && sequencer.migrationId != 1)
                        throw new RuntimeException(
                          s"Expected sequencer migrationId to be either 0 or 1, but got ${sequencer.migrationId}"
                        )
                    }
                    sequencers.map { sequencer =>
                      (sequencer.migrationId, sequencer.url)
                    }.toSet shouldBe Set(
                      (0L, getPublicSequencerUrl(sv1Backend)),
                      (1L, getPublicSequencerUrl(sv1LocalBackend)),
                      (0L, getPublicSequencerUrl(sv2Backend)),
                      (1L, getPublicSequencerUrl(sv2LocalBackend)),
                      (0L, getPublicSequencerUrl(sv3Backend)),
                      (1L, getPublicSequencerUrl(sv3LocalBackend)),
                      (0L, getPublicSequencerUrl(sv4Backend)),
                      (1L, getPublicSequencerUrl(sv4LocalBackend)),
                    )
                }
              }
            }

            clue(s"validator should connect to sequencers in upgraded domain") {
              eventually() {
                val sequencerUrlSet = getSequencerUrlSet(
                  aliceValidatorLocal.participantClientWithAdminToken,
                  decentralizedSynchronizerAlias,
                )
                sequencerUrlSet should have size 4
                sequencerUrlSet.intersect(sequencerUrlSetBeforeUpgrade) shouldBe Set.empty
              }
            }

            startValidatorAndTapAmulet(
              v("splitwellValidatorLocal"),
              uwc("splitwellProviderWalletLocal"),
            )

            startAllSync(
              sw("providerSplitwellBackendLocal")
            )

            sv1LocalBackend.getDsoInfo().dsoRules.payload.svs.size() shouldBe 4

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
                inside(listTapsFromScan(sv1ScanLocalBackend, sv1Party, 1337, 1338)) {
                  case Seq(formerTap, laterTap) =>
                    BigDecimal(formerTap.amuletAmount) shouldBe BigDecimal(1337)
                    BigDecimal(laterTap.amuletAmount) shouldBe BigDecimal(1338)
                }
              },
            )

            actAndCheck(
              "validate domain with create VoteRequest",
              sv1LocalBackend.createVoteRequest(
                sv1Party.toProtoPrimitive,
                new ARC_DsoRules(
                  new SRARC_AddSv(
                    new DsoRules_AddSv(
                      "bob",
                      "Bob",
                      SvUtil.DefaultSV1Weight,
                      "bob-participant-id",
                      new Round(42),
                    )
                  )
                ),
                "url",
                "description",
                sv1LocalBackend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              ),
            )(
              "VoteRequest and Vote should be there",
              _ =>
                inside(sv1LocalBackend.listVoteRequests()) { case Seq(onlyReq) =>
                  sv1LocalBackend
                    .lookupVoteRequest(
                      onlyReq.contractId
                    )
                    .payload
                    .votes should have size 1
                },
            )

            withClueAndLog("ACS snapshot includes the ACS import") {
              val dsoInfo = sv1ScanLocalBackend.getDsoInfo()
              val ansRules = sv1ScanLocalBackend.getAnsRules()

              val snapshotRecordTime = sv1ScanLocalBackend.forceAcsSnapshotNow()
              val snapshot = sv1ScanLocalBackend
                .getAcsSnapshotAt(
                  snapshotRecordTime,
                  migrationId = 1L,
                  pageSize = 1000,
                  templates = Some(
                    Vector(DsoRules.TEMPLATE_ID, AmuletRules.TEMPLATE_ID, AnsRules.TEMPLATE_ID).map(
                      PackageQualifiedName(_)
                    )
                  ),
                )
                .valueOrFail(s"Snapshot was just taken but not returned.")

              snapshot.createdEvents.map(_.contractId) should contain(
                dsoInfo.dsoRules.contract.contractId
              )
              snapshot.createdEvents.map(_.contractId) should contain(
                dsoInfo.amuletRules.contract.contractId
              )
              snapshot.createdEvents.map(_.contractId) should contain(
                ansRules.contractId.contractId
              )
            }

            withClueAndLog("3rd party app works after domain migration") {
              val (_, paymentRequest) =
                actAndCheck(timeUntilSuccess = 40.seconds, maxPollInterval = 1.second)(
                  "alice initiates transfer after domain migration",
                  rsw("aliceSplitwellLocal").initiateTransfer(
                    splitwellGroupKey,
                    Seq(
                      new ReceiverAmuletAmount(
                        charlieUserParty.toProtoPrimitive,
                        BigDecimal(43.0).bigDecimal,
                      )
                    ),
                  ),
                )(
                  "alice sees payment request",
                  _ => {
                    getSingleRequestOnDecentralizedSynchronizer(uwc("aliceWalletLocal"))
                  },
                )

              actAndCheck(
                "alice initiates payment accept request after domain migration",
                uwc("aliceWalletLocal").acceptAppPaymentRequest(paymentRequest.contractId),
              )(
                "alice sees balance update",
                _ =>
                  inside(rsw("aliceSplitwellLocal").listBalanceUpdates(splitwellGroupKey)) {
                    case Seq(update1, update2) =>
                      Seq(update1, update2).foreach { update =>
                        aliceValidatorLocal.participantClient.ledger_api_extensions.acs
                          .lookup_contract_domain(
                            aliceUserParty,
                            Set(update.contractId.contractId),
                          ) shouldBe Map(
                          update.contractId.contractId -> decentralizedSynchronizerId
                        )
                      }
                      inside(update1.payload.update) { case transfer: Transfer =>
                        transfer.amount shouldBe BigDecimal("43.0000000000").bigDecimal
                      }
                      inside(update2.payload.update) { case transfer: Transfer =>
                        transfer.amount shouldBe BigDecimal("42.0000000000").bigDecimal
                      }
                  },
              )
            }

            withClueAndLog("apps can be restarted") {
              withClueAndLog("sv1 restarts with dump onboarding type") {
                sv1LocalBackend.stop()
                sv1LocalBackend.startSync()
              }

              withClueAndLog("sv1 restarts without any onboarding type") {
                sv1LocalBackend.stop()
                svb("sv1LocalOnboarded").startSync()

                clue(s"scan should expose sequencers in upgraded domain only for sv1") {
                  eventually() {
                    inside(sv1ScanLocalBackend.listDsoSequencers()) {
                      case Seq(DomainSequencers(domainId, sequencers)) =>
                        domainId shouldBe decentralizedSynchronizerId
                        sequencers.map { sequencer =>
                          (sequencer.migrationId, sequencer.url)
                        }.toSet shouldBe Set(
                          (1L, getPublicSequencerUrl(sv1LocalBackend)),
                          (0L, getPublicSequencerUrl(sv2Backend)),
                          (1L, getPublicSequencerUrl(sv2LocalBackend)),
                          (0L, getPublicSequencerUrl(sv3Backend)),
                          (1L, getPublicSequencerUrl(sv3LocalBackend)),
                          (0L, getPublicSequencerUrl(sv4Backend)),
                          (1L, getPublicSequencerUrl(sv4LocalBackend)),
                        )
                    }
                  }
                }
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
      env: SpliceTestConsoleEnvironment,
      ec: ExecutionContextExecutor,
  ): Unit = {
    nodes
      .parTraverse { node =>
        val id = node.getId().futureValue
        node
          .ensureDomainParameters(
            decentralizedSynchronizerId,
            _.tryUpdate(confirmationRequestsMaxRate = rate),
            signedBy = id.namespace.fingerprint,
          )
      }
      .futureValue
      .discard
  }

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: BigDecimal) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.amuletAmount)).contains(tapAmount)
    )
  }

  private def listTapsFromScan(
      scan: ScanAppBackendReference,
      owner: PartyId,
      fromTapAmount: Double,
      toTapAmount: Double,
  ) = {
    listTransactionsFromScan(scan)
      .flatMap(_.tap)
      .filter { tx =>
        tx.amuletOwner == owner.toProtoPrimitive &&
        BigDecimal(tx.amuletAmount) >= BigDecimal(fromTapAmount) &&
        BigDecimal(tx.amuletAmount) <= BigDecimal(toTapAmount)
      }
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

  private def getSequencerUrlSet(
      participantConnection: ParticipantClientReference,
      domainAlias: DomainAlias,
  ): Set[String] = {
    val sequencerConnections = participantConnection.domains
      .config(domainAlias)
      .value
      .sequencerConnections
    (for {
      conn <- sequencerConnections.aliasToConnection.values
      endpoint <- conn match {
        case GrpcSequencerConnection(endpoints, _, _, _) => endpoints
      }
    } yield endpoint.toString).toSet
  }

  private def createSplitwellGroupAndTransfer(
      aliceUserParty: PartyId,
      charlieUserParty: PartyId,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    startAllSync(
      splitwellValidatorBackend,
      splitwellBackend,
    )
    clue("setup splitwell group") {

      val group = "group1"
      // The provider's wallet is auto-onboarded, so we just need to wait for it to be ready
      waitForWalletUser(splitwellWalletClient)

      splitwellBackend.getProviderPartyId()

      clue("setup install contracts") {
        Seq(
          (aliceSplitwellClient, aliceUserParty),
          (charlieSplitwellClient, charlieUserParty),
        ).foreach { case (splitwell, party) =>
          createSplitwellInstalls(splitwell, party)
        }
      }

      actAndCheck("create 'group1'", aliceSplitwellClient.requestGroup(group))(
        "Alice sees 'group1'",
        _ => aliceSplitwellClient.listGroups() should have size 1,
      )

      // Wait for the group contract to be visible to Alice's Ledger API
      aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
        .awaitJava(Group.COMPANION)(aliceUserParty)

      val (_, invite) = actAndCheck(
        "create a generic invite for 'group1'",
        aliceSplitwellClient.createGroupInvite(
          group
        ),
      )(
        "alice observes the invite",
        _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
      )

      actAndCheck("charlie asks to join 'group1'", charlieSplitwellClient.acceptInvite(invite))(
        "alice sees the accepted invite",
        _ => aliceSplitwellClient.listAcceptedGroupInvites(group) should not be empty,
      )

      actAndCheck(
        "charlie joins 'group1'",
        inside(aliceSplitwellClient.listAcceptedGroupInvites(group)) { case Seq(accepted) =>
          aliceSplitwellClient.joinGroup(accepted.contractId)
        },
      )(
        "charlie is in 'group1'",
        _ => {
          charlieSplitwellClient.listGroups() should have size 1
          aliceSplitwellClient.listAcceptedGroupInvites(group) should be(empty)
        },
      )

      val key = HttpSplitwellAppClient.GroupKey(
        group,
        aliceUserParty,
      )

      clue("grant featured app right to splitwell provider") {
        grantFeaturedAppRight(splitwellWalletClient)
      }

      val (_, paymentRequest) =
        actAndCheck(timeUntilSuccess = 40.seconds, maxPollInterval = 1.second)(
          "alice initiates transfer",
          aliceSplitwellClient.initiateTransfer(
            key,
            Seq(
              new ReceiverAmuletAmount(
                charlieUserParty.toProtoPrimitive,
                BigDecimal(42.0).bigDecimal,
              )
            ),
          ),
        )(
          "alice sees payment request",
          _ => {
            getSingleRequestOnDecentralizedSynchronizer(aliceWalletClient)
          },
        )

      actAndCheck(
        "alice initiates payment accept request on global domain",
        aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
      )(
        "alice sees balance update on splitwell domain",
        _ =>
          inside(aliceSplitwellClient.listBalanceUpdates(key)) { case Seq(update) =>
            aliceValidatorBackend.participantClient.ledger_api_extensions.acs
              .lookup_contract_domain(
                aliceUserParty,
                Set(update.contractId.contractId),
              ) shouldBe Map(
              update.contractId.contractId -> decentralizedSynchronizerId
            )
          },
      )
      key
    }
  }

  private def getPublicSequencerUrl(sv: SvAppBackendReference): String =
    sv.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
}

object DecentralizedSynchronizerMigrationIntegrationTest extends OptionValues {
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
