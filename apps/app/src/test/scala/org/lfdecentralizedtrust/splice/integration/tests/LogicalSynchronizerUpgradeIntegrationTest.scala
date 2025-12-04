package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.google.protobuf.ByteString
import monocle.Monocle.toAppliedFocusOps
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  NetworkAppClientConfig,
  SynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.DecentralizedSynchronizerMigrationIntegrationTest.migrationDumpDir
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.integration.tests.SvMigrationApiIntegrationTest.directoryForDump
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection.BftScanClientConfig.TrustSingle
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.scan.config.CacheConfig
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.splitwell.config.{
  SplitwellDomains,
  SplitwellSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.DomainMigration
import org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration.DomainMigrationInitializer
import org.lfdecentralizedtrust.splice.util.*
import org.scalatest.time.{Minute, Span}
import org.slf4j.event.Level

import java.net.URI
import java.time.Duration
import java.util.UUID
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.jdk.CollectionConverters.SeqHasAsJava

class LogicalSynchronizerUpgradeIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with DomainMigrationUtil
    with StandaloneCanton
    with SplitwellTestUtil
    with HasExecutionContext {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def dbsSuffix = "lsu"

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
      .addConfigTransforms((_, conf) =>
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
        )(conf)
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

  "migrate global domain to new nodes with downtime" in { implicit env =>
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
      eventually() {
        validatorBackend
          .getExternalPartyBalance(externalParty)
          .totalUnlockedCoin shouldBe "40.0000000000"
      }
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
        None,
        None,
        None,
        None,
      ),
      participants = false,
      logSuffix = "global-domain-migration",
      enableBftSequencer = true,
    )() {
      // Upload after starting validator which connects to global
      // synchronizers as upload_dar_unless_exists vets on all
      // connected synchronizers.
      aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      val externalPartyOnboarding = clue("Create external party and transfer 40 amulet to it") {
        createExternalParty(aliceValidatorBackend, aliceValidatorWalletClient)
      }
      val allBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
      val allNewBackends = Seq(sv1LocalBackend, sv2LocalBackend, sv3LocalBackend, sv4LocalBackend)
      val migrationTime = CantonTimestamp.now().plusSeconds(60)
      val newSynchronizerSerial = decentralizedSynchronizerPSId.serial

      clue(s"Announce migration at $migrationTime") {
        allBackends.par.map { backend =>
          backend.participantClientWithAdminToken.topology.synchronizer_upgrade.announcement
            .propose(
              decentralizedSynchronizerPSId.copy(serial =
                newSynchronizerSerial + NonNegativeInt.one
              ),
              migrationTime,
            )

        }
      }

      clue("trigger dump") {
        allBackends.par.map { backend =>
          backend.triggerDecentralizedSynchronizerMigrationDump(
            0,
            Some(
              migrationTime.toInstant
            ),
          )
        }
      }

      clue("init new nodes") {

        allNewBackends.zip(allBackends).par.map { case (newBackend, oldBackend) =>
          val expectedDirectory = directoryForDump(newBackend.name, migrationTime.toInstant)
          expectedDirectory.exists shouldBe true
          val dump = DomainMigrationInitializer.loadDomainMigrationDump(expectedDirectory.path)
          val sequencerInitializer =
            new NodeInitializer(sequencerAdminConnection(newBackend), retryProvider, loggerFactory)
          val newMediatorAdminConnection = mediatorAdminConnection(newBackend)
          val mediatorInitializer =
            new NodeInitializer(newMediatorAdminConnection, retryProvider, loggerFactory)

          sequencerInitializer.initializeFromDumpAndWait(dump.nodeIdentities.sequencer).futureValue
          mediatorInitializer.initializeFromDumpAndWait(dump.nodeIdentities.mediator).futureValue

          val staticSynchronizerParameters =
            oldBackend.sequencerClient.synchronizer_parameters.static.get()
          val newStaticSyncParams = staticSynchronizerParameters.copy(
            serial = staticSynchronizerParameters.serial + NonNegativeInt.one
          )
          newBackend.sequencerClient.setup.initialize_from_synchronizer_predecessor(
            ByteString.copyFrom(
              dump.domainDataSnapshot.genesisState.value.asJava
            ),
            newStaticSyncParams,
          )

          newMediatorAdminConnection.initialize(
            PhysicalSynchronizerId(
              decentralizedSynchronizerId,
              newStaticSyncParams.toInternal,
            ),
            GrpcSequencerConnection.tryCreate(
              newBackend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
            ),
            newBackend.config.localSynchronizerNode.value.mediator.sequencerRequestAmplification,
          )
        }
      }

      val sequencerUrlSetBeforeUpgrade = getSequencerUrlSet(
        aliceValidatorBackend.participantClientWithAdminToken,
        decentralizedSynchronizerAlias,
      )

      clue("Announce new sequencer urls") {
        allBackends.par.map { backend =>
          backend.participantClientWithAdminToken.topology.synchronizer_upgrade.sequencer_successors
            .propose_successor(
              backend.sequencerClient.id,
              NonEmpty(
                Seq,
                URI.create(
                  backend.sequencerClient.config.publicApi
                    .focus(_.port)
                    .modify(_ + 22_000)
                    .endpointAsString
                ),
              ),
              decentralizedSynchronizerId,
            )
        }
      }

      clue("Validator connects to the new sequencers") {
        eventually() {
          val sequencerUrlSet = getSequencerUrlSet(
            aliceValidatorBackend.participantClientWithAdminToken,
            decentralizedSynchronizerAlias,
          )
          sequencerUrlSet should have size 4
          sequencerUrlSet.intersect(sequencerUrlSetBeforeUpgrade) shouldBe Set.empty
        }
      }

      clue("External party's balance has been preserved and it can transfer") {
        aliceValidatorBackend
          .getExternalPartyBalance(externalPartyOnboarding.party)
          .totalUnlockedCoin shouldBe "40.0000000000"
        val prepareSend =
          aliceValidatorBackend.prepareTransferPreapprovalSend(
            externalPartyOnboarding.party,
            aliceValidatorBackend.getValidatorPartyId(),
            BigDecimal(10.0),
            CantonTimestamp.now().plus(Duration.ofHours(24)),
            0L,
            Some("transfer-command-description"),
          )
        actAndCheck(
          "Submit signed TransferCommand creation",
          aliceValidatorBackend.submitTransferPreapprovalSend(
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
              aliceValidatorBackend
                .getExternalPartyBalance(externalPartyOnboarding.party)
                .totalUnlockedCoin
            ) should beWithin(BigDecimal(28), BigDecimal(30))
          },
        )
      }
    }
  }

  def sequencerAdminConnection(backend: SvAppBackendReference): SequencerAdminConnection =
    new SequencerAdminConnection(
      backend.config.localSynchronizerNode.value.sequencer.adminApi,
      backend.spliceConsoleEnvironment.environment.config.monitoring.logging.api,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )

  def mediatorAdminConnection(backend: SvAppBackendReference): MediatorAdminConnection =
    new MediatorAdminConnection(
      backend.config.localSynchronizerNode.value.mediator.adminApi,
      backend.spliceConsoleEnvironment.environment.config.monitoring.logging.api,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: BigDecimal) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.amuletAmount)).contains(tapAmount)
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
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

}
