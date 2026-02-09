package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.config.{NonNegativeDuration, NonNegativeFiniteDuration}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
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
import org.lfdecentralizedtrust.splice.scan.config.CacheConfig
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

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  // We manually force a snapshot on sv1 in the test. The other SVs
  // won't have a snapshot at that time so the assertions in the
  // update history sanity plugin wil fail.
  override lazy val skipAcsSnapshotChecks = true

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
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
        )(
          config.copy(
            svApps = config.svApps ++
              Seq(1, 2, 3, 4).map(sv =>
                InstanceName.tryCreate(s"sv${sv}Local") ->
                  config
                    .svApps(InstanceName.tryCreate(s"sv$sv"))
              ),
            scanApps = config.scanApps ++ Seq(1, 2, 3, 4).map(sv =>
              InstanceName.tryCreate(s"sv${sv}ScanLocal") ->
                config.scanApps(InstanceName.tryCreate(s"sv${sv}Scan"))
            ),
          )
        )
      )
      .addConfigTransforms((_, config) => {
        ConfigTransforms
          .bumpCantonSyncPortsBy(22_000, _.contains("Local"))
          .compose(
            ConfigTransforms.updateAllSvAppConfigs { (name, config) =>
              if (name.endsWith("Local")) {
                config
              } else {
                config.copy(
                  domainMigrationDumpPath =
                    Some((migrationDumpDir(name) / "domain_migration_dump.json").path)
                )
              }
            }
          )(config)
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .withManualStart

  "migrate global domain to new nodes without downtime" in { implicit env =>
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
    val allNewBackends = Seq(sv1LocalBackend, sv2LocalBackend, sv3LocalBackend, sv4LocalBackend)
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
        allNewBackends.zip(allBackends).par.map { case (newBackend, oldBackend) =>
          TraceContext.withNewTraceContext(s"init ${oldBackend.name}") { implicit traceContext =>
            val expectedDirectory = directoryForDump(oldBackend.name, synchronizerFreezeTime)
            expectedDirectory.exists shouldBe true
            val lsuSynchronizerState =
              oldBackend.sequencerClient.topology.transactions.logical_upgrade_state()
            val dump = DomainMigrationInitializer
              .loadDomainMigrationDump((expectedDirectory / "domain_migration_dump.json").path)
            val sequencerInitializer =
              new NodeInitializer(
                sequencerAdminConnection(newBackend),
                retryProvider,
                loggerFactory,
              )
            val newMediatorAdminConnection = mediatorAdminConnection(newBackend)
            val mediatorInitializer =
              new NodeInitializer(newMediatorAdminConnection, retryProvider, loggerFactory)

            clue(s"init ${oldBackend.name} sequencer and mediator from dump") {
              newBackend.sequencerClient.health.wait_for_ready_for_id()
              sequencerInitializer
                .initializeFromDump(dump.nodeIdentities.sequencer)
                .futureValue

              newBackend.mediatorClient.health.wait_for_ready_for_id()
              mediatorInitializer.initializeFromDump(dump.nodeIdentities.mediator).futureValue
            }
            val staticSynchronizerParameters =
              oldBackend.sequencerClient.synchronizer_parameters.static.get()
            val newStaticSyncParams = staticSynchronizerParameters.copy(
              serial = staticSynchronizerParameters.serial + NonNegativeInt.one
            )

            clue(s"init ${oldBackend.name} sequencer from synchronizer predecessor") {
              newBackend.sequencerClient.health.wait_for_ready_for_initialization()
              newBackend.sequencerClient.setup.initialize_from_synchronizer_predecessor(
                lsuSynchronizerState,
                newStaticSyncParams,
              )
              eventually(2.minutes) {
                newBackend.sequencerClient.topology.transactions
                  .list(decentralizedSynchronizerId)
                  .result
                  .size shouldBe topologyTransactionsOnTheSync
              }
            }

            clue(s"init ${oldBackend.name} mediator") {
              newMediatorAdminConnection
                .initialize(
                  PhysicalSynchronizerId(
                    decentralizedSynchronizerId,
                    newStaticSyncParams.toInternal,
                  ),
                  GrpcSequencerConnection
                    .create(
                      newBackend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
                    )
                    .value,
                  newBackend.config.localSynchronizerNode.value.mediator.sequencerRequestAmplification.toInternal,
                )
                .futureValue
              eventually(2.minutes) {
                newBackend.mediatorClient.topology.transactions
                  .list(decentralizedSynchronizerId)
                  .result
                  .size shouldBe topologyTransactionsOnTheSync
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
        allBackends.zip(allNewBackends).par.map { case (oldBackend, newBackend) =>
          oldBackend.sequencerClient.topology.lsu.sequencer_successors
            .propose_successor(
              oldBackend.sequencerClient.id,
              NonEmpty(
                Seq,
                URI.create(
                  newBackend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
                ),
              ),
              decentralizedSynchronizerId,
            )
        }
      }

      clue("transfer traffic after upgrade") {
        allNewBackends.zip(allBackends).par.map { case (newBackend, oldBackend) =>
          eventually(timeUntilSuccess = 2.minutes) {
            oldBackend.mediatorClient.testing
              .fetch_synchronizer_time(NonNegativeDuration.ofSeconds(10)) should be > upgradeTime
          }

          clue(s"transfer traffic for  ${oldBackend.name}") {
            newBackend.sequencerClient.traffic_control
              .set_lsu_state(oldBackend.sequencerClient.traffic_control.get_lsu_state())
          }
        }
      }

      val newSequencerUrls = allNewBackends.map { newBackend =>
        newBackend.config.localSynchronizerNode.value.sequencer.externalPublicApiUrl
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
        // remove extended timeUntilSuccess after we also migrate traffic
        actAndCheck(timeUntilSuccess = 2.minutes)(
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
        case data.GrpcSequencerConnection(endpoints, _, _, _, _) => endpoints
      }
    } yield endpoint.toString).toSet
  }

}
