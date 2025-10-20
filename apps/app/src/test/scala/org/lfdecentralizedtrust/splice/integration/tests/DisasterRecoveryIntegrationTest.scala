package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File
import better.files.File.apply
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.config.{FullClientConfig, NonNegativeFiniteDuration}
import io.circe.syntax.EncoderOps
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  NetworkAppClientConfig,
  ParticipantClientConfig,
}
import org.lfdecentralizedtrust.splice.console.{
  AppBackendReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection.INITIAL_ROUND_USER_METADATA_KEY
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.DomainMigration
import org.lfdecentralizedtrust.splice.sv.config.{
  SvDecentralizedSynchronizerConfig,
  SvSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainDataSnapshot,
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.util.DomainMigrationUtil.testDumpDir
import org.lfdecentralizedtrust.splice.validator.config.{
  ValidatorDecentralizedSynchronizerConfig,
  ValidatorSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.validator.migration.DomainMigrationDump as ValidatorDomainMigrationDump
import org.lfdecentralizedtrust.splice.wallet.store.BalanceChangeTxLogEntry
import org.scalatest.time.{Minute, Span}

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.util.Using

class DisasterRecoveryIntegrationTest
    extends IntegrationTest
    with ProcessTestUtil
    with DomainMigrationUtil
    with StandaloneCanton
    with SvTestUtil
    with WalletTestUtil {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def dbsSuffix = "disaster_recovery"

  override def usesDbs: IndexedSeq[String] = {
    (1 to 4)
      .map(i =>
        Seq(
          s"participant_sv${i}_disaster_recovery_new",
          s"sequencer_sv${i}_disaster_recovery_new",
          s"mediator_sv${i}_disaster_recovery_new",
        )
      )
      .flatten ++
      Seq("sequencer_driver_disaster_recovery_new", "participant_extra_disaster_recovery_new") ++
      super.usesDbs
  }

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  // Runs against a temporary Canton instance.
  override lazy val resetRequiredTopologyState = false

  // Any app with port starting with 28 or with name suffixed by 'Local' is an app started after the disaster
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // Disable user allocation
      .withPreSetup(_ => ())
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransformsToFront((_, conf) => ConfigTransforms.bumpCantonPortsBy(22_000)(conf))
      .addConfigTransforms(
        (_, conf) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
          )(conf),
        (_, conf) =>
          updateAutomationConfig(ConfigurableApp.Scan)(
            _.withPausedTrigger[AcsSnapshotTrigger]
          )(conf),
        (_, conf) =>
          conf.copy(
            svApps = conf.svApps ++
              Seq(1, 2, 3, 4).map(sv =>
                (
                  InstanceName.tryCreate(s"sv${sv}Local") ->
                    conf
                      .svApps(InstanceName.tryCreate(s"sv${sv}"))
                      .copy(
                        onboarding = Some(
                          DomainMigration(
                            name = getSvName(sv),
                            dumpFilePath = migrationDumpFilePath(s"sv${sv}").path,
                          )
                        ),
                        domains = SvSynchronizerConfig(global =
                          SvDecentralizedSynchronizerConfig(
                            alias = SynchronizerAlias.tryCreate("global"),
                            // changing the domain config since for a domain migration SVs connect directly to their own sequencer instead of SV1's sequencer.
                            url = Some(s"http://localhost:28${sv}08"),
                          )
                        ),
                        domainMigrationId = 1L,
                      )
                )
              ),
            scanApps = conf.scanApps + (
              InstanceName.tryCreate("sv1ScanLocal") ->
                conf
                  .scanApps(InstanceName.tryCreate("sv1Scan"))
                  .copy(domainMigrationId = 1L)
            ),
            validatorApps = conf.validatorApps + (
              InstanceName.tryCreate("sv1ValidatorLocal") ->
                conf
                  .validatorApps(InstanceName.tryCreate("sv1Validator"))
                  .copy(
                    scanClient = TrustSingle(url = "http://127.0.0.1:28012"),
                    domains = ValidatorSynchronizerConfig(global =
                      ValidatorDecentralizedSynchronizerConfig(
                        alias = SynchronizerAlias.tryCreate("global"),
                        url = Some("http://localhost:28108"),
                      )
                    ),
                    domainMigrationId = 1L,
                  )
            ) + (
              InstanceName.tryCreate("aliceValidatorLocal") -> {
                val aliceValidatorConf = conf
                  .validatorApps(InstanceName.tryCreate("aliceValidator"))
                aliceValidatorConf
                  .copy(
                    participantClient = ParticipantClientConfig(
                      FullClientConfig(port = Port.tryCreate(28502)),
                      aliceValidatorConf.participantClient.ledgerApi.copy(
                        clientConfig =
                          aliceValidatorConf.participantClient.ledgerApi.clientConfig.copy(
                            port = Port.tryCreate(28501)
                          )
                      ),
                    ),
                    scanClient = TrustSingle(url = "http://127.0.0.1:28012"),
                    domains = ValidatorSynchronizerConfig(global =
                      ValidatorDecentralizedSynchronizerConfig(
                        alias = SynchronizerAlias.tryCreate("global"),
                        url = Some("http://localhost:28108"),
                      )
                    ),
                    domainMigrationId = 1L,
                    restoreFromMigrationDump = Some(
                      migrationDumpFilePath("aliceValidator").path
                    ),
                  )
              }
            ),
            walletAppClients = conf.walletAppClients + (
              InstanceName.tryCreate("sv1WalletLocal") ->
                conf
                  .walletAppClients(InstanceName.tryCreate("sv1Wallet"))
                  .copy(
                    adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:28103")
                  )
            ) + (
              InstanceName.tryCreate("aliceValidatorLocalWallet") ->
                conf
                  .walletAppClients(InstanceName.tryCreate("aliceWallet"))
                  .copy(adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:28503"))
            ),
          ),
      )
      .addConfigTransforms((_, conf) =>
        (ConfigTransforms
          .setSomeSvAppPortsPrefix(28, Seq("sv1Local", "sv2Local", "sv3Local", "sv4Local")) compose
          ConfigTransforms.setSomeScanAppPortsPrefix(28, Seq("sv1ScanLocal")) compose
          ConfigTransforms
            .setSomeValidatorAppPortsPrefix(28, Seq("sv1ValidatorLocal", "aliceValidatorLocal")))(
          conf
        )
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllValidatorConfigs((name, validatorConfig) =>
          if (name == "aliceValidator") {
            // update validator app config for the aliceValidator to remove the extra domain and update port
            val newConfig = validatorConfig.copy(
              domains = ValidatorSynchronizerConfig(global =
                ValidatorDecentralizedSynchronizerConfig(
                  alias = SynchronizerAlias.tryCreate("global"),
                  url = Some("http://localhost:27108"),
                )
              )
            )
            newConfig
          } else validatorConfig
        )(conf)
      )
      .withManualStart

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  "Recover from losing the domain" in { implicit env =>
    runTest(
      "lost-domain",
      (identities, timestampBeforeDisaster) => {
        val svBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
        val dumps = svBackends.map(_.getDomainDataSnapshot(timestampBeforeDisaster, force = true))
        svBackends.zip(identities.zip(dumps)).foreach { case (sv, (ids, dump)) =>
          dump.dataSnapshot.acsTimestamp should be(timestampBeforeDisaster)
          dump.createdAt should be(timestampBeforeDisaster)
          dump.migrationId shouldBe 1
          writeMigrationDumpFile(sv, ids, dump)
        }
      },
    )
  }
  "Recover from losing all sequencers and most participants" in { implicit env =>
    runTest(
      "lost-all-sequencers-most-participants",
      (identities, timestampBeforeDisaster) => {
        Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).zip(identities).foreach {
          case (sv, ids) =>
            val dump =
              sv
                .getDomainDataSnapshot(
                  timestampBeforeDisaster,
                  Some(identities.head.dsoPartyId),
                  force = true,
                )
            dump.dataSnapshot.acsTimestamp should be(timestampBeforeDisaster)
            dump.createdAt should be(timestampBeforeDisaster)
            dump.migrationId shouldBe 1
            dump.participantUsers.users
              .find(_.annotations.contains(INITIAL_ROUND_USER_METADATA_KEY)) should not be empty
            writeMigrationDumpFile(sv, ids, dump)
        }
      },
    )
  }

  private def runTest(
      cantonInstanceSuffix: String,
      getAndWriteDumps: (Seq[SynchronizerNodeIdentities], Instant) => Unit,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    import env.executionContext

    val svBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
      s"participants-before-disaster-$cantonInstanceSuffix",
      sequencersMediators = false,
      extraParticipantsConfigFileNames = Seq("standalone-participant-extra.conf"),
      extraParticipantsEnvMap = Map(
        "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
        "EXTRA_PARTICIPANT_DB" -> s"participant_extra_disaster_recovery",
      ),
    )() {

      val (acsSnapshotBeforeDisaster, acsSnapshotAfterDisaster) = withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        s"sequencers-mediators-before-disaster-$cantonInstanceSuffix",
        participants = false,
      )() {
        val allAppsBeforeDisaster = Seq[AppBackendReference](
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
          sv1ValidatorBackend,
          aliceValidatorBackend,
        )

        clue("Starting old apps") {
          startAllSync(allAppsBeforeDisaster*)
        }

        val identities = withClueAndLog("Getting node identities dump") {
          svBackends.map(_.getSynchronizerNodeIdentitiesDump())
        }

        actAndCheck("Create some transaction history", sv1WalletClient.tap(1337))(
          "Scan transaction history is recorded and wallet balance is updated",
          _ => {
            // buffer to account for domain fee payments
            assertInRange(
              sv1WalletClient.balance().unlockedQty,
              (walletUsdToAmulet(1000.0), walletUsdToAmulet(2000.0)),
            )
            countTapsFromScan(sv1ScanBackend, walletUsdToAmulet(1337)) shouldBe 1
          },
        )

        val validatorParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        actAndCheck(
          "Tap amulets for alice validator", {
            aliceWalletClient.tap(1500.0)
          },
        )(
          "alice wallet balance updated",
          _ => {
            checkWallet(
              validatorParty,
              aliceWalletClient,
              Seq((walletUsdToAmulet(1000), walletUsdToAmulet(2000))),
            )
          },
        )

        val acsSnapshotBeforeDisaster = withClueAndLog("Generating ACS snapshot before disaster") {
          sv1ScanBackend.forceAcsSnapshotNow()
        }

        // In a real disaster, we would find the available ACS timestamp from each SV, and take the
        // minimum on a majority of SVs, but here we want to make sure all SVs have the tap in their
        // ACS, so we just make sure that the timestamp right after the tap that we want to retain is
        // available on the SV participants.
        val timestampBeforeDisaster = Instant.now()

        // Tap some more amulet and wait for SVs to see it (even though we're going to recover to a point before it)
        sv1WalletClient.tap(1338)
        aliceWalletClient.tap(1400.0)
        val timestampAfterLostTap = Instant.now()

        logger.debug(
          s"timestampBeforeDisaster=$timestampBeforeDisaster, timestampAfterLostTap=$timestampAfterLostTap"
        )

        // Tap yet more amulet without waiting for it to necessarily be ingested on all SVs
        sv1WalletClient.tap(1339)

        aliceWalletClient.tap(1300)

        waitForSvParticipantsToCatchup(timestampAfterLostTap)

        withClueAndLog(
          "More than one tap visible in the wallet transaction history on the old domain"
        ) {
          val balanceChanges = sv1WalletClient.listTransactions(None, 100).collect {
            case e: BalanceChangeTxLogEntry => e.amount
          }
          balanceChanges should contain allElementsOf Seq(
            walletUsdToAmulet(1337),
            walletUsdToAmulet(1338),
          )
        }
        withClueAndLog(
          "More than one tap visible in the scan transaction history on the old domain"
        ) {
          val taps = sv1ScanBackend
            .listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
            .flatMap(_.tap.map(t => BigDecimal(t.amuletAmount)))
          taps should contain allElementsOf Seq(walletUsdToAmulet(1337), walletUsdToAmulet(1338))
        }

        val acsSnapshotAfterDisaster = withClueAndLog("Generating ACS snapshot after disaster") {
          sv1ScanBackend.forceAcsSnapshotNow()
        }

        // Note: the dumps contain data from the sequencers, need to get
        // the dumps before shutting down canton nodes.
        // TODO(DACH-NY/canton-network-node#11099): Once taking the migration dump does not need sequencers to be running, move this further down.
        withClueAndLog("Getting and writing disaster recovery dumps") {
          getAndWriteDumps(identities, timestampBeforeDisaster)
        }

        withClueAndLog("Testing restore from HTTP validator dump") {
          import org.lfdecentralizedtrust.splice.validator.admin.api.client.commands.HttpValidatorAdminAppClient.GetValidatorDomainDataSnapshot
          val validatorRawDump = aliceValidatorBackend.httpCommand(
            GetValidatorDomainDataSnapshot(
              timestampBeforeDisaster,
              migrationId = None,
              force = true,
            ).withRawResponse
          )
          val validatorRawDumpJson =
            validatorRawDump.toEither.value
              .fold(Right(_), Left(_))
              .value
              .asJsonObject
              .apply("data_snapshot")
              .value
          io.circe.parser
            .decode[ValidatorDomainMigrationDump](validatorRawDumpJson.noSpaces)
            .value
        }

        withClueAndLog("Getting and writing disaster recovery dumps for validator") {
          val validatorDump =
            aliceValidatorBackend.getValidatorDomainDataSnapshot(
              timestampBeforeDisaster.toString,
              force = true,
            )
          validatorDump.migrationId shouldBe 1
          writeValidatorMigrationDumpFile(aliceValidatorBackend, validatorDump)
        }

        clue("Shutting down old apps") {
          stopAllAsync(allAppsBeforeDisaster*).futureValue
        }

        (acsSnapshotBeforeDisaster, acsSnapshotAfterDisaster)
      }

      // The sequencers and mediators have been shut down here, only participants are still alive

      withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        s"disaster-recovery-$cantonInstanceSuffix",
        overrideSvDbsSuffix = Some("disaster_recovery_new"),
        overrideSequencerDriverDbSuffix = Some("disaster_recovery_new"),
        portsRange = Some(28),
        extraParticipantsConfigFileNames = Seq("standalone-participant-extra.conf"),
        extraParticipantsEnvMap = Map(
          "EXTRA_PARTICIPANT_ADMIN_USER" -> aliceValidatorBackend.config.ledgerApiUser,
          "EXTRA_PARTICIPANT_DB" -> "participant_extra_disaster_recovery_new",
          "EXTRA_PARTICIPANT_ADMIN_API_PORT" -> "28502",
          "EXTRA_PARTICIPANT_LEDGER_API_PORT" -> "28501",
        ),
      )(
      ) {
        Using.resources(
          createUpgradeNode(
            1,
            sv1Backend,
            sv1LocalBackend,
            retryProvider,
            env.environment.config.monitoring.logging.api,
            28,
            27,
          ),
          createUpgradeNode(
            2,
            sv1Backend,
            sv2LocalBackend,
            retryProvider,
            env.environment.config.monitoring.logging.api,
            28,
            27,
          ),
          createUpgradeNode(
            3,
            sv1Backend,
            sv3LocalBackend,
            retryProvider,
            env.environment.config.monitoring.logging.api,
            28,
            27,
          ),
          createUpgradeNode(
            4,
            sv1Backend,
            sv4LocalBackend,
            retryProvider,
            env.environment.config.monitoring.logging.api,
            28,
            27,
          ),
        ) {
          case (
                newSynchronizerNode1,
                newSynchronizerNode2,
                newSynchronizerNode3,
                newSynchronizerNode4,
              ) =>
            val allNodes =
              Seq(
                newSynchronizerNode1,
                newSynchronizerNode2,
                newSynchronizerNode3,
                newSynchronizerNode4,
              )

            withClueAndLog("Starting new apps") {
              startAllSync(
                sv1LocalBackend,
                sv2LocalBackend,
                sv3LocalBackend,
                sv4LocalBackend,
                sv1ScanLocalBackend,
                sv1ValidatorLocalBackend,
                aliceValidatorLocalBackend,
              )
            }

            withClueAndLog("checking domain migration") {
              checkMigrateDomainOnNodes(allNodes, sv1ScanLocalBackend.getDsoPartyId())
            }

            withClueAndLog("Old balance has been transferred to new domain") {
              assertInRange(
                sv1WalletLocalClient.balance().unlockedQty,
                (walletUsdToAmulet(1000), walletUsdToAmulet(2000)),
              )
            }

            withClueAndLog(
              "Right number of taps visible in the scan transaction history on the new domain"
            ) {
              val taps = sv1ScanLocalBackend
                .listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
                .flatMap(_.tap.map(t => BigDecimal(t.amuletAmount)))
              taps should contain theSameElementsAs Seq(
                walletUsdToAmulet(1337),
                walletUsdToAmulet(1500),
              )
            }

            withClueAndLog(
              "Only one tap visible in the sv1 wallet transaction history on the new domain"
            ) {
              val balanceChanges = sv1WalletLocalClient.listTransactions(None, 100).collect {
                case e: BalanceChangeTxLogEntry => e.amount
              }
              balanceChanges should contain theSameElementsAs Seq(walletUsdToAmulet(1337))
            }

            withClueAndLog(
              "Only one ACS snapshot visible"
            ) {
              sv1ScanLocalBackend.getDateOfMostRecentSnapshotBefore(
                before = acsSnapshotBeforeDisaster.minusMillis(1L),
                migrationId = 0L,
              ) shouldBe None

              sv1ScanLocalBackend
                .getDateOfMostRecentSnapshotBefore(
                  before = acsSnapshotBeforeDisaster.plusMillis(1L),
                  migrationId = 0L,
                )
                .map(_.toInstant) shouldBe Some(acsSnapshotBeforeDisaster.toInstant)

              // acsSnapshotAfterDisaster should be deleted by `UpdateHistory.cleanUpDataAfterDomainMigration`
              sv1ScanLocalBackend
                .getDateOfMostRecentSnapshotBefore(
                  before = acsSnapshotAfterDisaster.plusMillis(1L),
                  migrationId = 0L,
                )
                .map(_.toInstant) shouldBe Some(acsSnapshotBeforeDisaster.toInstant)
            }

            withClueAndLog("New domain is functional") {
              sv1WalletLocalClient.tap(1337)
              assertInRange(
                sv1WalletLocalClient.balance().unlockedQty,
                (walletUsdToAmulet(2000), walletUsdToAmulet(3000)),
              )
            }
        }

        withClueAndLog(
          "Only one tap visible in the validator wallet transaction history on the new domain"
        ) {
          val balanceChanges = aliceValidatorWalletLocalClient.listTransactions(None, 100).collect {
            case e: BalanceChangeTxLogEntry => e.amount
          }
          balanceChanges should contain theSameElementsAs Seq(walletUsdToAmulet(1500))
        }

        withClueAndLog("Old validator balance has been transferred") {
          assertInRange(
            aliceValidatorWalletLocalClient.balance().unlockedQty,
            (walletUsdToAmulet(1000), walletUsdToAmulet(2000)),
          )
        }

        withClueAndLog("New validator is functional") {
          val validatorLocalParty =
            onboardWalletUser(aliceValidatorWalletLocalClient, aliceValidatorLocalBackend)
          aliceValidatorWalletLocalClient.tap(1600)
          checkWallet(
            validatorLocalParty,
            aliceValidatorWalletLocalClient,
            Seq((walletUsdToAmulet(3000), walletUsdToAmulet(3500))),
          )
        }
      }
    }
  }

  private def waitForSvParticipantsToCatchup(timestamp: Instant)(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    clue(s"Waiting for all SVs participants to be caught up to $timestamp") {
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach(svBackend =>
        eventuallySucceeds() {
          val snapshot = svBackend.getDomainDataSnapshot(timestamp, force = true)
          snapshot.participantUsers.users
            .find(_.annotations.contains(INITIAL_ROUND_USER_METADATA_KEY)) should not be empty
        }
      )
    }
  }

  private def writeValidatorMigrationDumpFile(
      validator: ValidatorAppBackendReference,
      dump: ValidatorDomainMigrationDump,
  ): Unit = {
    val participantIdDumpFile = participantIdentitiesFilePath(validator.name)
    clearOrCreate(participantIdDumpFile)

    participantIdDumpFile.write(dump.participant.toJson.spaces2)

    val fullDumpFile = migrationDumpFilePath(validator.name)
    clearOrCreate(fullDumpFile)
    fullDumpFile.write(dump.asJson.spaces2)
  }

  private def writeMigrationDumpFile(
      sv: SvAppBackendReference,
      ids: SynchronizerNodeIdentities,
      dump: DomainDataSnapshot.Response,
  ): Unit = {
    val participantIdDumpFile = participantIdentitiesFilePath(sv.name)
    clearOrCreate(participantIdDumpFile)

    participantIdDumpFile.write(ids.participant.toJson.spaces2)

    val fullDumpFile = migrationDumpFilePath(sv.name)
    clearOrCreate(fullDumpFile)

    val fullDump = DomainMigrationDump(
      migrationId = dump.migrationId,
      ids,
      dump.dataSnapshot,
      dump.participantUsers,
      createdAt = dump.createdAt,
    )
    fullDumpFile.write(fullDump.asJson.spaces2)
  }

  private def clearOrCreate(f: File) = {
    if (f.exists) f.clear() else f.createFile()
  }

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: BigDecimal) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.amuletAmount)).contains(tapAmount)
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  }

  // Not using temp-files so test-generated outputs are easy to inspect.
  val migrationDumpDir: Path = testDumpDir.resolve("disaster-recovery-dump")
  if (!migrationDumpDir.toFile.exists())
    migrationDumpDir.toFile.mkdirs()

  private def participantIdentitiesFilePath(nodeName: String) = {
    val filename = s"${nodeName}_participant_identities_dump.json"
    migrationDumpDir / filename
  }

  private def migrationDumpFilePath(nodeName: String) = {
    val filename = s"${nodeName}_migration_dump.json"
    migrationDumpDir / filename
  }

}
