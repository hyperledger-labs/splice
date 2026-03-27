package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
// import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{IntegrationTest}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.sv.config.{
  SvOnboardingConfig,
  SvSynchronizerNodeConfig,
  SvSynchronizerNodesConfig,
}
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeSequencingTestTrigger
import org.lfdecentralizedtrust.splice.util.*
import org.scalatest.time.{Minutes, Span}
import org.scalatest.TryValues

import java.time.{Duration, Instant}
import java.util.UUID
import monocle.macros.syntax.lens.*
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

class RollForwardLsuIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with StandaloneCanton
    with HasExecutionContext
    with SynchronizerFeesTestUtil
    with LsuTestUtil
    with TryValues {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected lazy val resetRequiredTopologyState: Boolean = false

  override def dbsSuffix = "lsu"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransforms(
        (_, config) => {
          ConfigTransforms
            .updateAllSvAppConfigs { (name, config) =>
              config.copy(
                // TODO(#4682) Make it work with BFT connections.
                bftSequencerConnection = false,
                domainMigrationDumpPath = Some(
                  (DomainMigrationUtil.migrationTestDumpDir(
                    name
                  ) / "domain_migration_dump.json").path
                ),
                parameters = config.parameters.copy(
                  spliceCachingConfigs = config.parameters.spliceCachingConfigs.copy(
                    physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
                  )
                ),
              )
            }
            .andThen(ConfigTransforms.updateAllScanAppConfigs { (_, config) =>
              config.copy(
                parameters = config.parameters.copy(
                  spliceCachingConfigs = config.parameters.spliceCachingConfigs.copy(
                    physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
                  )
                )
              )
            })(config)
        },
        (_, config) =>
          config.copy(
            // The instance after the roll-forward with current = serial 2, legacy = serial 0
            svApps = config.svApps ++
              Seq(1, 2, 3, 4).map(sv =>
                InstanceName.tryCreate(s"sv${sv}Local") ->
                  config
                    .svApps(InstanceName.tryCreate(s"sv$sv"))
                    .focus(_.localSynchronizerNodes)
                    .modify(c => c.copy(legacy = c.current.some))
                    .focus(_.onboarding)
                    .modify(c =>
                      SvOnboardingConfig
                        .RollForwardLsu(
                          c.value.name,
                          NonNegativeInt.tryCreate(2),
                          ProtocolVersion.v34,
                        )
                        .some
                    )
              ),
            scanApps = config.scanApps ++ Seq(1, 2, 3, 4).map(sv =>
              // The instance after the roll-forward with current = serial 2, legacy = serial 0
              InstanceName.tryCreate(s"sv${sv}ScanLocal") ->
                config
                  .scanApps(InstanceName.tryCreate(s"sv${sv}Scan"))
                  .focus(_.synchronizerNodes)
                  .modify(c => c.copy(legacy = c.current.some))
            ),
          ),
      )
      .withBftSequencersSuccessor
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms
          // This bumps current but not legacy
          .bumpCantonSyncPortsBy(22_000, name => name.contains("Local"))
          .andThen(
            ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
              // TODO(DACH-NY/canton-network-internal#4254) Reenable once this is fixed in Canton
              _.withPausedTrigger[LogicalSynchronizerUpgradeSequencingTestTrigger]
            )
          )(config)
      )
      .withAmuletPrice(walletAmuletPrice)
      .withManualStart

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)

  // The scenario we test is the following:
  // SVs schedule a migration from serial 0 to 1.
  // This never goes through. In the test we simply never configure the successor synchronizer for the SVs so the announcement gets published but
  // no synchronizer with serial 1 is initialized. Actually configuring the successor but ensuring nothing gets sequenced would both be tricky and slows the test down even more.
  // The SVs reconfigure their SV app to manually do an LSU to serial 2.
  "roll forward LSU" in { implicit env =>
    val allNodes = Seq[AppBackendReference](
      sv1ScanBackend,
      sv2ScanBackend,
      sv3ScanBackend,
      sv4ScanBackend,
      sv1Backend,
      sv2Backend,
      sv3Backend,
      sv4Backend,
      // TODO(#4682): Fix with BFT connections
      // sv1ValidatorBackend,
      // sv2ValidatorBackend,
      // sv3ValidatorBackend,
      // sv4ValidatorBackend,
    )

    startAllSync((allNodes ++ Seq(aliceValidatorBackend, bobValidatorBackend))*)

    // TODO(#4682): Fix with BFT connections
    // actAndCheck("Create some transaction history", sv1WalletClient.tap(1337))(
    //   "Scan transaction history is recorded and wallet balance is updated",
    //   _ => {
    //     // buffer to account for domain fee payments
    //     assertInRange(
    //       sv1WalletClient.balance().unlockedQty,
    //       (walletUsdToAmulet(1000), walletUsdToAmulet(2000)),
    //     )
    //     countTapsFromScan(sv1ScanBackend, walletUsdToAmulet(1337)) shouldBe 1
    //   },
    // )

    clue("All sequencers are registered") {
      eventually() {
        inside(sv1ScanBackend.listDsoSequencers()) {
          case Seq(DomainSequencers(synchronizerId, sequencers)) =>
            synchronizerId shouldBe decentralizedSynchronizerId
            sequencers should have size 8
            sequencers.foreach { sequencer =>
              sequencer.serial match {
                case Some(serial) =>
                  serial shouldBe 0
                  sequencer.migrationId shouldBe -1
                case None =>
                  sequencer.migrationId shouldBe 0
              }
            }
        }
      }
    }

    def onboardUserAndTapAmulet(
        validatorBackend: ValidatorAppBackendReference,
        walletClient: WalletAppClientReference,
        tapAmount: BigDecimal = 50.0,
        expectedAmulets: Range = 50 to 50,
    ) = {
      val walletUserParty = onboardWalletUser(walletClient, validatorBackend)
      walletClient.tap(tapAmount)
      clue(s"${validatorBackend.name} has tapped a amulet") {
        checkWallet(
          walletUserParty,
          walletClient,
          Seq((walletUsdToAmulet(expectedAmulets.start), walletUsdToAmulet(expectedAmulets.end))),
        )
      }
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

    onboardUserAndTapAmulet(aliceValidatorBackend, aliceValidatorWalletClient)

    // account for the cancellation
    val newSynchronizerSerial = decentralizedSynchronizerPSId.serial + NonNegativeInt.two
    val successorPsid = decentralizedSynchronizerPSId.copy(serial = newSynchronizerSerial)
    // Upload after starting validator which connects to global
    // synchronizers as upload_dar_unless_exists vets on all
    // connected synchronizers.
    aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
    clue("Create external party and transfer 40 amulet to it") {
      createExternalParty(aliceValidatorBackend, aliceValidatorWalletClient)
    }
    val topologyFreezeTime = CantonTimestamp.now()
    val upgradeTime = CantonTimestamp.now().plusSeconds(60)
    clue("Schedule logical synchronizer upgrade") {
      scheduleLsu(topologyFreezeTime, upgradeTime, newSynchronizerSerial.value.toLong)
    }
    clue("Topology state contains LSU announcement") {
      eventually(3.minutes) {
        sv1ScanBackend.participantClient.topology.lsu.announcement
          .list(Some(decentralizedSynchronizerId)) should have size (1)
      }
    }
    val allSvBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
    val allSvLocalBackends = Seq(sv1LocalBackend, sv2LocalBackend, sv3LocalBackend, sv4LocalBackend)
    val allScanBackends = Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend)
    val allScanLocalBackends =
      Seq(sv1ScanLocalBackend, sv2ScanLocalBackend, sv3ScanLocalBackend, sv4ScanLocalBackend)
    // Stop first
    stopAllAsync(allSvBackends*).futureValue
    stopAllAsync(allScanBackends*).futureValue
    withCantonSvNodes(
      (
        None,
        None,
        None,
        None,
      ),
      participants = false,
      enableBftSequencer = true,
      logSuffix = "global-synchronizer-upgrade",
    )() {
      // Wait first so that the participant has observed the timestamp and will happily migrate.
      clue(s"wait for upgrade time $upgradeTime") {
        Threading.sleep(Duration.between(Instant.now(), upgradeTime.toInstant).toMillis.abs)
      }

      // Restart SV app with roll-forward LSU config
      startAllSync(allSvLocalBackends*)
      startAllSync(allScanLocalBackends*)

      val topologyTransactionsOnTheSync = sv1Backend.sequencerClient.topology.transactions
        .list(store = Synchronizer(decentralizedSynchronizerId))
        .result
        .size

      clue("new nodes are initialized") {
        allSvBackends.map { backend =>
          val upgradeSequencerClient = backend.sequencerClientFor(_.successor.value)
          val upgradeMediatorClient = backend.mediatorClientFor(_.successor.value)
          clue(s"check ${backend.name} initialized sequencer from synchronizer predecessor") {
            eventuallySucceeds(2.minutes) {
              upgradeSequencerClient.topology.transactions
                .list(decentralizedSynchronizerId)
                .result
                .size shouldBe topologyTransactionsOnTheSync
            }
          }

          clue(s"check ${backend.name} initialized mediator") {
            eventuallySucceeds(2.minutes) {
              upgradeMediatorClient.topology.transactions
                .list(decentralizedSynchronizerId)
                .result
                .size shouldBe topologyTransactionsOnTheSync
            }
          }
        }
      }

      val newSequencerUrls = allSvBackends.map { backend =>
        backend.config.localSynchronizerNodes.successor.value.sequencer.externalPublicApiUrl
          .stripPrefix("http://")
      }

      def participantIsConnectedToNewSynchronizer(
          clientWithAdminToken: ParticipantClientReference
      ) = {
        clientWithAdminToken.synchronizers
          .list_connected()
          .loneElement
          .physicalSynchronizerId shouldBe successorPsid
        val sequencerUrlSet = getSequencerUrlSet(
          clientWithAdminToken,
          decentralizedSynchronizerAlias,
        )
        sequencerUrlSet should have size 4
        sequencerUrlSet should contain theSameElementsAs newSequencerUrls.toSet
        clientWithAdminToken.topology.transactions
          .list(store = Synchronizer(decentralizedSynchronizerId))
          .result
          .size should be >= topologyTransactionsOnTheSync
      }

      clue("physical synchronizers configs are set") {
        eventually() {
          val rulesAndState =
            sv1Backend.appState.dsoStore.getDsoRulesWithSvNodeStates().futureValue
          val nodeStates = rulesAndState.svNodeStates.values
            .map(
              _.payload.state.synchronizerNodes
                .get(decentralizedSynchronizerId.toProtoPrimitive)
            )
          nodeStates
            .flatMap { node =>
              node.physicalSynchronizers.toScala.value.asScala.get(0)
            }
            .map(_.sequencer.toScala.value.url) should contain theSameElementsAs Seq(
            "http://localhost:5108",
            "http://localhost:5208",
            "http://localhost:5308",
            "http://localhost:5408",
          )
          nodeStates
            .flatMap { node =>
              node.physicalSynchronizers.toScala.value.asScala.get(newSynchronizerSerial.value)
            }
            .map(_.sequencer.toScala.value.url) should contain theSameElementsAs Seq(
            "http://localhost:27108",
            "http://localhost:27208",
            "http://localhost:27308",
            "http://localhost:27408",
          )
        }
      }

      clue("Validator connects to the new sequencers and sync topology") {
        eventually(60.seconds) {
          val clientWithAdminToken = aliceValidatorBackend.participantClientWithAdminToken
          participantIsConnectedToNewSynchronizer(clientWithAdminToken)
        }
      }

      clue("SVs connect to the new sequencers and sync topology") {
        allSvBackends.par.map { backend =>
          eventually() {
            participantIsConnectedToNewSynchronizer(backend.participantClientWithAdminToken)
          }
        }
      }

      clue("Scan reports active physical synchronizer serial 1 after upgrade") {
        eventually() {
          Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend).foreach { scan =>
            scan.getActivePhysicalSynchronizerSerial() shouldBe newSynchronizerSerial
          }
        }
      }

      clue("LSU sequencers are registered") {
        eventually() {
          inside(sv1ScanBackend.listDsoSequencers()) {
            case Seq(DomainSequencers(synchronizerId, sequencers)) =>
              synchronizerId shouldBe decentralizedSynchronizerId
              sequencers should have size 12
              sequencers.groupBy(_.svName).foreach { case (sv, sequencers) =>
                clue(s"check sequencers for $sv") {
                  sequencers.size shouldBe 3
                  forExactly(1, sequencers) { sequencer =>
                    sequencer.serial.value shouldBe 0
                    sequencer.migrationId shouldBe -1
                  }
                  forExactly(1, sequencers) { sequencer =>
                    sequencer.serial.value shouldBe newSynchronizerSerial.value.toLong
                    sequencer.migrationId shouldBe -1
                  }
                  forExactly(1, sequencers) { sequencer =>
                    sequencer.serial should be(empty)
                    sequencer.migrationId shouldBe 0
                  }
                }
              }
          }
        }
      }

      clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
        // manually stop stuff as we destroy the new synchronizer as it runs in process
        val validators = Seq(aliceValidatorBackend, bobValidatorBackend, splitwellValidatorBackend)
        stopAllAsync(validators*).futureValue
        validators.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
        stopAllAsync(allNodes*).futureValue
        allSvBackends.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
      }
    }
  }

  def sequencerAdminConnection(
      node: SvSynchronizerNodesConfig => SvSynchronizerNodeConfig,
      backend: SvAppBackendReference,
  ): SequencerAdminConnection =
    new SequencerAdminConnection(
      node(backend.config.localSynchronizerNodes).sequencer.adminApi,
      backend.spliceConsoleEnvironment.environment.config.monitoring.logging.api,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )

  def mediatorAdminConnection(
      node: SvSynchronizerNodesConfig => SvSynchronizerNodeConfig,
      backend: SvAppBackendReference,
  ): MediatorAdminConnection =
    new MediatorAdminConnection(
      node(backend.config.localSynchronizerNodes).mediator.adminApi,
      backend.spliceConsoleEnvironment.environment.config.monitoring.logging.api,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )

  // private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: BigDecimal) = {
  //   listTransactionsFromScan(scan).count(
  //     _.tap.map(a => BigDecimal(a.amuletAmount)).contains(tapAmount)
  //   )
  // }

  // private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
  //   scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  // }

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
        case data.GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints
      }
    } yield endpoint.toString).toSet
  }

}
