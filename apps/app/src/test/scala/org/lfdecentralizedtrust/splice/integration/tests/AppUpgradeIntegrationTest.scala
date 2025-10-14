package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.integration.tests.AppUpgradeIntegrationTest.*
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.util.{
  PostgresAroundEach,
  ProcessTestUtil,
  SplitwellTestUtil,
  WalletTestUtil,
}

import java.nio.file.{Path, Paths}
import better.files.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.{BuildInfo, DarResources}
import org.lfdecentralizedtrust.splice.wallet.store.BalanceChangeTxLogEntry
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId.Authorized
import com.digitalasset.canton.topology.store.TimeQuery.HeadState
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig

import scala.jdk.CollectionConverters.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Using
import scala.util.Using.Releasable
import scala.concurrent.duration.*

class AppUpgradeIntegrationTest
    extends IntegrationTest
    with PostgresAroundEach
    with ProcessTestUtil
    with SplitwellTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override protected def runEventHistorySanityCheck: Boolean = false

  private val splitwellDarPathV1 =
    s"daml/splitwell/.daml/dist/splitwell-base.dar"
  private val splitwellDarPathCurrent =
    "daml/splitwell/src/main/resources/dar/splitwell-current.dar"

  // We need to split DBs as we upgrade the nodes independently. Otherwise, the first node runs the DB migrations
  // and the nodes that have not yet been upgraded will fail if the DB schema is not backwards compatible.
  override def usesDbs: IndexedSeq[String] = IndexedSeq(
    "sv1node",
    "sv2node",
    "sv3node",
    "bobsplitwellvalidators",
  )

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "include/upgrade-databases.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      .withManualStart
      // TODO(#979) Consider removing this once domain config updates are less disruptive, particularly
      // to the tests after SVs 2 and 3 have been upgraded
      .withSequencerConnectionsFromScanDisabled()
      .addConfigTransforms((_, config) =>
        // Makes the test a bit faster and easier to debug. See #11488
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllValidatorAppConfigs_(c =>
          // Reduce the cache TTL so package upgrades are picked up quickly.
          c.copy(scanClient =
            c.scanClient.setAmuletRulesCacheTimeToLive(NonNegativeFiniteDuration.ofSeconds(1))
          )
        )(conf)
      )
      .addConfigTransform((_, config) => {
        config
          .focus(_.validatorApps)
          .modify(_.updatedWith(InstanceName.tryCreate("splitwellValidatorApp"))(_.map {
            splitwellValidator =>
              splitwellValidator
                .focus(_.appInstances)
                .modify(_.map {
                  case (n @ "splitwell", appInstance) =>
                    n -> appInstance
                      .focus(_.dars)
                      .modify(_.map { darPath =>
                        Paths.get(
                          darPath.toString.replace(
                            "current",
                            DarResources.splitwell_current.metadata.version.toString(),
                          )
                        )
                      })
                  case x => x
                })
          }))
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(
          // required for reward triggers and amulet merging to run
          NonNegativeFiniteDuration.ofSeconds(5)
        )(config)
      )

  "A set of Splice apps" should {
    "be upgradeable" in { implicit env =>
      {

        val testId = env.environment.config.name.value

        Using.resource(
          AppUpgradeIntegrationTest.MultiProcessResource("forUpgrade", loggerFactory)
        )(spliceProcs => {
          // Do not start the old sv4 backend nor alice's validators, they will join only after upgrade
          Seq(
            ("sv1-node", Seq("sv-apps.sv1", "scan-apps.sv1Scan", "validator-apps.sv1Validator")),
            ("sv2-node", Seq("sv-apps.sv2", "scan-apps.sv2Scan", "validator-apps.sv2Validator")),
            ("sv3-node", Seq("sv-apps.sv3", "scan-apps.sv3Scan", "validator-apps.sv3Validator")),
            (
              "bobSplitwellValidators",
              Seq(
                "splitwell-apps.providerSplitwellBackend",
                "validator-apps.bobValidator",
                "validator-apps.splitwellValidator",
              ),
            ),
          ).foreach { case (conf, apps) =>
            val version = getBaseVersion()
            val bundledConfig = getConfigFileFromBundle(version, conf)
            val suffix = apps
              .map(app =>
                s"canton.$app.storage.config.properties.databaseName = ${conf.replace("-", "").toLowerCase}"
              )
              .mkString("\n")
            val inputConfig = generateConfig(bundledConfig, version, testId, Some(suffix))
            spliceProcs.startBundledSplice(conf, inputConfig)
          }

          eventually(5.minute) {
            Seq("sv1", "sv2", "sv3").foreach(sv => {
              sv_client(s"${sv}Client").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} SV app initialized"
              wc(s"${sv}Wallet").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} wallet initialized"
            })
            forAll(Seq("bob", "splitwell")) { validator =>
              vc(s"${validator}ValidatorClient").httpHealth.successOption
                .exists(_.active) should be(true)
            }
          }

          bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPathV1)

          val sv2Wallet = wc("sv2Wallet")
          val sv1Client = sv_client("sv1Client")

          val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)

          clue("Tapping some amulet in the network before any upgrades") {
            bobWalletClient.tap(10)
            bobValidatorWalletClient.tap(1_000_001)
            bobValidatorWalletClient.balance().unlockedQty should be > BigDecimal(1_000_000)
            sv2Wallet.tap(1_000_002)
            sv2Wallet.balance().unlockedQty should be > BigDecimal(1_000_000)
          }

          val bobTxsBeforeUpgrade =
            clue("Check that bob validator can see the tap in the wallet tx history") {
              val txs = withoutDevNetTopups(bobValidatorWalletClient.listTransactions(None, 10))
              inside(txs(0)) { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.amount shouldBe walletUsdToAmulet(BigDecimal(1_000_001))
              }
              txs
            }

          clue("Upgrading bob's and splitwell validator") {
            spliceProcs.stopBundledSplice("bobSplitwellValidators")
            bobValidatorBackend.startSync()
            splitwellValidatorBackend.startSync()
            splitwellBackend.startSync()
          }

          clue("Check that bob still sees the same wallet tx history") {
            val txsAfter =
              withoutDevNetTopups(bobValidatorWalletClient.listTransactions(None, 10))
            txsAfter should contain allElementsOf bobTxsBeforeUpgrade
          }

          clue("Validating that the balance is visible in the upgraded validator") {
            bobValidatorWalletClient.balance().unlockedQty should be > BigDecimal(1_000_000)
          }

          clue("Upgrading sv-2 & sv-3") {
            spliceProcs.stopBundledSplice("sv2-node")
            startAllSync(sv2Backend, sv2ScanBackend, sv2ValidatorBackend)
            spliceProcs.stopBundledSplice("sv3-node")
            startAllSync(sv3Backend, sv3ScanBackend, sv3ValidatorBackend)
          }

          clue("Testing some more transactions after 2 SVs upgraded") {
            sv2Wallet.tap(1_000_003)
            sv2Wallet.balance().unlockedQty should be > BigDecimal(2_000_000)
            // p2p transfer between an upgraded validator (alice's) and a non-upgraded (sv-1's)
            p2pTransfer(
              bobValidatorWalletClient,
              sv1WalletClient,
              sv1Client.getDsoInfo().svParty,
              500_001,
            )
            sv1WalletClient.balance().unlockedQty should be > BigDecimal(490_000)
          }

          clue("Upgrading also sv1") {
            spliceProcs.stopBundledSplice("sv1-node")
            startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend)
          }

          // SV1 does not vet DAR before the vote goes through
          val sv1Packages = vettedPackages(sv1Backend.participantClientWithAdminToken)
          forAll(sv1Packages) { pkg =>
            pkg.packageId should not be DarResources.amulet.bootstrap.packageId
          }

          val amuletRules = sv2ScanBackend.getAmuletRules()
          val amuletConfig = amuletRules.payload.configSchedule.initialValue
          // Ideally we'd like the config to take effect immediately. However, we
          // can only schedule configs in the future and this is enforced at the Daml level.
          // So we pick a date that is far enough in the future that we can complete the voting process
          // before it is reached but close enough that we don't need to wait for long.
          // 12 seconds seems to work well empirically.
          val scheduledTime = Instant.now().plus(12, ChronoUnit.SECONDS)
          val newAmuletConfig = new splice.amuletconfig.AmuletConfig(
            amuletConfig.transferConfig,
            amuletConfig.issuanceCurve,
            amuletConfig.decentralizedSynchronizer,
            amuletConfig.tickDuration,
            new splice.amuletconfig.PackageConfig(
              DarResources.amulet.bootstrap.metadata.version.toString(),
              DarResources.amuletNameService.bootstrap.metadata.version.toString(),
              DarResources.dsoGovernance.bootstrap.metadata.version.toString(),
              DarResources.validatorLifecycle.bootstrap.metadata.version.toString(),
              DarResources.wallet.bootstrap.metadata.version.toString(),
              DarResources.walletPayments.bootstrap.metadata.version.toString(),
            ),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
          )
          val upgradeAction = new ARC_AmuletRules(
            new CRARC_SetConfig(
              new AmuletRules_SetConfig(
                newAmuletConfig,
                amuletConfig,
              )
            )
          )

          actAndCheck(timeUntilSuccess = 40.seconds)(
            "Voting on a AmuletRules config change for upgraded packages", {
              val (_, voteRequest) = actAndCheck(
                "Creating vote request",
                eventuallySucceeds() {
                  sv1Backend.createVoteRequest(
                    sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                    upgradeAction,
                    "url",
                    "description",
                    sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
                    None,
                  )
                },
              )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

              clue(s"sv2 accepts, resulting in a super-majority approval") {
                eventuallySucceeds() {
                  sv2Backend.castVote(
                    voteRequest.contractId,
                    isAccepted = true,
                    "url",
                    "description",
                  )
                }
              }
            },
          )(
            "observing AmuletRules with upgraded config",
            _ => {
              val newAmuletRules = sv1Client.getDsoInfo().amuletRules
              val config =
                newAmuletRules.payload.configSchedule.initialValue
              config.packageConfig.amulet should endWith(".123")
            },
          )

          // Ensure that the code below really uses the new version. Locally things can be sufficiently
          // fast that you otherwise still end up using the old version.
          env.environment.clock
            .scheduleAt(
              _ => (),
              CantonTimestamp.assertFromInstant(scheduledTime.plus(500, ChronoUnit.MILLIS)),
            )
            .unwrap
            .futureValue

          // Vote on a dummy change on amulet rules to ensure it is archived and recreated
          // which indicates the new choice is being used.
          val dummyUpgradeAction = new ARC_AmuletRules(
            new CRARC_SetConfig(
              new AmuletRules_SetConfig(
                newAmuletConfig,
                amuletConfig,
              )
            )
          )

          actAndCheck(
            "Voting on a AmuletRules config change for upgraded packages", {
              val (_, voteRequest) = actAndCheck(
                "Creating vote request",
                eventuallySucceeds() {
                  sv1Backend.createVoteRequest(
                    sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
                    dummyUpgradeAction,
                    "url",
                    "description",
                    sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
                    None,
                  )
                },
              )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
              clue(s"sv2 accepts, resulting in a super-majority approval") {
                eventuallySucceeds() {
                  sv2Backend.castVote(
                    voteRequest.contractId,
                    isAccepted = true,
                    "url",
                    "description",
                  )
                }
              }
            },
          )(
            "observing AmuletRules with new package id",
            _ => {
              val newAmuletRules = sv1Backend.getDsoInfo().amuletRules
              newAmuletRules.contract.identifier.getPackageId shouldBe DarResources.amulet_current.packageId
            },
          )

          val sv1PackagesAfterUpgrade =
            vettedPackages(sv1Backend.participantClientWithAdminToken)
          forExactly(1, sv1PackagesAfterUpgrade) { pkg =>
            withClue(s"Package ${pkg.packageId}") {
              pkg.packageId shouldBe DarResources.amulet.bootstrap.packageId
            }
          }

          actAndCheck(
            "Bob taps after upgrade",
            eventuallySucceeds() {
              bobWalletClient.tap(20)
            },
          )(
            "Old and new amulet get merged together into a new amulet",
            _ => {
              val amulet = bobWalletClient.list().amulets.loneElement.contract
              amulet.contract.identifier.getPackageId shouldBe DarResources.amulet_current.packageId
              BigDecimal(amulet.payload.amount.initialAmount) should beWithin(
                walletUsdToAmulet(30 - smallAmount),
                walletUsdToAmulet(30),
              )
            },
          )

          // Alice can join after the upgrade
          aliceValidatorBackend.startSync()
          val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          // This is just to invalidate the amulet rules cache on Alice’s side. In a real upgrade, the upgrade will be announced days or weeks in advance
          // while cache expiration is a few minutes so this is a non-issue.
          clue("Alice taps after upgrade") {
            eventuallySucceeds() {
              aliceWalletClient.tap(5)
            }
          }

          actAndCheck(
            "Bob makes p2p transfer after upgrade",
            eventuallySucceeds() {
              p2pTransfer(bobWalletClient, aliceWalletClient, alice, 4.0)
            },
          )(
            "old and new taps and transfers appear in scan tx log",
            _ => {
              val txs = sv1ScanBackend.listActivity(pageEndEventId = None, pageSize = 50)
              // old tap
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.amuletOwner shouldBe bob.toProtoPrimitive
                BigDecimal(tf.amuletAmount) shouldBe walletUsdToAmulet(10)
              }
              // new taps
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.amuletOwner shouldBe bob.toProtoPrimitive
                BigDecimal(tf.amuletAmount) shouldBe walletUsdToAmulet(20)
              }
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.amuletOwner shouldBe alice.toProtoPrimitive
                BigDecimal(tf.amuletAmount) shouldBe walletUsdToAmulet(5)
              }
              // new transfer
              forExactly(1, txs) { tx =>
                val tf = tx.transfer.value
                tf.sender.party shouldBe bob.toProtoPrimitive
                tf.receivers.loneElement.party shouldBe alice.toProtoPrimitive
                BigDecimal(tf.receivers.loneElement.amount) shouldBe 4.0
              }
            },
          )

          // SV4 can join after the upgrade.
          clue("SV4 can join after upgrade") {
            startAllSync(sv4Backend, sv4ScanBackend, sv4ValidatorBackend)
          }

          clue("Splitwell works") {

            // There is no auto-vetting for splitwell yet so we upload the DARs manually.
            bobValidatorBackend.participantClient.upload_dar_unless_exists(
              splitwellDarPathCurrent
            )
            aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPathV1)
            aliceValidatorBackend.participantClient.upload_dar_unless_exists(
              splitwellDarPathCurrent
            )
            splitwellValidatorBackend.participantClient.upload_dar_unless_exists(
              splitwellDarPathCurrent
            )

            // Note that this test atm only covers that splitwell works with upgraded wallet payment APIs.
            // It does not cover upgrading splitwell itself to use new features beyond that.
            // The only important step for this is the AcceptedAppPaymentsTrigger used by the splitwell
            // provider which needs to use the new splitwell version. Other steps can still use
            // the old splitwell version and contract up/downgrading takes care of any issues.
            val group = "group"
            createSplitwellInstalls(aliceSplitwellClient, alice)
            createSplitwellInstalls(bobSplitwellClient, bob)
            actAndCheck("Alice creates group", aliceSplitwellClient.requestGroup(group))(
              "Alice sees group",
              _ => aliceSplitwellClient.listGroups() should have size 1,
            )
            val (_, invite) =
              actAndCheck(
                "Alice creates group invite",
                aliceSplitwellClient.createGroupInvite(group),
              )(
                "Alice sees the group invite",
                _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
              )
            val (_, acceptedInvite) =
              actAndCheck("bob asks to join 'group1'", bobSplitwellClient.acceptInvite(invite))(
                "Alice sees the accepted invite",
                _ => aliceSplitwellClient.listAcceptedGroupInvites(group).loneElement,
              )
            actAndCheck(
              "Alice adds bob to group",
              aliceSplitwellClient.joinGroup(acceptedInvite.contractId),
            )(
              "group is updated",
              _ =>
                aliceSplitwellClient
                  .listGroups()
                  .loneElement
                  .contract
                  .payload
                  .members
                  .asScala shouldBe Seq(bob.toProtoPrimitive),
            )

            val key = HttpSplitwellAppClient.GroupKey(
              group,
              alice,
            )

            val (_, paymentRequest) = actAndCheck(
              "Alice creates payment request",
              aliceSplitwellClient.initiateTransfer(
                key,
                Seq(
                  new walletCodegen.ReceiverAmuletAmount(
                    bob.toProtoPrimitive,
                    BigDecimal(2.0).bigDecimal,
                  )
                ),
              ),
            )(
              "Alice sees payment request",
              _ => aliceWalletClient.listAppPaymentRequests().loneElement,
            )
            actAndCheck(
              "Alice accepts payment request",
              aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
            )(
              "Alice sees balance update",
              _ => aliceSplitwellClient.listBalanceUpdates(key) should have size 1,
            )
          }
        })
      }
    }
  }

  private def vettedPackages(participant: ParticipantClientReference) = {
    participant.topology.vetted_packages
      .list(
        filterParticipant = participant.id.filterString,
        timeQuery = HeadState,
        store = Some(Authorized),
      )
      .flatMap(_.item.packages)
      .filter(_.validFrom.forall(_.isBefore(CantonTimestamp.now())))
  }
}

object AppUpgradeIntegrationTest {

  final case class MultiProcessResource(logSuffix: String, loggerFactory: NamedLoggerFactory)
      extends NamedLogging {

    val processes = scala.collection.mutable.Map[String, Process]()

    def startBundledSplice(name: String, config: Path): Unit = {
      val version = getBaseVersion()
      val process = ProcessTestUtil.startProcess(
        Seq(
          getBundledSplice(version).toString,
          "daemon",
          "--log-level-canton=DEBUG",
          "--log-level-stdout=OFF",
          "--log-encoder",
          "json",
          "--log-file-name",
          s"log/splice-node-$logSuffix-$name.clog",
          "-c",
          config.toString,
        ),
        Seq(),
      )
      processes += (name -> process.process)
    }

    def stopBundledSplice(name: String) = {
      processes
        .get(name)
        .foreach(p => {
          p.destroy()
          p.waitFor()
        })
      processes -= name
    }

    def destroyAllAndWait(): Unit = {
      processes.foreach(_._2.destroy())
      processes.foreach(_._2.waitFor())
    }

    def getBundledSplice(version: String) = {
      val dir = getDir(bundleDir(version))
      val bundledSplice = dir.resolve("bin/splice-node")
      if (!bundledSplice.toFile.exists()) {
        throw new RuntimeException(
          s"Bundled Splice artifacts for version ${version} not found, did you run build-tools/prep-app-upgrade-test ?"
        )
      }
      bundledSplice
    }
  }

  object MultiProcessResource {
    implicit val releasable: Releasable[MultiProcessResource] =
      (resource: MultiProcessResource) => resource.destroyAllAndWait()
  }

  def getDir(dir: Path) = {
    if (!dir.toFile.exists()) {
      dir.toFile.mkdirs()
    }
    dir
  }

  def generatedConfigDir(version: String): Path = {
    getDir(Paths.get(s"apps/app/src/test/resources/generated/${version}"))
  }

  def bundleDir(version: String): Path = {
    getDir(
      Paths
        .get("apps/app/src/test/resources/bundles")
        .resolve(version)
        .resolve("splice-node")
    )
  }

  def getConfigFileFromBundle(version: String, node: String): Path = {
    val dir = getDir(
      bundleDir(version).resolve("testResources/include/nodes")
    )
    val conf = dir.resolve(s"${node}.conf")
    if (!conf.toFile.exists()) {
      throw new RuntimeException(
        s"Bundled config file for node ${node} in version ${version} not found, did you run build-tools/prep-app-upgrade-test ?"
      )
    }
    conf
  }

  def getBaseVersion() = {
    // For now, we get the base version from BuildInfo, which is auto-generated by build.sbt
    BuildInfo.compatibleVersion
  }

  def generateConfig(
      sourceConfig: Path,
      version: String,
      testId: String,
      suffix: Option[String],
  ): Path = {
    val bundle = bundleDir(version)
    val classpath = bundle.resolve("lib/splice-node.jar")
    val transformConfig = bundle.resolve("testResources/transform-config.sc")
    val generatedPath = generatedConfigDir(version).resolve(sourceConfig.getFileName)
    val generated = File(generatedPath)

    val cmd =
      s"scala -classpath $classpath $transformConfig integrationTestDefaults $sourceConfig $generated $testId"

    import sys.process.*
    val result = Process(
      Seq("bash", "-c", cmd),
      None,
      // TODO(DACH-NY/canton-network-node#10595): consider reading these from config files:
      "SV1_URL" -> "http://127.0.0.1:5114",
      "SV1_SCAN_URL" -> "http://127.0.0.1:5012",
      "SV2_SCAN_URL" -> "http://127.0.0.1:5112",
      "SV3_SCAN_URL" -> "http://127.0.0.1:5212",
      "SV4_SCAN_URL" -> "http://127.0.0.1:5312",
    ).!
    if (result != 0) {
      throw new RuntimeException(s"Command $cmd returned: $result")
    }
    suffix.foreach(s => File(generatedPath).appendLine().append(s))
    generatedPath
  }
}
