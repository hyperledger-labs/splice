package org.lfdecentralizedtrust.splice.integration.tests

import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.{HasExecutionContext, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong}
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Synchronizer
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.topology.store.TimeQuery
import com.digitalasset.canton.topology.transaction.TopologyChangeOp
import com.digitalasset.canton.version.ProtocolVersion
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllSvAppFoundDsoConfigs_
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.LogicalSynchronizerUpgradeSchedule
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
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry.Http.BuyTrafficRequestStatus
import org.scalatest.time.{Minutes, Span}
import org.scalatest.TryValues

import java.time.{Duration, Instant}
import java.util.UUID
import scala.collection.parallel.CollectionConverters.seqIsParallelizable
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.RichOptional

class LogicalSynchronizerUpgradeIntegrationTest
    extends IntegrationTest
    with ExternallySignedPartyTestUtil
    with ProcessTestUtil
    with SvTestUtil
    with WalletTestUtil
    with StandaloneCanton
    with HasExecutionContext
    with SynchronizerFeesTestUtil
    with TryValues {

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected lazy val resetRequiredTopologyState: Boolean = false

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
      .addConfigTransforms((_, config) => {
        ConfigTransforms
          .updateAllSvAppConfigs { (_, config) =>
            config.copy(
              localSynchronizerNodes = config.localSynchronizerNodes.copy(successor =
                config.localSynchronizerNodes.current.some
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
          })
          .andThen(ConfigTransforms.bumpCantonSyncSuccessorPortsBy(22_000))(config)
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .withAmuletPrice(walletAmuletPrice)
      .addConfigTransform((_, config) => {
        updateAllSvAppFoundDsoConfigs_(c => c.copy(zeroTransferFees = true))(config)
      })

  override def walletAmuletPrice: java.math.BigDecimal = SpliceUtil.damlDecimal(1.0)
  "cancel a scheduled logical synchronizer upgrade" in { implicit env =>
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

  "upgrade synchronizer to new physical synchronizer without downtime" in { implicit env =>
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
    val topologyFreezeTime = CantonTimestamp.now()
    // We need to give enough time for the new Canton instance to startup
    // and finish sequencer initialization so we can then publish the sequencer announcement before the upgrade time.
    val upgradeTime = CantonTimestamp.now().plusSeconds(120)
    clue("Schedule logical synchronizer upgrade") {
      scheduleLsu(topologyFreezeTime, upgradeTime, newSynchronizerSerial.value.toLong)
    }
    val allBackends = Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
    withCantonSvNodes(
      (
        None,
        None,
        None,
        None,
      ),
      participants = false,
      logSuffix = "global-domain-migration",
    )() {

      clue(s"wait for lsu announcement") {
        waitForLsuAnnouncement()
      }

      val topologyTransactionsOnTheSync = sv1Backend.sequencerClient.topology.transactions
        .list(store = Synchronizer(decentralizedSynchronizerId))
        .result
        .size

      clue("new nodes are initialized") {
        allBackends.map { backend =>
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

      clue(s"wait for upgrade time $upgradeTime") {
        Threading.sleep(Duration.between(Instant.now(), upgradeTime.toInstant).toMillis.abs)
      }

      val newSequencerUrls = allBackends.map { backend =>
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
        allBackends.par.map { backend =>
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
                    sequencer.serial.value shouldBe newSynchronizerSerial
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

      stopAllAsync(aliceValidatorBackend).futureValue
      aliceValidatorBackend.participantClientWithAdminToken.synchronizers.disconnect_all()
      stopAllAsync(allNodes*).futureValue
      allBackends.foreach(_.participantClientWithAdminToken.synchronizers.disconnect_all())
    }
  }

  private def scheduleLsu(
      topologyFreezeTime: CantonTimestamp,
      upgradeTime: CantonTimestamp,
      serial: Long,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    scheduleLogicalSynchronizerUpgrade(
      sv1Backend,
      Seq(sv2Backend, sv3Backend),
      new LogicalSynchronizerUpgradeSchedule(
        topologyFreezeTime.toInstant,
        upgradeTime.toInstant,
        serial,
        ProtocolVersion.v34.toString,
      ),
    )
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
