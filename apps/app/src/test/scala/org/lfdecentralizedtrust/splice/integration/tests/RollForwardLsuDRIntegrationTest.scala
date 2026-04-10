package org.lfdecentralizedtrust.splice.integration.tests

import better.files.*
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.lsu.LsuRollForwardTimestamp
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
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
  override def usesDbs = super.usesDbs ++ super.usesDbs.map(_ + "beforedr")

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  // There are a few different timestamps involved in DR:
  // 1. The timestamp at which we export topology.
  // 2. The timestamp at which we export traffic, this is usually just the last sequenced message.
  // 3. The timestamp at which we stop accepting messages on the broken synchronizer.
  //    Assuming the synchronizer didn't explode and stopped fully on its own you configure this in Canton under `max-sequencing-time`.
  // 4. The time at which new sequenced messages are allowed on the new synchronizer.
  //    This must be >= the time at which topology is exported. After this time
  //    LSU sequencing test messages are possible but nothing else.
  //    This is configured in Canton under `lower-bound-sequencing-time-exclusive` for the new sequencer.
  // 5. The time at which all traffic resumes on the new synchronizer. This
  //    is configured in Canton under `upgrade-time` for the new sequencer.
  // In practice, this can often collapse and 1,2,3 are identical and 4,5 as well which is also the setup
  // we use in this test.

  // In theory we just want max sequencing time but Canton then waits for a time proof for that so we instead just use the timestamp of the last topology transaction.
  val topologyExportTimeFile = File.newTemporaryFile()
  // In theory we just want max sequencing time but the sequencer may not observe an event at that time so we dynamically use the last sequenced observed time
  val trafficExportTimeFile = File.newTemporaryFile()

  // This needs to be long enough in the future that we can start Canton and initialize for SVs.
  val maxSequencingTime = CantonTimestamp.now().plusSeconds(180)
  // 5s chosen by fair dice roll
  val lowerBoundSequencingTimeExclusive = maxSequencingTime.plusSeconds(5)
  val upgradeTime = lowerBoundSequencingTimeExclusive

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
                  (File(
                    DomainMigrationUtil.migrationTestDumpDir(
                      name
                    )
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
                          exportTimes = Some(
                            SvOnboardingConfig.RollForwardLsuTimestampConfig(
                              topologyExportTime = LsuRollForwardTimestamp.TimestampFromFile(
                                topologyExportTimeFile.path
                              ),
                              trafficExportTime = LsuRollForwardTimestamp.TimestampFromFile(
                                trafficExportTimeFile.path
                              ),
                              // Use ledger end, setting this to upgrade time will fail as that timestamp is not observed.
                              upgradeTime = None,
                            )
                          ),
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
        (_, config) => ConfigTransforms.withBftSequencers(_.contains("Local"))(config),
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, config) =>
        // Bump current for the non-local nodes before DR
        ConfigTransforms
          .bumpCantonSyncPortsBy(22_000, n => !n.contains("Local"))
          .andThen(
            // Bump current and legacy for the local nodes after DR
            ConfigTransforms
              .bumpCantonSyncPortsBy(23_000, _.contains("Local"))
              // Bump legacy back to the old port
              .andThen(ConfigTransforms.bumpCantonSyncLegacyPortsBy(22_000))
          )(config)
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
  //    LSU sequencing test messages are possible but nothing else.
  //    This is configured in Canton under `lower-bound-sequencing-time-exclusive` for the new sequencer.
  // 5. The time at which all traffic resumes on the new synchronizer. This
  //    is configured in Canton under `upgrade-time` for the new sequencer.
  // In practice, this can often collapse and 1,2,3 are identical and 4,5 as well.
  "roll forward LSU DR" in { implicit env =>
    withCantonSvNodes(
      (
        None,
        None,
        None,
        None,
      ),
      participants = false,
      enableBftSequencer = false,
      logSuffix = "roll-forward-lsu-before-dr",
      extraSequencerConfig =
        Seq(s"parameters.lsu-repair.global-max-sequencing-time-inclusive=${maxSequencingTime}"),
      overrideSvDbsSuffix = Some("lsubeforedr"),
    )() {
      val allNodes = Seq[AppBackendReference](
        sv1ScanBackend,
        sv2ScanBackend,
        sv3ScanBackend,
        sv4ScanBackend,
        sv1Backend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
      )

      clue("Start nodes before DR") {
        startAllSync(allNodes*)
      }
      clue("SVs are connected to their own sequencer") {
        forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
          clue(s"sv ${sv.name} is connected to all sequencers") {
            eventually(90.seconds) {
              val synchronizerConfig = sv.participantClient.synchronizers
                .config(sv.config.domains.global.alias)
                .value
              val sequencerConnections = synchronizerConfig.sequencerConnections
              val connection = sequencerConnections.connections.forgetNE.loneElement
              val endpoints = inside(connection) {
                case c: data.GrpcSequencerConnection => c.endpoints
              }
              endpoints.forgetNE.loneElement shouldBe Endpoint("localhost", sv.config.localSynchronizerNodes.current.sequencer.internalApi.port)
            }
          }
        }
      }
      clue("Stop old apps before DR") {
        stopAllAsync(allNodes*).futureValue
      }

      val topologyTxs =
        sv1Backend.participantClient.topology.transactions.list(decentralizedSynchronizerId)
      val topologyExportTime = topologyTxs.result.map(_.sequenced).max
      logger.info(s"Setting topology export time to $topologyExportTime")
      topologyExportTimeFile.overwrite(topologyExportTime.value.toString)

      withCantonSvNodes(
        (
          None,
          None,
          None,
          None,
        ),
        participants = false,
        enableBftSequencer = true,
        logSuffix = "roll-forward-lsu-dr",
        extraSequencerConfig = Seq(
          s"parameters.lsu-repair.lsu-sequencing-bounds-override.lower-bound-sequencing-time-exclusive=${lowerBoundSequencingTimeExclusive}",
          s"parameters.lsu-repair.lsu-sequencing-bounds-override.upgrade-time=${upgradeTime}",
        ),
        portsRange = Some(28),
      )() {

        val allSvLocalBackends =
          Seq(sv1LocalBackend, sv2LocalBackend, sv3LocalBackend, sv4LocalBackend)
        val allScanLocalBackends =
          Seq(sv1ScanLocalBackend, sv2ScanLocalBackend, sv3ScanLocalBackend, sv4ScanLocalBackend)

        // Wait first so that the participant has observed the timestamp and will happily migrate.
        clue(s"wait for upgrade time $upgradeTime") {
          Threading.sleep(Duration.between(Instant.now(), upgradeTime.toInstant).toMillis.abs)
        }

        val trafficExportTime = sv1Backend.participantClient.testing.fetch_synchronizer_time(
          decentralizedSynchronizerId,
          freshnessBound = NonNegativeFiniteDuration.ofSeconds(30),
        )
        logger.info(s"Setting traffic export time to $trafficExportTime")
        trafficExportTimeFile.overwrite(trafficExportTime.toString)

        // Restart SV app with roll-forward LSU config
        startAllSync(((allSvLocalBackends: Seq[AppBackendReference]) ++ allScanLocalBackends)*)

        val newSynchronizerSerial = decentralizedSynchronizerPSId.serial + NonNegativeInt.two
        val successorPsid = decentralizedSynchronizerPSId.copy(serial = newSynchronizerSerial)

        val topologyTransactionsOnTheSync = sv1Backend.sequencerClient.topology.transactions
          .list(store = Synchronizer(decentralizedSynchronizerId))
          .result
          .size

        clue("new nodes are initialized") {
          allSvLocalBackends.map { backend =>
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

        clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
          allSvLocalBackends.par.foreach(
            _.participantClient.synchronizers.disconnect_all()
          )
        }
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
