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
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.scan.config.ScanRollForwardLsuConfig
import org.lfdecentralizedtrust.splice.sv.config.{
  SvOnboardingConfig,
  SvSynchronizerNodeConfig,
  SvSynchronizerNodesConfig,
}
import org.lfdecentralizedtrust.splice.util.*
import org.scalatest.time.{Minutes, Span}
import org.scalatest.TryValues

import java.time.{Duration, Instant}
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_24
class RollForwardLsuDRIntegrationTest
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
                          // TODO(#4784) Test these with non-None values.
                          exportTimes = None,
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
                  .focus(_.rollForwardLsu)
                  .replace(Some(ScanRollForwardLsuConfig(upgradeTime = None)))
            ),
          ),
        (_, config) => ConfigTransforms.withBftSequencers(_.contains("Local"))(config),
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms
          // This bumps current but not legacy
          .bumpCantonSyncPortsBy(22_000, name => name.contains("Local"))(config)
      )
      .withAmuletPrice(walletAmuletPrice)
      .withManualStart

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)

  // There are a few different timestamps involved in DR:
  // 1. The timestamp at which we export topology.
  // 2. The timestamp at which we export traffic, this is usually just the last sequenced message.
  // 3. The timestamp at which we stop accepting messages on the broken synchronizer.
  //    Assuming the synchronizer didn't explode and stopped fully on its own you configure this in Canton under `max-sequencing-time`.
  // 4. The time at which new sequenced messages are allowed on the new synchronizer.
  //    This must be >= the time at which topology is exported. After this time
  //    LSU sequencing test

  "roll forward LSU DR" in { implicit env =>
    val upgradeTime = env.clock.now.plusSeconds(120)
    val
    val allNodes = Seq[AppBackendReference](
      sv1ScanBackend,
      sv2ScanBackend,
      sv3ScanBackend,
      sv4ScanBackend,
      sv1Backend,
      sv2Backend,
      sv3Backend,
      sv4Backend,
      aliceValidatorBackend,
      // TODO(#4682): Fix with BFT connections
      // sv1ValidatorBackend,
      // sv2ValidatorBackend,
      // sv3ValidatorBackend,
      // sv4ValidatorBackend,
    )

    startAllSync(allNodes*)

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

    val newSynchronizerSerial = decentralizedSynchronizerPSId.serial + NonNegativeInt.two
    val successorPsid = decentralizedSynchronizerPSId.copy(serial = newSynchronizerSerial)
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
      startAllSync(((allSvLocalBackends: Seq[AppBackendReference]) ++ allScanLocalBackends)*)

      val topologyTransactionsOnTheSync = sv1Backend.sequencerClient.topology.transactions
        .list(store = Synchronizer(decentralizedSynchronizerId))
        .result
        .size

      clue("new nodes are initialized") {
        allSvBackends.map { backend =>
          val upgradeSequencerClient = backend.sequencerClientFor(_.current)
          val upgradeMediatorClient = backend.mediatorClientFor(_.current)
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

      def participantIsConnectedToNewSynchronizer(
          sv: SvAppBackendReference
      ) = {
        sv.participantClient.synchronizers
          .list_connected()
          .loneElement
          .physicalSynchronizerId shouldBe successorPsid
        val sequencerUrlSet = getSequencerUrlSet(
          sv.participantClient,
          decentralizedSynchronizerAlias,
        )
        sequencerUrlSet should have size 1
        sequencerUrlSet shouldBe Set(
          sv.config.localSynchronizerNodes.current.sequencer.externalPublicApiUrl
            .stripPrefix("http://")
        )
        sv.participantClient.topology.transactions
          .list(store = Synchronizer(decentralizedSynchronizerId))
          .result
          .size should be >= topologyTransactionsOnTheSync
      }

      clue("physical synchronizers configs are set") {
        eventually() {
          val rulesAndState =
            sv1LocalBackend.appState.dsoStore.getDsoRulesWithSvNodeStates().futureValue
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

      clue("SVs connect to the new sequencers and sync topology") {
        allSvLocalBackends.par.map { backend =>
          eventually() {
            participantIsConnectedToNewSynchronizer(backend)
          }
        }
      }

      clue("Scan reports active physical synchronizer serial 1 after upgrade") {
        eventually() {
          allScanLocalBackends.foreach { scan =>
            scan.getActivePhysicalSynchronizerSerial() shouldBe newSynchronizerSerial
          }
        }
      }

      clue("LSU sequencers are registered") {
        eventually() {
          inside(sv1ScanLocalBackend.listDsoSequencers()) {
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

      clue("Alice is connected to new physical synchronizer") {
        eventually() {
          aliceValidatorBackend.participantClient.synchronizers
            .list_connected()
            .loneElement
            .physicalSynchronizerId
            .serial shouldBe newSynchronizerSerial
        }
      }

      clue("Alice can tap") {
        aliceValidatorWalletClient.tap(100.0)
      }

      clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
        stopAllAsync(allNodes*).futureValue
        allSvLocalBackends.par.foreach(
          _.participantClient.synchronizers.disconnect_all()
        )
        aliceValidatorBackend.participantClient.synchronizers.disconnect_all()
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
