package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.data.{User, UserRights}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.config.{FullClientConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.ledger.api.IdentityProviderConfig
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags, PartyId}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.AnsRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_AddSv
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules,
  DsoRules_AddSv,
  SynchronizerUpgradeSchedule,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.Group
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell.balanceupdatetype.Transfer
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment.ReceiverAmuletAmount
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  NetworkAppClientConfig,
  ParticipantClientConfig,
  SynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.DecentralizedSynchronizerMigrationIntegrationTest.migrationDumpDir
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.bracket
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.scan.config.CacheConfig
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.splitwell.config.{
  SplitwellDomains,
  SplitwellSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.store.{PageLimit, TreeUpdateWithMigrationId}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  ReceiveSvRewardCouponTrigger,
  SvNamespaceMembershipTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.DomainMigration
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.DomainMigrationUtil.testDumpDir
import org.lfdecentralizedtrust.splice.util.{
  DomainMigrationUtil,
  PackageQualifiedName,
  ProcessTestUtil,
  SpliceUtil,
  SplitwellTestUtil,
  StandaloneCanton,
  SvTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.ExpireTransferOfferTrigger
import org.scalatest.OptionValues
import org.scalatest.time.{Minute, Span}
import org.slf4j.event.Level

import java.io.File
import java.nio.file.Path
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.util.Using

class DecentralizedSynchronizerMigrationIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with DomainMigrationUtil
    with StandaloneCanton
    with SplitwellTestUtil {

  override protected def runEventHistorySanityCheck: Boolean = false

  private val initialRound = 481516L

  override def dbsSuffix = "domain_migration"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  // We manually force a snapshot on sv1 in the test. The other SVs
  // won't have a snapshot at that time so the assertions in the
  // update history sanity plugin wil fail.
  override lazy val skipAcsSnapshotChecks = true

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransforms((_, config) => {
        config.copy(
          svApps = config.svApps ++
            Seq(1, 2, 3, 4).map(sv =>
              InstanceName.tryCreate(s"sv${sv}Local") ->
                ConfigTransforms.withBftSequencer(
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
          scanApps = config.scanApps ++ Seq(1, 2, 3, 4).map(sv =>
            InstanceName.tryCreate(s"sv${sv}ScanLocal") ->
              ConfigTransforms.withBftSequencer(
                s"sv${sv}ScanLocal",
                config
                  .scanApps(InstanceName.tryCreate(s"sv${sv}Scan"))
                  .copy(domainMigrationId = 1L),
                migrationId = 1L,
                basePort = 27010,
              )
          ),
          validatorApps = config.validatorApps + (
            InstanceName.tryCreate("sv1ValidatorLocal") ->
              config
                .validatorApps(InstanceName.tryCreate("sv1Validator"))
                .copy(
                  domainMigrationId = 1L
                )
          ) + (
            InstanceName.tryCreate("bobValidatorLocal") -> {
              val bobValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("bobValidator"))
              bobValidatorConfig
                .copy(
                  participantClient = ParticipantClientConfig(
                    FullClientConfig(port = Port.tryCreate(5902)),
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
              val sv1ScanConfig = config
                .scanApps(InstanceName.tryCreate("sv1Scan"))
              aliceValidatorConfig
                .copy(
                  // Disable bft connections as we only start sv1 scan.
                  scanClient =
                    TrustSingle(url = s"http://127.0.0.1:${sv1ScanConfig.adminApi.port}"),
                  restoreFromMigrationDump = Some(
                    (migrationDumpDir("aliceValidator") / "domain_migration_dump.json").path
                  ),
                  domainMigrationId = 1L,
                )

            }
          ) + (
            InstanceName.tryCreate("splitwellValidatorLocal") -> {
              val splitwellValidatorConfig = config
                .validatorApps(InstanceName.tryCreate("splitwellValidator"))
              val sv1ScanConfig = config
                .scanApps(InstanceName.tryCreate("sv1Scan"))
              splitwellValidatorConfig
                .copy(
                  // Disable bft connections as we only start sv1 scan.
                  scanClient =
                    TrustSingle(url = s"http://127.0.0.1:${sv1ScanConfig.adminApi.port}"),
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
                  domainMigrationId = 1L,
                )
            }
          ),
          splitwellApps = config.splitwellApps + (
            InstanceName.tryCreate("providerSplitwellBackendLocal") -> {
              val splitwellBackendConfig =
                config.splitwellApps(InstanceName.tryCreate("providerSplitwellBackend"))
              splitwellBackendConfig
                .copy(
                  domains = SplitwellSynchronizerConfig(
                    splitwell = SplitwellDomains(
                      preferred = SynchronizerConfig(
                        alias = SynchronizerAlias.tryCreate("global")
                      ),
                      others = Seq.empty,
                    )
                  ),
                  domainMigrationId = 1L,
                )
            }
          ),
          splitwellAppClients = config.splitwellAppClients + (
            InstanceName.tryCreate("aliceSplitwellLocal") -> config
              .splitwellAppClients(InstanceName.tryCreate("aliceSplitwell"))
          ),
        )
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransforms((_, conf) =>
        ConfigTransforms.bumpCantonPortsBy(
          22_000,
          name =>
            // Bob actually doesn't migrate and is instead used to test unavailable validators
            name != "bobValidatorLocal" && name.contains("Local"),
        )(conf)
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
        // TODO(DACH-NY/canton-network-node#9014) Consider keeping this running and instead
        // making the test check history instead of balance once our
        // stores handle hard domain migrations properly.
        (_, conf) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
          )(conf),
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(_.copy(initialRound = initialRound))(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(conf =>
          conf.copy(cache =
            conf.cache.copy(cachedByParty =
              CacheConfig(
                ttl = NonNegativeFiniteDuration.ofMillis(1),
                maxSize = 2000,
              )
            )
          )
        )(config)
      )
      .withManualStart
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  def firstRound(
      backend: SvAppBackendReference
  ): Long =
    backend.getDsoInfo().initialRound match {
      case None => 0L
      case Some(round) => round.toLong
    }

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "migrate global domain to new nodes with downtime" in { implicit env =>
    import env.executionContext

    startAllSync(
      sv1ScanBackend,
      sv2ScanBackend,
      sv3ScanBackend,
      sv4ScanBackend,
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

    val triggersBefore = (sv2Backend.dsoAutomation.triggers[Trigger] ++ sv2Backend.svAutomation
      .triggers[Trigger]).map(_.getClass.getCanonicalName)

    clue("All sequencers are registered") {
      eventually() {
        inside(sv1ScanBackend.listDsoSequencers()) {
          case Seq(DomainSequencers(synchronizerId, sequencers)) =>
            synchronizerId shouldBe decentralizedSynchronizerId
            sequencers should have size 4
            sequencers.foreach { sequencer =>
              sequencer.migrationId shouldBe 0
            }
        }
      }
    }

    createSomeParticipantUsersState(
      sv2Backend.participantClient,
      sv2Backend.getDsoInfo().svParty,
      false,
    )
    val (sv2IdpcsBeforeMigration, sv2UsersBeforeMigration, sv2RightsBeforeMigration) =
      // we ignore annotations we set ourselves because some of these change during the migration (like the migration ID)
      getParticipantUsersState(
        sv2ValidatorBackend.participantClient,
        discardAnnotations = Some("network.canton.global"),
      )

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

    def createExternalParty(
        validatorBackend: ValidatorAppBackendReference,
        walletClient: WalletAppClientReference,
    ) = {
      val onboarding @ OnboardingResult(externalParty, _, _) =
        onboardExternalParty(validatorBackend)
      walletClient.tap(50.0)
      createTransferPreapprovalEnsuringItExists(walletClient, validatorBackend)
      createAndAcceptExternalPartySetupProposal(validatorBackend, onboarding)
      eventually() {
        validatorBackend.lookupTransferPreapprovalByParty(externalParty) should not be empty

        validatorBackend.scanProxy.lookupTransferPreapprovalByParty(
          externalParty
        ) should not be empty
      }
      validatorBackend
        .getExternalPartyBalance(externalParty)
        .totalUnlockedCoin shouldBe "0.0000000000"
      walletClient.transferPreapprovalSend(externalParty, 40.0, UUID.randomUUID.toString)
      validatorBackend
        .getExternalPartyBalance(externalParty)
        .totalUnlockedCoin shouldBe "40.0000000000"
      onboarding
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
      extraParticipantsConfigFileNames =
        Seq("standalone-participant-extra.conf", "standalone-participant-second-extra.conf"),
      extraParticipantsEnvMap = Map(
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> s"participant_extra_${dbsSuffix}",
        "SECOND_EXTRA_PARTICIPANT_DB" -> s"participant_second_extra_${dbsSuffix}",
        "SECOND_EXTRA_PARTICIPANT_ADMIN_USER" -> splitwellValidatorBackend.config.ledgerApiUser,
      ),
      enableBftSequencer = true,
    )() {
      val aliceUserParty = startValidatorAndTapAmulet(aliceValidatorBackend, aliceWalletClient)
      // Upload after starting validator which connects to global
      // synchronizers as upload_dar_unless_exists vets on all
      // connected synchronizers.
      aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val splitwellGroupKey = createSplitwellGroupAndTransfer(aliceUserParty, charlieUserParty)
      val externalPartyOnboarding = clue("Create external party and transfer 40 amulet to it") {
        createExternalParty(aliceValidatorBackend, aliceValidatorWalletClient)
      }
      createSomeParticipantUsersState(
        aliceValidatorBackend.participantClient,
        charlieUserParty,
        true,
      )
      val (aliceIdpcsBeforeMigration, aliceUsersBeforeMigration, aliceRightsBeforeMigration) =
        getParticipantUsersState(aliceValidatorBackend.participantClient)

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
            sv1Backend.participantClientWithAdminToken.topology.synchronizer_parameters
              .list(
                store = TopologyStoreId.Synchronizer(decentralizedSynchronizerId)
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
                Seq(sv2Backend, sv3Backend),
                Some(new SynchronizerUpgradeSchedule(scheduledTime, 1L)),
              )
            },
            // reset to not crash other tests
            {
              clue(
                s"reset domain parameters to old values confirmationRequestsMaxRate=${domainDynamicParams.confirmationRequestsMaxRate},mediatorReactionTimeout=${domainDynamicParams.mediatorReactionTimeout}"
              ) {
                changeDomainParameters(
                  allNodes.map(_.oldParticipantConnection),
                  domainDynamicParams.confirmationRequestsMaxRate,
                  domainDynamicParams.mediatorReactionTimeout,
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
              // stop validators first to prevent log warnings about scans shutting down
              stopAllAsync(
                splitwellBackend,
                splitwellValidatorBackend,
                aliceValidatorBackend,
              ).futureValue
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
                sv3ScanBackend,
                sv4ScanBackend,
              ).futureValue
            }

            // TODO(#2035) Start sv-1 only later once Canton issue is fixed.
            withClueAndLog("starting sv1-4 upgraded nodes") {
              startAllSync(
                sv1LocalBackend,
                sv2LocalBackend,
                sv3LocalBackend,
                sv4LocalBackend,
                sv1ScanLocalBackend,
                sv2ScanLocalBackend,
                sv3ScanLocalBackend,
                sv4ScanLocalBackend,
              )
            }

            checkMigrateDomainOnNodes(majorityUpgradeNodes, testDsoParty)

            clue("Triggers are the same as before migration") {
              val triggersAfter =
                (sv2LocalBackend.dsoAutomation.triggers[Trigger] ++ sv2LocalBackend.svAutomation
                  .triggers[Trigger]).map(_.getClass.getCanonicalName)
              triggersAfter.filter(t =>
                !t.startsWith(
                  "org.lfdecentralizedtrust.splice.sv.automation.singlesv.SvBftSequencerPeer"
                )
              ) should contain theSameElementsAs triggersBefore.filter(t =>
                t != "org.lfdecentralizedtrust.splice.sv.migration.DecentralizedSynchronizerMigrationTrigger"
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
                    .getSynchronizerParametersState(
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
                  .getSynchronizerParametersState(
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
            withClueAndLog("validator can migrate to the new domain") {
              val validatorThatMigrates = aliceValidatorLocal
              startValidatorAndTapAmulet(
                validatorThatMigrates,
                aliceWalletClient,
                // tap 2 times (100) minus splitwell transfer (42)
                expectedAmulets = 57 to 58,
              )
            }
            withClueAndLog("User automation works before user is reonboarded") {
              val aliceUser = aliceWalletClient.config.ledgerApiUser
              val charlieUser = charlieWalletClient.config.ledgerApiUser
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
                aliceWalletClient.createTransferOffer(
                  charlieUserParty,
                  10,
                  "transfer 10 amulets to Charlie",
                  CantonTimestamp.now().plus(Duration.ofSeconds(2)),
                  UUID.randomUUID.toString,
                ),
              )(
                "Alice sees expired transfer offer",
                _ => aliceWalletClient.listTransferOffers() should have length 1,
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
                  aliceWalletClient.listTransferOffers() should have length 0
                },
              )
            }

            clue("External party's balance has been preserved and it can transfer") {
              aliceValidatorLocal
                .getExternalPartyBalance(externalPartyOnboarding.party)
                .totalUnlockedCoin shouldBe "40.0000000000"
              val prepareSend =
                aliceValidatorLocal.prepareTransferPreapprovalSend(
                  externalPartyOnboarding.party,
                  aliceValidatorLocal.getValidatorPartyId(),
                  BigDecimal(10.0),
                  CantonTimestamp.now().plus(Duration.ofHours(24)),
                  0L,
                  Some("transfer-command-description"),
                )
              actAndCheck(
                "Submit signed TransferCommand creation",
                aliceValidatorLocal.submitTransferPreapprovalSend(
                  externalPartyOnboarding.party,
                  prepareSend.transaction,
                  HexString.toHexString(
                    crypto
                      .signBytes(
                        HexString.parseToByteString(prepareSend.txHash).value,
                        externalPartyOnboarding.privateKey.asInstanceOf[SigningPrivateKey],
                        usage = SigningKeyUsage.ProtocolOnly,
                      )
                      .value
                      .toProtoV30
                      .signature
                  ),
                  publicKeyAsHexString(externalPartyOnboarding.publicKey),
                ),
              )(
                "validator automation completes transfer",
                _ => {
                  // 40-10-some fees
                  BigDecimal(
                    aliceValidatorLocal
                      .getExternalPartyBalance(externalPartyOnboarding.party)
                      .totalUnlockedCoin
                  ) should beWithin(BigDecimal(28), BigDecimal(30))
                },
              )
            }

            clue(s"scan should expose sequencers in both pre-upgrade or upgraded domain") {
              eventually() {
                inside(sv1ScanLocalBackend.listDsoSequencers()) {
                  case Seq(DomainSequencers(synchronizerId, sequencers)) =>
                    synchronizerId shouldBe decentralizedSynchronizerId
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

            clue("Alice's participant users state was preserved") {
              val (aliceIdpcsAfterMigration, aliceUsersAfterMigration, aliceRightsAfterMigration) =
                getParticipantUsersState(
                  aliceValidatorLocal.participantClient,
                  discardAnnotations = Some("acs_import"),
                )
              aliceIdpcsAfterMigration should contain theSameElementsAs aliceIdpcsBeforeMigration
              aliceUsersAfterMigration should contain theSameElementsAs aliceUsersBeforeMigration
              aliceRightsAfterMigration should contain theSameElementsAs aliceRightsBeforeMigration
            }

            clue("SV2's participant users state was preserved") {
              val (sv2IdpcsAfterMigration, sv2UsersAfterMigration, sv2RightsAfterMigration) =
                getParticipantUsersState(
                  sv2LocalBackend.participantClient,
                  discardAnnotations = Some("network.canton.global"),
                )
              sv2IdpcsAfterMigration should contain theSameElementsAs sv2IdpcsBeforeMigration
              sv2UsersAfterMigration should contain theSameElementsAs sv2UsersBeforeMigration
              sv2RightsAfterMigration should contain theSameElementsAs sv2RightsBeforeMigration
            }

            startValidatorAndTapAmulet(
              v("splitwellValidatorLocal"),
              splitwellWalletClient,
            )

            startAllSync(
              sw("providerSplitwellBackendLocal")
            )

            sv1LocalBackend.getDsoInfo().dsoRules.payload.svs.size() shouldBe 4

            clue("Old wallet balance is recorded") {
              eventually() {
                assertInRange(sv1WalletClient.balance().unlockedQty, (1000, 2000))
              }
            }
            clue("Old scan transaction history is recorded") {
              eventually() {
                countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual 1
                countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual 0
              }
            }
            actAndCheck("Create some new transaction history", sv1WalletClient.tap(1338))(
              "New transaction history is recorded and balance is updated",
              _ => {
                countTapsFromScan(sv1ScanLocalBackend, 1337) shouldEqual 1
                countTapsFromScan(sv1ScanLocalBackend, 1338) shouldEqual 1
                assertInRange(sv1WalletClient.balance().unlockedQty, (2000, 4000))
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
                      new Round(firstRound(sv1LocalBackend) + 42),
                    )
                  )
                ),
                "url",
                "description",
                sv1LocalBackend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
                None,
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

            withClueAndLog("Backfilled history includes ACS import") {
              eventually() {
                sv1ScanLocalBackend.appState.store.updateHistory.sourceHistory
                  .migrationInfo(1L)
                  .futureValue
                  .exists(_.complete) should be(true)
              }

              val backfilledUpdates =
                sv1ScanLocalBackend.appState.store.updateHistory
                  .getAllUpdates(None, PageLimit.tryCreate(1000))
                  .futureValue
              backfilledUpdates.collect {
                case TreeUpdateWithMigrationId(tree, migrationId)
                    if tree.update.recordTime == CantonTimestamp.MinValue && migrationId == 1L =>
                  tree
              } should not be empty
            }

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
                    Vector(
                      DsoRules.COMPANION,
                      AmuletRules.COMPANION,
                      AnsRules.COMPANION,
                    ).map(
                      PackageQualifiedName.fromJavaCodegenCompanion
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
                    getSingleAppPaymentRequest(aliceWalletClient)
                  },
                )

              actAndCheck(
                "alice initiates payment accept request after domain migration",
                aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
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
                      case Seq(DomainSequencers(synchronizerId, sequencers)) =>
                        synchronizerId shouldBe decentralizedSynchronizerId
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

  private def changeDomainParameters(
      nodes: Seq[ParticipantAdminConnection],
      confirmationRequestsMaxRate: NonNegativeInt,
      mediatorReactionTimeout: com.digitalasset.canton.time.NonNegativeFiniteDuration,
  )(implicit
      env: SpliceTestConsoleEnvironment,
      ec: ExecutionContextExecutor,
  ): Unit = {
    nodes
      .parTraverse { node =>
        node
          .ensureDomainParameters(
            decentralizedSynchronizerId,
            _.tryUpdate(
              confirmationRequestsMaxRate = confirmationRequestsMaxRate,
              mediatorReactionTimeout = mediatorReactionTimeout,
            ),
            forceChanges = ForceFlags(ForceFlag.AllowOutOfBoundsValue),
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
      synchronizerAlias: SynchronizerAlias,
  ): Set[String] = {
    val sequencerConnections = participantConnection.synchronizers
      .config(synchronizerAlias)
      .value
      .sequencerConnections
    (for {
      conn <- sequencerConnections.aliasToConnection.values
      endpoint <- conn match {
        case GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints
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
            getSingleAppPaymentRequest(aliceWalletClient)
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

  private def createSomeParticipantUsersState(
      participant: ParticipantClientReference,
      someExistingParty: PartyId,
      createNewParties: Boolean,
  )(implicit env: SpliceTestConsoleEnvironment) = {
    // for test isolation
    val suffix = (new scala.util.Random).nextInt().toHexString.toLowerCase

    val idpcsOriginally = participant.ledger_api.identity_provider_config.list()
    val usersOriginally = participant.ledger_api.users.list().users

    val someParties = Seq(someExistingParty) ++ {
      if (createNewParties) {
        clue("Create fresh parties for fresh users") {
          // more than just users state, but allocating fresh parties makes it easier to create interesting users
          Seq(someExistingParty) ++ {
            for (i <- 0 to 2)
              yield participant.ledger_api.parties
                .allocate(
                  s"fake-party-${i}-${suffix}",
                  synchronizerId = Some(decentralizedSynchronizerId),
                )
                .party
          }
        }
      } else {
        Seq()
      }
    }
    actAndCheck(
      "Create some participant users state", {
        participant.ledger_api.identity_provider_config.create(
          s"fake-idp-enabled-${suffix}",
          false,
          "https://jwks.fake.com",
          s"https://issuer-${suffix}.fake.com",
          None,
        )
        participant.ledger_api.identity_provider_config.create(
          s"fake-idp-disabled-${suffix}",
          true,
          "https://jwks2.fake.com",
          s"https://issuer-2-${suffix}.fake.com",
          Some(s"https://${suffix}.fake.com"),
        )
        participant.ledger_api.users.create(
          s"fake-user-0-${suffix}",
          Set(someParties(0)),
          Option(someParties(0)),
          Set(),
          false,
          false,
          true,
          Map("fake-key-1" -> "fake-value-1"),
          s"fake-idp-enabled-${suffix}",
          false,
          executeAs = Set(someParties(0)),
          executeAsAnyParty = true,
        )
        if (createNewParties) {
          participant.ledger_api.users.create(
            s"fake-user-1-${suffix}",
            Set(someParties(1)),
            Option(someParties(1)),
            Set(someParties(3)),
            true,
            true,
            true,
            Map("fake-key-2" -> "fake-value-2", "fake-key-3" -> "fake-value-3"),
            s"fake-idp-enabled-${suffix}",
            true,
          )
          participant.ledger_api.users.create(
            s"fake-user-2-${suffix}",
            Set(someParties(2)),
            None,
            Set(),
            false,
            false,
            false,
            Map.empty,
            s"fake-idp-disabled-${suffix}",
            false,
          )
          participant.ledger_api.users.create(
            s"fake-user-3-${suffix}",
            Set(),
            None,
            Set(someParties(3)),
            false,
            false,
            true,
            Map("fake-key-4" -> "fake-value-4"),
            "",
            false,
          )
        }
      },
    )(
      "Participant users state created",
      _ => {

        val (
          idpcs,
          users,
          _,
        ) = getParticipantUsersState(participant)
        idpcs.length shouldBe idpcsOriginally.length + 2
        users.length should be >= usersOriginally.length + { if (createNewParties) 4 else 1 }
      },
    )
  }

  private def getParticipantUsersState(
      participant: ParticipantClientReference,
      discardAnnotations: Option[String] = None,
  ): (Seq[IdentityProviderConfig], Seq[User], Map[String, UserRights]) = {
    val idpcs = participant.ledger_api.identity_provider_config.list()
    // the `Seq("")` is so we also get the users that have no explicit idp set
    val users = (Seq("") ++ idpcs.map(_.identityProviderId.value))
      .map { idp =>
        participant.ledger_api.users.list(identityProviderId = idp).users
      }
      .flatten
      .map((user: User) =>
        discardAnnotations match {
          case Some(substring) =>
            user.copy(annotations = user.annotations.filter { case (key, _) =>
              !key.contains(substring)
            })
          case None =>
            user
        }
      )
    val rights =
      users
        .map(user =>
          (user.id, participant.ledger_api.users.rights.list(user.id, user.identityProviderId))
        )
        .toMap
    (
      idpcs,
      users,
      rights,
    )
  }
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
