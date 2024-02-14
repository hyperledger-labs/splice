package com.daml.network.integration.tests

import better.files.File
import better.files.File.apply
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.{DomainMigrationUtil, ProcessTestUtil, StandaloneCanton}
import com.daml.network.config.{CNDbConfig, CNNodeConfigTransforms, NetworkAppClientConfig}
import CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.{CNNodeEnvironmentImpl, RetryProvider}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UseInMemoryStores
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.scalatest.time.{Minute, Span}
import com.daml.network.console.{ScanAppBackendReference, SvAppBackendReference}
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import com.daml.network.sv.automation.singlesv.SvRewardTrigger
import com.daml.network.sv.config.{SvDomainConfig, SvGlobalDomainConfig}
import com.daml.network.sv.config.SvOnboardingConfig.DomainMigration
import com.daml.network.sv.migration.{DomainDataSnapshot, DomainMigrationDump, DomainNodeIdentities}
import com.daml.network.util.DomainMigrationUtil.testDumpDir
import com.daml.network.validator.config.{ValidatorDomainConfig, ValidatorGlobalDomainConfig}
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory
import com.digitalasset.canton.topology.DomainId

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.duration.*
import scala.util.Using

class DisasterRecoveryIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with DomainMigrationUtil
    with StandaloneCanton {

  override def dbsSuffix = "disaster_recovery"

  override def usesDbs = {
    (1 to 4)
      .map(i =>
        Seq(
          s"participant_sv${i}_disaster_recovery_new",
          s"sequencer_sv${i}_disaster_recovery_new",
          s"mediator_sv${i}_disaster_recovery_new",
        )
      )
      .flatten ++
      Seq("sequencer_driver_disaster_recovery_new") ++
      super.usesDbs
  }

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))
  // TODO(#9014) make it work with persistent stores
  registerPlugin(new UseInMemoryStores(loggerFactory))

  // Runs against a temporary Canton instance.
  override lazy val resetDecentralizedNamespace = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      // Disable user allocation
      .withPreSetup(_ => ())
      .withZeroSequencerAvailabilityDelay
      .addConfigTransformsToFront(
        (_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(22_000)(conf),
        (_, conf) => CNNodeConfigTransforms.bumpCantonDomainPortsBy(22_000)(conf),
      )
      .addConfigTransforms(
        (_, conf) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[SvRewardTrigger]
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
                            name = s"Canton-Foundation-${sv}",
                            dumpFilePath = migrationDumpFilePath(s"sv${sv}").path,
                          )
                        ),
                        domains = SvDomainConfig(global =
                          SvGlobalDomainConfig(
                            alias = DomainAlias.tryCreate("global"),
                            // changing the domain config since for a domain migration SVs connect directly to their own sequencer instead of SV1's sequencer.
                            url = s"http://localhost:28${sv}08",
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
                  .copy()
            ),
            validatorApps = conf.validatorApps + (
              InstanceName.tryCreate("sv1ValidatorLocal") ->
                conf
                  .validatorApps(InstanceName.tryCreate("sv1Validator"))
                  .copy(
                    storage = CNDbConfig.Memory(),
                    scanClient = TrustSingle(url = "http://127.0.0.1:28012"),
                    domains = ValidatorDomainConfig(global =
                      ValidatorGlobalDomainConfig(
                        alias = DomainAlias.tryCreate("global"),
                        url = Some("http://localhost:28109"),
                      )
                    ),
                  )
            ),
            walletAppClients = conf.walletAppClients + (
              InstanceName.tryCreate("sv1WalletLocal") ->
                conf
                  .walletAppClients(InstanceName.tryCreate("sv1Wallet"))
                  .copy(
                    adminApi = NetworkAppClientConfig(url = "http://127.0.0.1:28103")
                  )
            ),
          ),
      )
      .addConfigTransforms((_, conf) =>
        (CNNodeConfigTransforms
          .setSomeSvAppPortsPrefix(28, Seq("sv1Local", "sv2Local", "sv3Local", "sv4Local")) compose
          CNNodeConfigTransforms.setSomeScanAppPortsPrefix(28, Seq("sv1ScanLocal")) compose
          CNNodeConfigTransforms.setSomeValidatorAppPortsPrefix(28, Seq("sv1ValidatorLocal")))(
          conf
        )
      )
      .withManualStart

  val dumpPath = Files.createTempFile("participant-dump", ".json")

  "Recover from losing the domain" in { implicit env =>
    import env.environment.scheduler
    import env.executionContext

    val svBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
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

    withCantonSvNodes(
      (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
      "participants-before-disaster",
      sequencersMediators = false,
    )() {

      val (identities, timestampBeforeDisaster) = withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        "sequencers-mediators-before-disaster",
        participants = false,
      )() {
        startAllSync(
          sv1ScanBackend,
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
          sv1ValidatorBackend,
        )

        val identities = withClueAndLog("Getting node identities dump") {
          svBackends.map(_.getDomainNodeIdentitiesDump())
        }

        actAndCheck("Create some transaction history", sv1WalletClient.tap(1337))(
          "Scan transaction history is recorded and wallet balance is updated",
          _ => {
            // buffer to account for domain fee payments
            assertInRange(sv1WalletClient.balance().unlockedQty, (1000, 2000))
            countTapsFromScan(sv1ScanBackend, 1337) shouldBe 1
          },
        )

        val domainId = identities.head.domainId
        val timestampBeforeDisaster = getDomainTimeOnParticipant(sv1Backend, domainId).toInstant

        waitForSvParticipantsToCatchpup(timestampBeforeDisaster, domainId)

        // Tap some more coin and wait for SVs to see it (even though we're going to recover to a point before it)
        sv1WalletClient.tap(1338)
        waitForSvParticipantsToCatchpup(Instant.now(), domainId)

        // Tap yet more coin without waiting for it to necessarily be ingested on all SVs
        sv1WalletClient.tap(1339)

        (identities, timestampBeforeDisaster)
      }

      // The sequencers and mediators have been shut down here, only participants are still alive

      withClueAndLog("Getting and writing disaster recovery dumps") {
        val dumps = svBackends.map(_.getDomainDataSnapshot(timestampBeforeDisaster))
        svBackends.zip(identities.zip(dumps)).foreach { case (sv, (ids, dump)) =>
          writeMigrationDumpFile(sv, ids, dump)
        }
      }

      withCantonSvNodes(
        (Some(sv1Backend), Some(sv2Backend), Some(sv3Backend), Some(sv4Backend)),
        "disaster-recovery",
        overrideSvDbsSuffix = Some("disaster_recovery_new"),
        overrideSequencerDriverDbSuffix = Some("disaster_recovery_new"),
        autoInit = false,
        portsRange = Some(28),
      )(
      ) {

        Using.resources(
          createUpgradeNode(1, sv1Backend, sv1LocalBackend, retryProvider, wallClock, 28),
          createUpgradeNode(2, sv1Backend, sv2LocalBackend, retryProvider, wallClock, 28),
          createUpgradeNode(3, sv1Backend, sv3LocalBackend, retryProvider, wallClock, 28),
          createUpgradeNode(4, sv1Backend, sv4LocalBackend, retryProvider, wallClock, 28),
        ) { case (newDomainNode1, newDomainNode2, newDomainNode3, newDomainNode4) =>
          val allNodes =
            Seq(newDomainNode1, newDomainNode2, newDomainNode3, newDomainNode4)

          withClueAndLog("Starting new SV apps") {
            startAllSync(
              sv1LocalBackend,
              sv2LocalBackend,
              sv3LocalBackend,
              sv4LocalBackend,
              sv1ScanLocalBackend,
              sv1ValidatorLocalBackend,
            )
          }

          checkMigrateDomainOnNodes(allNodes)

          withClueAndLog("Old balance has been transferred to new domain") {
            assertInRange(sv1WalletLocalClient.balance().unlockedQty, (1000, 2000))
          }
          withClueAndLog("New domain is functional") {
            sv1WalletLocalClient.tap(1337)
            assertInRange(sv1WalletLocalClient.balance().unlockedQty, (2000, 3000))
          }
        }
      }
    }
  }

  private def waitForSvParticipantsToCatchpup(timestamp: Instant, domainId: DomainId)(implicit
      env: CNNodeTestConsoleEnvironment
  ) = {
    withClue("Waiting for SV participants to catchup") {
      eventually() {
        Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach(backend => {
          getDomainTimeOnParticipant(backend, domainId).toInstant.isAfter(
            timestamp
          ) shouldBe true withClue s"${backend.name} should be caught up"
        })
      }
    }
  }

  private def getDomainTimeOnParticipant(svBackend: SvAppBackendReference, domainId: DomainId) =
    svBackend.appState.participantAdminConnection
      .getDomainTime(
        domainId,
        NonNegativeDuration.tryFromDuration(10.seconds),
      )
      .futureValue
      .timestamp

  private def writeMigrationDumpFile(
      sv: SvAppBackendReference,
      ids: DomainNodeIdentities,
      dump: DomainDataSnapshot,
  ): Unit = {

    def clearOrCreate(f: File) = {
      if (f.exists) f.clear() else f.createFile()
    }

    val participantIdDumpFile = participantIdentitiesFilePath(sv.name)
    clearOrCreate(participantIdDumpFile)

    participantIdDumpFile.write(ids.participant.toJson.spaces2)

    val fullDumpFile = migrationDumpFilePath(sv.name)
    clearOrCreate(fullDumpFile)
    val fullDump = DomainMigrationDump(
      migrationId = 1,
      ids,
      dump,
    )
    fullDumpFile.write(fullDump.toJson.spaces2)
  }

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: Double) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.coinAmount)).contains(BigDecimal(tapAmount))
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
