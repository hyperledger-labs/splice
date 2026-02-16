package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.config.{NonNegativeDuration, NonNegativeFiniteDuration}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllSvAppFoundDsoConfigs_
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
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.onboarding.domainmigration.DomainMigrationInitializer
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.validator.automation.ReconcileSequencerConnectionsTrigger
import org.scalatest.time.{Minutes, Span}

import java.net.URI
import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.concurrent.duration.DurationInt
import scala.util.Using

class LogicalSynchronizerUpgradeIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with StandaloneCanton
    with HasExecutionContext {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected lazy val resetRequiredTopologyState: Boolean = false

  override def dbsSuffix = "lsu"
  private val UpgradePSid = 1

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  // We manually force a snapshot on sv1 in the test. The other SVs
  // won't have a snapshot at that time so the assertions in the
  // update history sanity plugin wil fail.
  override lazy val skipAcsSnapshotChecks = true

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransforms((_, config) => {
        ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
          config.copy(
            domainMigrationDumpPath =
              Some((migrationDumpDir(name) / "domain_migration_dump.json").path)
          )
        }(config)
      })
      .addConfigTransforms((_, config) => {
        ConfigTransforms
          .updateAllSvAppConfigs { (_, config) =>
            config.copy(
              currentPhysicalSynchronizerId = Some(0),
              localSynchronizerNodes =
                config.localSynchronizerNodes + (UpgradePSid -> config.localSynchronizerNode.value),
            )
          }
          .compose(ConfigTransforms.bumpCantonPSyncPortsBy(UpgradePSid, 22_000))(config)
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .withAmuletPrice(walletAmuletPrice)
      .addConfigTransform((_, config) => {
        updateAllSvAppFoundDsoConfigs_(c => c.copy(zeroTransferFees = true))(config)
      })
      .withManualStart

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)

  "migrate global domain to new nodes without downtime" in { implicit env =>
    val allNodes = Seq[AppBackendReference](
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
    startAllSync(
      allNodes*
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
      clue(s"${validatorBackend.name} has tapped a amulet") {
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

    startValidatorAndTapAmulet(aliceValidatorBackend, aliceValidatorWalletClient)

    val newSynchronizerSerial = decentralizedSynchronizerPSId.serial + NonNegativeInt.one
    val successorPsid = decentralizedSynchronizerPSId.copy(serial = newSynchronizerSerial)
    // Upload after starting validator which connects to global
    // synchronizers as upload_dar_unless_exists vets on all
    // connected synchronizers.
    aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
    val externalPartyOnboarding = clue("Create external party and transfer 40 amulet to it") {
      createExternalParty(aliceValidatorBackend, aliceValidatorWalletClient)
    }
    val allBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
    val upgradeTimeInstant = Instant
      .now()
      .plusSeconds(60)
      .truncatedTo(
        ChronoUnit.SECONDS
      )
    val upgradeTime = CantonTimestamp.tryFromInstant(
      upgradeTimeInstant
    )
    withCantonSvNodes(
      (
        None,
        None,
        None,
        None,
      ),
      participants = false,
      logSuffix = "global-domain-migration",
      extraSequencerConfig = Seq(
        s"""parameters.sequencing-time-lower-bound-exclusive=$upgradeTimeInstant"""
      ),
    )() {

      val announcement = clue(s"Announce upgrade at $upgradeTime") {
        allBackends.par.map { backend =>
          backend.participantClientWithAdminToken.topology.lsu.announcement
            .propose(
              successorPsid,
              upgradeTime,
              store = Some(Synchronizer(decentralizedSynchronizerId)),
            )
        }
        allBackends.head.participantClientWithAdminToken.topology.lsu.announcement
          .list()
          .head
      }

      val synchronizerFreezeTime = announcement.context.validFrom
      val topologyTransactionsOnTheSync = sv1Backend.sequencerClient.topology.transactions
        .list(store = Synchronizer(decentralizedSynchronizerId))
        .result
        .size - 1 // minus 1 for the logical upgrade transaction

      clue("trigger dump") {
        allBackends.par.map { backend =>
          eventuallySucceeds(suppressErrors = false) {
            backend.triggerDecentralizedSynchronizerMigrationDump(
              0,
              Some(
                synchronizerFreezeTime
              ),
            )
          }
        }
      }
      clue("init new nodes") {
        allBackends.map { backend =>
          TraceContext.withNewTraceContext(s"init ${backend.name}") { implicit traceContext =>
            val expectedDirectory = directoryForDump(backend.name, synchronizerFreezeTime)
            expectedDirectory.exists shouldBe true
            val lsuSynchronizerState =
              backend.sequencerClient.topology.transactions.logical_upgrade_state()
            val dump = DomainMigrationInitializer
              .loadDomainMigrationDump((expectedDirectory / "domain_migration_dump.json").path)
            Using.resources(
              sequencerAdminConnection(UpgradePSid, backend),
              mediatorAdminConnection(UpgradePSid, backend),
            ) { (newSequencerAdminConnection, newMediatorAdminConnection) =>
              val sequencerInitializer =
                new NodeInitializer(
                  newSequencerAdminConnection,
                  retryProvider,
                  loggerFactory,
                )
              val mediatorInitializer =
                new NodeInitializer(newMediatorAdminConnection, retryProvider, loggerFactory)
              val upgradeSequencerClient = backend.sequencerClientForPSId(UpgradePSid)
              val upgradeMediatorClient = backend.mediatorClientForPSId(UpgradePSid)
              clue(s"init ${backend.name} sequencer and mediator from dump") {
                upgradeSequencerClient.health.wait_for_ready_for_id()
                sequencerInitializer
                  .initializeFromDump(dump.nodeIdentities.sequencer)
                  .futureValue

                upgradeMediatorClient.health.wait_for_ready_for_id()
                mediatorInitializer.initializeFromDump(dump.nodeIdentities.mediator).futureValue
              }
              val staticSynchronizerParameters =
                backend.sequencerClient.synchronizer_parameters.static.get()
              val newStaticSyncParams = staticSynchronizerParameters.copy(
                serial = staticSynchronizerParameters.serial + NonNegativeInt.one
              )

              clue(s"init ${backend.name} sequencer from synchronizer predecessor") {
                upgradeSequencerClient.health.wait_for_ready_for_initialization()
                upgradeSequencerClient.setup.initialize_from_synchronizer_predecessor(
                  lsuSynchronizerState,
                  newStaticSyncParams,
                )
                eventually(2.minutes) {
                  upgradeSequencerClient.topology.transactions
                    .list(decentralizedSynchronizerId)
                    .result
                    .size shouldBe topologyTransactionsOnTheSync
                }
              }

              clue(s"init ${backend.name} mediator") {
                val upgradeSynchronizerNodeConfig = backend.config.localSynchronizerNodes
                  .get(UpgradePSid)
                  .value
                newMediatorAdminConnection
                  .initialize(
                    PhysicalSynchronizerId(
                      decentralizedSynchronizerId,
                      newStaticSyncParams.toInternal,
                    ),
                    GrpcSequencerConnection
                      .create(
                        upgradeSynchronizerNodeConfig.sequencer.externalPublicApiUrl
                      )
                      .value,
                    upgradeSynchronizerNodeConfig.mediator.sequencerRequestAmplification.toInternal,
                  )
                  .futureValue
                eventually(2.minutes) {
                  upgradeMediatorClient.topology.transactions
                    .list(decentralizedSynchronizerId)
                    .result
                    .size shouldBe topologyTransactionsOnTheSync
                }
              }
            }

          }
        }
      }

      Seq(
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
        aliceValidatorBackend,
      ).par.foreach(
        _.validatorAutomation
          .trigger[ReconcileSequencerConnectionsTrigger]
          .pause()
          .futureValue
      )

      clue("Announce new sequencer urls") {
        allBackends.par.map { backend =>
          backend.sequencerClient.topology.lsu.sequencer_successors
            .propose_successor(
              backend.sequencerClient.id,
              NonEmpty(
                Seq,
                URI.create(
                  backend.config.localSynchronizerNodes
                    .get(UpgradePSid)
                    .value
                    .sequencer
                    .externalPublicApiUrl
                ),
              ),
              decentralizedSynchronizerId,
            )
        }
      }

      clue("transfer traffic after upgrade") {
        allBackends.par.map { backend =>
          eventually(timeUntilSuccess = 2.minutes) {
            backend.mediatorClient.testing
              .fetch_synchronizer_time(NonNegativeDuration.ofSeconds(10)) should be > upgradeTime
          }

          clue(s"transfer traffic for  ${backend.name}") {
            backend
              .sequencerClientForPSId(UpgradePSid)
              .traffic_control
              .set_lsu_state(backend.sequencerClient.traffic_control.get_lsu_state())
          }
        }
      }

      val newSequencerUrls = allBackends.map { backend =>
        backend.config.localSynchronizerNodes
          .get(UpgradePSid)
          .value
          .sequencer
          .externalPublicApiUrl
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

      clue("Validator connects to the new sequencers and sync topology") {
        eventually(60.seconds) {
          val clientWithAdminToken = aliceValidatorBackend.participantClientWithAdminToken
          participantIsConnectedToNewSynchronizer(clientWithAdminToken)
        }
      }

      clue("SVs connect to the new sequencers and sync topology") {
        allBackends.par.map { backend =>
          eventually() {
            participantIsConnectedToNewSynchronizer(backend.participantClientWithAdminToken)
          }
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
        actAndCheck()(
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
            BigDecimal(
              aliceValidatorBackend
                .getExternalPartyBalance(externalPartyOnboarding.party)
                .totalUnlockedCoin
            ) should be(BigDecimal(30))
          },
        )
      }
      // new sync nodes are started in process so to avoid log noise we stop everything before the test ends
      stopAllAsync(aliceValidatorBackend).futureValue
      aliceValidatorBackend.participantClientWithAdminToken.synchronizers.disconnect_all()
      stopAllAsync(allNodes*).futureValue
      allBackends.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
    }
  }

  def sequencerAdminConnection(
      psid: Int,
      backend: SvAppBackendReference,
  ): SequencerAdminConnection =
    new SequencerAdminConnection(
      backend.config.localSynchronizerNodes.get(psid).value.sequencer.adminApi,
      backend.spliceConsoleEnvironment.environment.config.monitoring.logging.api,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )

  def mediatorAdminConnection(psid: Int, backend: SvAppBackendReference): MediatorAdminConnection =
    new MediatorAdminConnection(
      backend.config.localSynchronizerNodes.get(psid).value.mediator.adminApi,
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
        case data.GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints
      }
    } yield endpoint.toString).toSet
  }

}
