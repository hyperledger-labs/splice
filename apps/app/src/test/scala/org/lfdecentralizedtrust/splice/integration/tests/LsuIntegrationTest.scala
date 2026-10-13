package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.crypto.*
import better.files.File.apply
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.version.ProtocolVersion
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.config.{
  CircuitBreakersConfig,
  ConfigTransforms,
  NetworkAppClientConfig,
}
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.DomainSequencers
import org.lfdecentralizedtrust.splice.sv.config.{
  SvSynchronizerNodeConfig,
  SvSynchronizerNodesConfig,
}
import org.lfdecentralizedtrust.splice.sv.lsu.{LsuTransferTrafficTrigger, LsuTrigger}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.config.WalletAppClientConfig
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry.Http.BuyTrafficRequestStatus
import org.scalatest.time.{Minutes, Span}
import org.scalatest.TryValues

import java.time.{Duration, Instant}
import java.util.UUID
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_24
class LsuIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with StandaloneCanton
    with HasExecutionContext
    with SynchronizerFeesTestUtil
    with LsuTestUtil
    with TryValues
    with TokenStandardTest {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected lazy val resetRequiredTopologyState: Boolean = false

  override def dbsSuffix = "lsu"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(5, Minutes)))

  // We manually force a snapshot on sv1 in the test. The other SVs
  // won't have a snapshot at that time so the assertions in the
  // update history sanity plugin wil fail.
  override lazy val skipAcsSnapshotChecks = true

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    SynchronizerUpgradeUtil.migrationDumpDir.delete(swallowIOExceptions = true)
  }

  private val successorPv = ProtocolVersion.v36

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransforms((_, config) => {
        ConfigTransforms
          .updateAllSvAppConfigs { (name, config) =>
            config.copy(
              localSynchronizerNodes = config.localSynchronizerNodes
                .copy(successor =
                  config.localSynchronizerNodes.current
                    .copy(protocolVersion = successorPv)
                    .some
                ),
              domainMigrationDumpPath = Some(
                (SynchronizerUpgradeUtil.migrationTestDumpDir(
                  name
                ) / "domain_migration_dump.json").path
              ),
              parameters = config.parameters.copy(
                spliceCachingConfigs = config.parameters.spliceCachingConfigs.copy(
                  physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
                ),
                // sv-4 is intentionally a late-joining node in this test, which means the
                // sequencer spends some time catching up. This can cause the sv-4 apps'
                // circuit breakers to trip, which makes annoying logs and delays init.
                // The circuit breakers tripping a bit during catchup would be just fine
                // IRL, so as a simple fix to this test we disable them for all sv-4 apps
                // (see also the scan and validator app transforms below).
                circuitBreakers =
                  if (name == "sv4") CircuitBreakersConfig.never
                  else config.parameters.circuitBreakers,
              ),
            )
          }
          .andThen(
            ConfigTransforms
              .updateAllScanAppConfigs { (name, config) =>
                config.copy(
                  synchronizerNodes = config.synchronizerNodes.copy(
                    successor = Some(config.synchronizerNodes.current)
                  ),
                  parameters = config.parameters.copy(
                    spliceCachingConfigs = config.parameters.spliceCachingConfigs.copy(
                      physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
                    ),
                    circuitBreakers =
                      if (name == "sv4Scan") CircuitBreakersConfig.never
                      else config.parameters.circuitBreakers,
                  ),
                )
              }
          )
          .andThen(
            ConfigTransforms
              .updateAllValidatorAppConfigs { (name, config) =>
                config.copy(
                  parameters = config.parameters.copy(
                    circuitBreakers =
                      if (name == "sv4Validator") CircuitBreakersConfig.never
                      else config.parameters.circuitBreakers
                  )
                )
              }
          )(config)
      })
      .withBftSequencersSuccessor
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms
          .bumpCantonSyncSuccessorPortsBy(22_000)(config)
      )
      .addConfigTransform((_, c) =>
        c.copy(
          svApps = c.svApps ++
            Seq(
              InstanceName.tryCreate("sv1Local") ->
                c
                  .svApps(InstanceName.tryCreate(s"sv1"))
                  .focus(_.localSynchronizerNodes)
                  .modify(c =>
                    c.copy(
                      additionalLegacy = Seq(c.current),
                      current = c.successor.getOrElse(
                        throw new IllegalStateException("successor must be set")
                      ),
                      successor = None,
                    )
                  ),
              InstanceName.tryCreate("sv1NoLegacyLocal") ->
                c
                  .svApps(InstanceName.tryCreate(s"sv1"))
                  .focus(_.localSynchronizerNodes)
                  .modify(c =>
                    c.copy(
                      current = c.successor.getOrElse(
                        throw new IllegalStateException("successor must be set")
                      ),
                      successor = None,
                    )
                  ),
            )
        )
      )
      // use the standalone participant
      .addConfigTransforms((_, config) => {
        val bobValidatorConfig = config
          .validatorApps(InstanceName.tryCreate("bobValidator"))
        val updatedConfig = config.copy(
          validatorApps = config.validatorApps + (
            InstanceName.tryCreate("bobValidatorLocal") -> {
              bobValidatorConfig
            }
          ),
          walletAppClients = config.walletAppClients + (
            InstanceName.tryCreate("bobValidatorWalletLocal") -> {
              WalletAppClientConfig(
                adminApi = NetworkAppClientConfig(
                  s"http://${bobValidatorConfig.adminApi.clientConfig.endpointAsString}"
                ),
                ledgerApiUser = bobValidatorConfig.validatorWalletUsers.head,
              )
            }
          ),
        )
        ConfigTransforms.bumpSomeValidatorAppCantonPortsBy(21_900, Seq("bobValidatorLocal"))(
          updatedConfig
        )
      })
      .withSvBftSequencerConnectionDisabled()
      .withAmuletPrice(walletAmuletPrice)
      .withManualStart

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)

  private def bobValidatorLocal(implicit env: SpliceTestConsoleEnvironment) = {
    v("bobValidatorLocal")
  }

  "cancel a scheduled logical synchronizer upgrade" in { implicit env =>
    initDso(includeLocal = false)
    startAllSync(aliceValidatorBackend, splitwellValidatorBackend)

    val topologyFreezeTime = CantonTimestamp.now()
    // Use an upgrade time 1h in the future so that the upgrade nodes have enough time to be
    // started, initialized and to publish their sequencer successors before we cancel.
    val upgradeTime = CantonTimestamp.now().plus(Duration.ofHours(1))

    val newSynchronizerSerial = decentralizedSynchronizerPSId.serial + NonNegativeInt.one
    val successorPsid = decentralizedSynchronizerPSId.copy(
      serial = newSynchronizerSerial,
      protocolVersion = successorPv,
    )
    val svNodesDoingTheLsu = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

    withCantonSvNodes(
      (
        None,
        None,
        None,
        None,
      ),
      participants = false,
      enableBftSequencer = true,
      logSuffix = "cancel-global-synchronizer-upgrade",
    )(
      ProcessTestUtil.javaToolOptionsKey -> "-Xms8g -Xmx10g"
    ) {
      clue(s"Schedule logical synchronizer upgrade at $upgradeTime") {
        scheduleLsu(topologyFreezeTime, upgradeTime, newSynchronizerSerial.value.toLong)
      }

      clue("Wait for LSU announcement to be proposed") {
        waitForLsuAnnouncement()
      }

      clue("Upgrade nodes are started and initialized before cancelling") {
        svNodesDoingTheLsu.foreach { backend =>
          val upgradeSequencerClient = backend.sequencerClientFor(_.successor.value)
          val upgradeMediatorClient = backend.mediatorClientFor(_.successor.value)
          clue(s"check ${backend.name} initialized sequencer from synchronizer predecessor") {
            eventuallySucceeds(3.minutes) {
              upgradeSequencerClient.physical_synchronizer_id shouldBe successorPsid
            }
          }
          clue(s"check ${backend.name} initialized mediator") {
            eventuallySucceeds(3.minutes) {
              upgradeMediatorClient.health.initialized() shouldBe true
            }
          }
        }
      }

      clue("Sequencer successors were published for all upgrade nodes") {
        eventually() {
          val successors =
            sv1Backend.participantClientWithAdminToken.topology.lsu.sequencer_successors
              .list(store = Some(Synchronizer(decentralizedSynchronizerId)))
          successors should have size svNodesDoingTheLsu.size.toLong
          successors.map(_.item.successorPsid).toSet shouldBe Set(successorPsid.opaque)
        }
      }

      clue("Cancel LSU from all SVs") {
        svNodesDoingTheLsu.par.foreach { sv =>
          sv.cancelLogicalSynchronizerUpgrade()
        }
      }

      clue("LSU announcement has been removed from topology state") {
        eventually() {
          sv1Backend.participantClientWithAdminToken.topology.lsu.announcement
            .list(
              store = Some(Synchronizer(decentralizedSynchronizerId)),
              operation = Some(TopologyChangeOp.Replace),
            ) shouldBe empty
        }
      }

      clue("Removal transaction exists in topology history") {
        val removals = sv1Backend.participantClientWithAdminToken.topology.lsu.announcement
          .list(
            store = Some(Synchronizer(decentralizedSynchronizerId)),
            timeQuery = TimeQuery.Range(None, None),
            operation = Some(TopologyChangeOp.Remove),
          )
        removals should not be empty
      }

      clue("Trigger does not re-create the cancelled announcement") {
        // Wait long enough for the trigger to have run multiple times
        Threading.sleep(10_000)
        sv1Backend.participantClientWithAdminToken.topology.lsu.announcement
          .list(
            store = Some(Synchronizer(decentralizedSynchronizerId)),
            operation = Some(TopologyChangeOp.Replace),
          ) shouldBe empty
      }
    }

    checkSerial1Sequencers()
  }

  def checkSerial1Sequencers()(implicit env: SpliceTestConsoleEnvironment) = {
    clue("All sequencers are registered") {
      eventually(timeUntilSuccess = 1.minute) {
        inside(sv1ScanBackend.listDsoSequencers()) {
          case Seq(DomainSequencers(synchronizerId, sequencers)) =>
            synchronizerId shouldBe decentralizedSynchronizerId
            sequencers should have size 8
            forExactly(4, sequencers) {
              _.serial shouldBe 0
            }
            forExactly(4, sequencers) {
              _.serial shouldBe 1
            }
        }
      }
    }
  }

  "upgrade synchronizer to new physical synchronizer without downtime" in { implicit env =>
    // start the nodes early so that the sv app can remove the existing successor physical synchronizer state
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
    )(
      ProcessTestUtil.javaToolOptionsKey -> "-Xms8g -Xmx10g"
    ) {

      val allNodes = Seq[AppBackendReference](
        sv1ScanBackend,
        sv2ScanBackend,
        sv3ScanBackend,
        sv4ScanBackend,
        sv1Backend,
        sv1LocalBackend,
        sv1NoLegacyLocalBackend,
        sv2Backend,
        sv3Backend,
        sv4Backend,
        sv1ValidatorBackend,
        sv2ValidatorBackend,
        sv3ValidatorBackend,
        sv4ValidatorBackend,
      )
      // restart to clear any caches
      allNodes.par.foreach(_.stop())
      initDso(includeLocal = false)
      startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
      actAndCheck("Create some transaction history", sv1WalletClient.tap(1337))(
        "Wallet balance is updated",
        _ => {
          // buffer to account for domain fee payments
          assertInRange(
            sv1WalletClient.balance().unlockedQty,
            (walletUsdToAmulet(1000), walletUsdToAmulet(2000)),
          )
        },
      )

      // The serial 1 sequencers are still registered at this point as we only unregister them when the successors give us back a response
      // and they are still uninitialized here.
      checkSerial1Sequencers()

      def onboardUserAndTapAmulet(
          validatorBackend: ValidatorAppBackendReference,
          walletClient: WalletAppClientReference,
          tapAmount: BigDecimal = 50.0,
          expectedAmulets: Range = 50 to 50,
      ) = {
        val walletUserParty = onboardWalletUser(walletClient, validatorBackend)
        eventuallySucceeds() {
          walletClient.tap(tapAmount)
        }
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
        validatorBackend
          .getExternalPartyBalance(externalParty)
          .totalUnlockedCoin shouldBe "0.0000000000"
        // can still fail with no preapproval depending on what scan subset is used
        eventuallySucceeds() {
          walletClient.transferPreapprovalSend(externalParty, 40.0, UUID.randomUUID.toString)
        }
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
      val successorPsid = decentralizedSynchronizerPSId.copy(
        serial = newSynchronizerSerial,
        protocolVersion = successorPv,
      )
      // Upload after starting validator which connects to global
      // synchronizers as upload_dar_unless_exists vets on all
      // connected synchronizers.
      aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      val externalPartyOnboarding = clue("Create external party and transfer 40 amulet to it") {
        createExternalParty(aliceValidatorBackend, aliceValidatorWalletClient)
      }

      val bobValidatorWalletLocal = wc(
        "bobValidatorWalletLocal"
      )
      clue("Start bob validator local, onboard and tap before upgrade") {
        runBobValidatorWithStandaloneParticipant("before-upgrade")(
          onboardUserAndTapAmulet(
            bobValidatorLocal,
            bobValidatorWalletLocal,
          )
        )
      }

      val lateJoiningNode = sv4Nodes
      lateJoiningNode.par.foreach(_.stop())
      val topologyFreezeTime = CantonTimestamp.now()
      // We need to give enough time for the new Canton instance to startup
      // and finish sequencer initialization so we can then publish the sequencer announcement before the upgrade time.
      val upgradeTime = CantonTimestamp.now().plusSeconds(150)
      clue(s"Schedule logical synchronizer upgrade at $upgradeTime") {
        scheduleLsu(topologyFreezeTime, upgradeTime, newSynchronizerSerial.value.toLong)
      }
      val allBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
      val initialSvNodesDoingTheLsu = Seq(sv1Backend, sv2Backend, sv3Backend)
      clue(
        "Pause traffic transfer trigger on sv2 to simulate a participant that is connected to a non initialized sequencer past upgrade tiem"
      ) {
        sv2Backend.dsoAutomation
          .trigger[LsuTransferTrafficTrigger]
          .pause()
          .futureValue
      }

      clue(s"wait for lsu announcement") {
        waitForLsuAnnouncement()
      }

      clue(s"Scan API returns proper LSU info after LSU announcement") {
        val lsu = sv1ScanBackend.getLsu().value
        val actualTopologyFreezeTime = sv1ScanBackend.participantClient.topology.lsu.announcement
          .list(Some(Synchronizer(decentralizedSynchronizerId)))
          .head
          .context
          .validFrom
        lsu.topologyFreezeTime shouldBe CantonTimestamp.assertFromInstant(actualTopologyFreezeTime)
        lsu.upgradeTime shouldBe upgradeTime
        lsu.successorPhysicalSynchronizerId shouldBe successorPsid
      }
      val externalPartyHint = "external-party"
      val (keyPair, externalPartyId) =
        clue("Try to onboard a party which fails due to topology freeze being active") {
          val generatedKey: SigningPublicKey =
            aliceValidatorBackend.participantClient.keys.secret
              .generate_signing_key(
                UUID.randomUUID().toString,
                SigningKeyUsage.All,
                Some(SigningKeySpec.EcCurve25519),
              )
          val signingKeyPairByteString = aliceValidatorBackend.participantClient.keys.secret
            .download(generatedKey.fingerprint, ProtocolVersion.dev)

          // delete the key from the participant to ensure that it won't be actually used there for anything
          aliceValidatorBackend.participantClient.keys.secret.delete(generatedKey.fingerprint, true)

          val keyPair =
            CryptoKeyPair.fromTrustedByteString(signingKeyPairByteString).value

          val partyId = PartyId.tryCreate(externalPartyHint, generatedKey.fingerprint)

          assertThrowsAndLogsCommandFailures(
            submitTopologyAndOnboard(
              aliceValidatorBackend,
              externalPartyHint,
              keyPair,
              partyId,
            ),
            _.errorMessage should include("TOPOLOGY_LSU_TOPOLOGY_FREEZE"),
          )
          (keyPair, partyId)
        }

      clue("new nodes are initialized") {
        initialSvNodesDoingTheLsu.map { backend =>
          val upgradeSequencerClient = backend.sequencerClientFor(_.successor.value)
          val upgradeMediatorClient = backend.mediatorClientFor(_.successor.value)
          clue(s"check ${backend.name} initialized sequencer from synchronizer predecessor") {
            eventuallySucceeds(3.minutes) {
              upgradeSequencerClient.physical_synchronizer_id shouldBe successorPsid
              upgradeSequencerClient.synchronizer_parameters.static
                .get()
                .synchronizerLimits shouldBe SvSynchronizerNodeConfig.defaultSynchronizerLimits
            }
          }

          clue(s"check ${backend.name} initialized mediator") {
            eventuallySucceeds(2.minutes) {
              upgradeMediatorClient.health.initialized() shouldBe true
            }
          }
        }
      }

      clue(s"wait for upgrade time $upgradeTime") {
        Threading.sleep(Duration.between(Instant.now(), upgradeTime.toInstant).toMillis.abs)
      }

      clue("Restart sv2 and resume traffic transfer trigger") {
        sv2Backend.stop()
        sv2Backend.startSync()
        sv2Backend.dsoAutomation
          .trigger[LsuTransferTrafficTrigger]
          .resume()
      }

      def participantIsConnectedToNewSynchronizer(
          clientWithAdminToken: ParticipantClientReference,
          isSv4Connected: Boolean,
          svBackend: Option[SvAppBackendReference],
      ) = {
        val newSequencerUrls = {
          svBackend match {
            case Some(backend) =>
              Seq(
                backend.config.localSynchronizerNodes.successor.value.sequencer.externalPublicApiUrl
                  .stripPrefix("http://")
              )
            case None =>
              (if (isSv4Connected) allBackends else allBackends.filter(_.name != sv4Backend.name))
                .map { backend =>
                  backend.config.localSynchronizerNodes.successor.value.sequencer.externalPublicApiUrl
                    .stripPrefix("http://")
                }
          }
        }

        clientWithAdminToken.synchronizers
          .list_connected()
          .loneElement
          .physicalSynchronizerId shouldBe successorPsid
        val sequencerUrlSet = getSequencerUrlsConfiguredForTheSync(
          clientWithAdminToken,
          decentralizedSynchronizerAlias,
        )
        sequencerUrlSet should contain theSameElementsAs newSequencerUrls.toSet
      }

      clue("Validator connects to the new sequencers and syncs topology") {
        eventually(60.seconds) {
          val clientWithAdminToken = aliceValidatorBackend.participantClientWithAdminToken
          participantIsConnectedToNewSynchronizer(
            clientWithAdminToken,
            isSv4Connected = false,
            None,
          )
        }
      }

      initialSvNodesDoingTheLsu.par.map { backend =>
        clue(s"SV ${backend.name} connects to the new sequencers and syncs topology") {
          eventually(60.seconds) {
            participantIsConnectedToNewSynchronizer(
              backend.participantClientWithAdminToken,
              isSv4Connected = false,
              Some(backend),
            )
          }
        }
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
          )
        }
      }

      clue("Scan reports active physical synchronizer serial 1 after upgrade") {
        eventually() {
          Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend).foreach { scan =>
            scan.getActivePhysicalSynchronizerSerial() shouldBe newSynchronizerSerial
          }
        }
      }

      clue("LSU sequencers are registered") {
        eventually() {
          inside(sv1ScanBackend.listDsoSequencers()) {
            case Seq(DomainSequencers(synchronizerId, sequencers)) =>
              synchronizerId shouldBe decentralizedSynchronizerId
              sequencers should have size 8
              sequencers.groupBy(_.svName).foreach { case (sv, sequencers) =>
                clue(s"check sequencers for $sv") {
                  forExactly(1, sequencers) { sequencer =>
                    sequencer.serial shouldBe 0
                  }
                  if (sv != sv4Backend.config.onboarding.value.name)
                    forExactly(1, sequencers) { sequencer =>
                      sequencer.serial shouldBe newSynchronizerSerial.value.toLong
                    }
                  else {
                    // sv4 still reports the old serial until it upgrades
                    forExactly(1, sequencers) { sequencer =>
                      sequencer.serial shouldBe 1
                    }
                  }
                }
              }
          }
        }
      }

      clue("External party's balance has been preserved and it can transfer") {
        aliceValidatorBackend
          .getExternalPartyBalance(externalPartyOnboarding.party)
          .totalUnlockedCoin shouldBe "40.0000000000"

        actAndCheck(
          "external party transfer 10 cc",
          executeTransferViaTokenStandard(
            aliceValidatorBackend.participantClientWithAdminToken,
            externalPartyOnboarding.richPartyId,
            aliceValidatorBackend.getValidatorPartyId(),
            BigDecimal("10.0"),
            transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
          ),
        )(
          "external party's balance decreases by 10 cc",
          _ =>
            BigDecimal(
              aliceValidatorBackend
                .getExternalPartyBalance(externalPartyOnboarding.party)
                .totalUnlockedCoin
            ) should be(BigDecimal(30)),
        )
      }

      clue("Retrying external party allocation after LSU works") {
        submitTopologyAndOnboard(
          aliceValidatorBackend,
          externalPartyHint,
          keyPair,
          externalPartyId,
        )
      }

      // new sync nodes are started in process so to avoid log noise we stop everything before the test ends
      clue("Alice can purchase traffic") {
        val aliceParty = aliceValidatorBackend.getValidatorPartyId()
        val aliceParticipant = aliceValidatorBackend.participantClient.id.toProtoPrimitive
        val trackingId = java.util.UUID.randomUUID.toString
        val trafficPurchaseAmount = 1000000L
        val trafficStateBefore = getTrafficState(aliceValidatorBackend, activeSynchronizerId)
        clue("Alice creates traffic request") {
          createBuyTrafficRequest(
            aliceValidatorBackend,
            aliceParty,
            aliceParticipant,
            trafficPurchaseAmount,
            trackingId,
          )
        }
        clue("Traffic request is ingested into wallet tx log") {
          eventuallySucceeds() {
            aliceValidatorWalletClient.getTrafficRequestStatus(trackingId)
          }
        }
        clue("Wallet automation completes the request") {
          eventually() {
            inside(aliceValidatorWalletClient.getTrafficRequestStatus(trackingId)) {
              case definitions.GetBuyTrafficRequestStatusResponse.members
                    .BuyTrafficRequestCompletedResponse(
                      definitions.BuyTrafficRequestCompletedResponse(status, _)
                    ) =>
                status shouldBe BuyTrafficRequestStatus.Completed
            }
          }
        }
        val expectedTrafficPurchased =
          trafficStateBefore.extraTrafficPurchased + NonNegativeLong.tryCreate(
            trafficPurchaseAmount
          )
        clue("Sequencer updates traffic state") {
          eventually() {
            val trafficStateAfter = getTrafficState(aliceValidatorBackend, activeSynchronizerId)
            trafficStateAfter.extraTrafficPurchased shouldBe expectedTrafficPurchased
          }
        }
        clue("Scan updates traffic state") {
          eventually() {
            sv1ScanBackend
              .getMemberTrafficStatus(
                decentralizedSynchronizerId,
                aliceValidatorBackend.participantClient.id,
              )
              .actual
              .totalLimit shouldBe expectedTrafficPurchased.unwrap
          }
        }
      }

      clue("sv4 upgrades") {
        loggerFactory.suppress(
          SuppressionRule.forLogger[LsuTrigger] && SuppressionRule.Level(
            org.slf4j.event.Level.WARN
          )
        ) {
          lateJoiningNode.par.foreach(_.startSync())
          clue("sv4 connects to the new sync") {
            eventually(120.seconds) {
              participantIsConnectedToNewSynchronizer(
                sv4Backend.participantClientWithAdminToken,
                isSv4Connected = true,
                Some(sv4Backend),
              )
            }
          }
          clue("Validator also connects to the sv-4 sequencer") {
            eventually(60.seconds) {
              val clientWithAdminToken = aliceValidatorBackend.participantClientWithAdminToken
              participantIsConnectedToNewSynchronizer(
                clientWithAdminToken,
                isSv4Connected = true,
                None,
              )
            }
          }
        }
      }

      clue("sv and scan app can be restarted") {
        sv1Backend.stop()
        sv1ScanBackend.stop()
        sv1Backend.startSync()
        sv1ScanBackend.startSync()
      }

      // this also ensures that sv1 ingested verdicts after the restart
      val beforeBobActivityTimestamp = Instant.now()
      clue("bob validator local upgrades after upgrade and can tap") {
        runBobValidatorWithStandaloneParticipant("after-upgrade") {
          eventually(60.seconds) {
            bobValidatorLocal.participantClientWithAdminToken.synchronizers
              .list_connected()
              .loneElement
              .physicalSynchronizerId
              .serial shouldBe newSynchronizerSerial
          }
          bobValidatorWalletLocal.tap(20)
          checkWallet(
            bobValidatorLocal.getValidatorPartyId(),
            bobValidatorWalletLocal,
            Seq((70, 70)),
          )
          // TODO(DACH-NY/canton-network-internal#4426) move check before tap
          eventually(60.seconds) {
            participantIsConnectedToNewSynchronizer(
              bobValidatorLocal.participantClientWithAdminToken,
              isSv4Connected = true,
              None,
            )
          }
        }
      }

      clue("mediator verdicts stream works after upgrade") {
        Seq(sv1ScanBackend, sv2ScanBackend, sv3ScanBackend, sv4ScanBackend).foreach { scan =>
          clue(s"check ${scan.name} streamed post upgrade verdicts") {
            eventually() {
              scan.appState.eventStore.verdictStore
                .maxVerdictRecordTime(0)
                .futureValue
                .value
                .toInstant should be > beforeBobActivityTimestamp
            }
          }
        }
      }

      clue("Restart with additionalLegacy") {
        sv1Backend.stop()
        sv1LocalBackend.startSync()
        forExactly(1, sv1ScanBackend.listDsoSequencers().loneElement.sequencers) { s =>
          s.serial shouldBe 0
          s.svName shouldBe sv1LocalBackend.config.onboarding.value.name
        }
      }

      clue("Restart with no legacy") {
        sv1LocalBackend.stop()
        actAndCheck("restart with no legacy config", sv1NoLegacyLocalBackend.startSync())
        (
          "scan only lists sequencer for serial 2",
          () =>
            sv1ScanBackend
              .listDsoSequencers()
              .loneElement
              .sequencers
              .filter(c => c.svName == sv1LocalBackend.config.onboarding.value.name)
              .loneElement
              .serial shouldBe 2,
        )
      }

      clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
        // manually stop stuff as we destroy the new synchronizer as it runs in process
        val validators = Seq(aliceValidatorBackend, splitwellValidatorBackend)
        stopAllAsync(validators*).futureValue
        validators.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
        stopAllAsync(allNodes*).futureValue
        allBackends.par.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
      }
    }
  }

  private def runBobValidatorWithStandaloneParticipant(hint: String)(
      run: => Unit
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    withCanton(
      Seq(
        testResourcesPath / "standalone-participant-extra.conf",
        testResourcesPath / "standalone-participant-extra-no-auth.conf",
      ),
      Seq(),
      s"lsu-bob-validator-$hint",
      "EXTRA_PARTICIPANT_ADMIN_USER" -> bobValidatorLocal.config.ledgerApiUser,
      "EXTRA_PARTICIPANT_DB" -> s"participant_extra_$dbsSuffix",
      ProcessTestUtil.javaToolOptionsKey -> "-Xms3g -Xmx3g",
    ) {
      bobValidatorLocal.startSync()
      run
      bobValidatorLocal.stop()
    }
  }

  private def waitForLsuAnnouncement()(implicit env: SpliceTestConsoleEnvironment): Unit = {
    eventually(timeUntilSuccess = 5.minutes) {
      sv1Backend.participantClientWithAdminToken.topology.lsu.announcement
        .list(
          Some(Synchronizer(decentralizedSynchronizerId))
        ) should not be empty
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

  private def getSequencerUrlsConfiguredForTheSync(
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
