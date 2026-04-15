package org.lfdecentralizedtrust.splice.integration.tests

import better.files.File.apply
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong}
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, NetworkAppClientConfig}
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
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
import org.lfdecentralizedtrust.splice.sv.lsu.LogicalSynchronizerUpgradeTrigger
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

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_24
class LogicalSynchronizerUpgradeIntegrationTest
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

  // We manually force a snapshot on sv1 in the test. The other SVs
  // won't have a snapshot at that time so the assertions in the
  // update history sanity plugin wil fail.
  override lazy val skipAcsSnapshotChecks = true

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    DomainMigrationUtil.migrationDumpDir.delete()
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .addConfigTransforms((_, config) => {
        ConfigTransforms
          .updateAllSvAppConfigs { (name, config) =>
            config.copy(
              localSynchronizerNodes = config.localSynchronizerNodes
                .copy(successor = config.localSynchronizerNodes.current.some),
              domainMigrationDumpPath = Some(
                (DomainMigrationUtil.migrationTestDumpDir(name) / "domain_migration_dump.json").path
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
              synchronizerNodes = config.synchronizerNodes.copy(
                successor = Some(config.synchronizerNodes.current)
              ),
              parameters = config.parameters.copy(
                spliceCachingConfigs = config.parameters.spliceCachingConfigs.copy(
                  physicalSynchronizerExpiration = NonNegativeFiniteDuration.ofSeconds(1)
                )
              ),
            )
          })(config)
      })
      .withBftSequencersSuccessor
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms
          .bumpCantonSyncSuccessorPortsBy(22_000)(config)
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
    initDso()
    startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
    val topologyFreezeTime = CantonTimestamp.now()
    val upgradeTime = CantonTimestamp.now().plusSeconds(120)

    clue("Schedule logical synchronizer upgrade") {
      scheduleLsu(topologyFreezeTime, upgradeTime, 1L)
    }

    clue("Wait for LSU announcement to be proposed") {
      waitForLsuAnnouncement()
    }

    clue("Cancel LSU from all SVs") {
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).par.foreach { sv =>
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

  "upgrade synchronizer to new physical synchronizer without downtime" ignore { implicit env =>
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

      clue(s"wait for lsu announcement") {
        waitForLsuAnnouncement()
      }

      clue("new nodes are initialized") {
        initialSvNodesDoingTheLsu.map { backend =>
          val upgradeSequencerClient = backend.sequencerClientFor(_.successor.value)
          val upgradeMediatorClient = backend.mediatorClientFor(_.successor.value)
          clue(s"check ${backend.name} initialized sequencer from synchronizer predecessor") {
            eventuallySucceeds(2.minutes) {
              upgradeSequencerClient.physical_synchronizer_id shouldBe successorPsid
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
              sequencers should have size 11
              sequencers.groupBy(_.svName).foreach { case (sv, sequencers) =>
                clue(s"check sequencers for $sv") {
                  forExactly(1, sequencers) { sequencer =>
                    sequencer.serial.value shouldBe 0
                    sequencer.migrationId shouldBe -1
                  }
                  if (sv != sv4Backend.config.onboarding.value.name)
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
        actAndCheck(timeUntilSuccess = 1.minute)(
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
          SuppressionRule.forLogger[LogicalSynchronizerUpgradeTrigger] && SuppressionRule.Level(
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

  private def countTapsFromScan(scan: ScanAppBackendReference, tapAmount: BigDecimal) = {
    listTransactionsFromScan(scan).count(
      _.tap.map(a => BigDecimal(a.amuletAmount)).contains(tapAmount)
    )
  }

  private def listTransactionsFromScan(scan: ScanAppBackendReference) = {
    scan.listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, 100)
  }

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
