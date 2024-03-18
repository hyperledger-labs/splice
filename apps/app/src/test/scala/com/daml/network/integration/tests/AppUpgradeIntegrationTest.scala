package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinrules.CoinRules_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_AddFutureCoinConfigSchedule
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import com.daml.network.util.{CNNodeUtil, ProcessTestUtil, SplitwellTestUtil, WalletTestUtil}

import java.nio.file.{Path, Paths}
import better.files.*
import com.daml.network.environment.{BuildInfo, DarResources}
import com.daml.network.integration.tests.AppUpgradeIntegrationTest.generatedConfigDir
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.jdk.CollectionConverters.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.Using
import scala.util.Using.Releasable
import scala.concurrent.duration.*

class AppUpgradeIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with SplitwellTestUtil
    with WalletTestUtil {

  private val splitwellDarPathV1 = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
  private val splitwellDarPathCurrent =
    "daml/splitwell/src/main/resources/dar/splitwell-current.dar"

  // TODO(#10595): consider reading these from config files:
  System.setProperty("SV1_URL", "http://127.0.0.1:5114")
  System.setProperty("SV1_SCAN_URL", "http://127.0.0.1:5012")
  System.setProperty("SV2_SCAN_URL", "http://127.0.0.1:5112")

  private val confs = transformAndSaveConfigs()

  private def transformAndSaveConfigs() = {

    Seq(
      "sv1-node",
      "sv2-node",
      "sv3-node",
      "sv4-node",
      "aliceValidator",
      "bobSplitwellValidators",
    ).map(configFile => {
      val config = CNNodeConfig.parseAndLoadOrThrow(
        Seq(Paths.get(s"apps/app/src/test/resources/include/nodes/${configFile}.conf").toFile)
      )
      val configOut =
        (CNNodeConfigTransforms.defaults()
        // TODO (#10859) remove setCoinPrice and fix test failures
          :+ CNNodeConfigTransforms.setCoinPrice(walletCoinPrice))
          .foldLeft(config)((c, t) => t(c))
      val outFile = generatedConfigDir().resolve(s"${configFile}.conf")
      CNNodeConfig.writeToFile(configOut, outFile, confidential = false)
      configFile -> outFile
    })
  }

  override def environmentDefinition: CNNodeEnvironmentDefinition = {

    val files = confs.map(c => File(c._2))
    val config = CNNodeConfig.parseAndLoadOrThrow(files.map(_.toJava))
    CNNodeEnvironmentDefinition(
      baseConfig = config,
      context = this.getClass.getSimpleName,
      // skip the default transforms, as we already applied them in the saved config files
      configTransformsWithContext = (_: String) => Seq(),
    ).withManualStart
      .withAllocatedUsers()
      // TODO (#10859) remove and fix test failures
      .withCoinPrice(walletCoinPrice)
  }

  // TODO (#10859) remove and fix test failures
  override def walletCoinPrice = CNNodeUtil.damlDecimal(1.0)

  "A set of CN apps" should {
    "be upgradeable" in { implicit env =>
      Using.resource(AppUpgradeIntegrationTest.MultiCnProcessResource("forUpgrade", loggerFactory))(
        cnProcs => {
          confs
            .filter({ case (name, _) =>
              // Do not start the old sv4 backend nor bob's and splitwell validators, they will join only after upgrade
              name != "sv4-node" && name != "bobSplitwellValidators"
            })
            .foreach(conf => cnProcs.startBundledCN(conf._1, conf._2))

          eventually(5.minute) {
            Seq("sv1", "sv2", "sv3").foreach(sv => {
              svcl(s"${sv}Client").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} SV app initialized"
              wc(s"${sv}Wallet").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} wallet initialized"
            })
            Seq("alice").foreach(validator =>
              vc(s"${validator}ValidatorClient").httpHealth.successOption
                .exists(_.active) should be(true)
            )
          }

          aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPathV1)

          val sv2Wallet = wc("sv2Wallet")
          val sv1Client = svcl("sv1Client")

          val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

          clue("Tapping some coin in the network before any upgrades") {
            aliceWalletClient.tap(10)
            aliceValidatorWalletClient.tap(1_000_001)
            aliceValidatorWalletClient.balance().unlockedQty should be > BigDecimal(1_000_000)
            sv2Wallet.tap(1_000_002)
            sv2Wallet.balance().unlockedQty should be > BigDecimal(1_000_000)
          }

          clue("Upgrading alice's validator") {
            cnProcs.stopBundledCN("aliceValidator")
            aliceValidatorBackend.startSync()
          }

          clue("Validating that the balance is visible in the upgraded validator") {
            aliceValidatorWalletClient.balance().unlockedQty should be > BigDecimal(1_000_000)
          }

          clue("Upgrading sv-2 & sv-3") {
            cnProcs.stopBundledCN("sv2-node")
            startAllSync(sv2Backend, sv2ScanBackend, sv2ValidatorBackend)
            cnProcs.stopBundledCN("sv3-node")
            // No scan for sv3
            startAllSync(sv3Backend, sv3ValidatorBackend)
          }

          clue("Testing some more transactions after 2 SVs upgraded") {
            sv2Wallet.tap(1_000_003)
            sv2Wallet.balance().unlockedQty should be > BigDecimal(2_000_000)
            // p2p transfer between an upgraded validator (alice's) and a non-upgraded (sv-1's)
            p2pTransfer(
              aliceValidatorWalletClient,
              sv1WalletClient,
              sv1Client.getSvcInfo().svParty,
              500_001,
            )
            sv1WalletClient.balance().unlockedQty should be > BigDecimal(490_000)
          }

          clue("Upgrading also sv1") {
            cnProcs.stopBundledCN("sv1-node")
            startAllSync(sv1Backend, sv1ValidatorBackend, sv1ScanBackend)
          }

          val coinRules = sv2ScanBackend.getCoinRules()
          val coinConfig = coinRules.payload.configSchedule.initialValue
          // Ideally we'd like the config to take effect immediately. However, we
          // can only schedule configs in the future and this is enforced at the Daml level.
          // So we pick a date that is far enough in the future that we can complete the voting process
          // before it is reached but close enough that we don't need to wait for long.
          // 12 seconds seems to work well empirically.
          val scheduledTime = Instant.now().plus(12, ChronoUnit.SECONDS)
          val newCoinConfig = new cc.coinconfig.CoinConfig(
            coinConfig.transferConfig,
            coinConfig.issuanceCurve,
            coinConfig.globalDomain,
            coinConfig.tickDuration,
            new cc.coinconfig.PackageConfig(
              "0.1.1",
              "0.1.1",
              "0.1.1",
              "0.1.0",
              "0.1.1",
              "0.1.1",
            ),
          )
          val upgradeAction = new ARC_CoinRules(
            new CRARC_AddFutureCoinConfigSchedule(
              new CoinRules_AddFutureCoinConfigSchedule(
                new com.daml.network.codegen.java.da.types.Tuple2(
                  scheduledTime,
                  newCoinConfig,
                )
              )
            )
          )

          actAndCheck(
            "Voting on a CoinRules config change for upgraded packages", {
              val (_, voteRequest) = actAndCheck(
                "Creating vote request",
                eventuallySucceeds() {
                  sv1Backend.createVoteRequest(
                    sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                    upgradeAction,
                    "url",
                    "description",
                    sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
                  )
                },
              )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

              clue(s"sv2-3 accept") {
                Seq(sv2Backend, sv3Backend).map(sv =>
                  eventuallySucceeds() {
                    sv.castVote(
                      voteRequest.contractId,
                      true,
                      "url",
                      "description",
                    )
                  }
                )
              }
            },
          )(
            "observing CoinRules with upgraded config",
            _ => {
              val newCoinRules = sv1Client.getSvcInfo().coinRules
              val configs =
                (newCoinRules.payload.configSchedule.initialValue :: newCoinRules.payload.configSchedule.futureValues.asScala.toList
                  .map(_._2))
              configs.map(_.packageConfig.cantonCoin) should contain("0.1.1")
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

          // Vote on a dummy change on coin rules to ensure it is archived and recreated
          // which indicates the new choice is being used.
          val dummyUpgradeAction = new ARC_CoinRules(
            new CRARC_AddFutureCoinConfigSchedule(
              new CoinRules_AddFutureCoinConfigSchedule(
                new com.daml.network.codegen.java.da.types.Tuple2(
                  Instant.now().plus(1, ChronoUnit.HOURS),
                  newCoinConfig,
                )
              )
            )
          )

          actAndCheck(
            "Voting on a CoinRules config change for upgraded packages", {
              val (_, voteRequest) = actAndCheck(
                "Creating vote request",
                eventuallySucceeds() {
                  sv1Backend.createVoteRequest(
                    sv1Backend.getSvcInfo().svParty.toProtoPrimitive,
                    dummyUpgradeAction,
                    "url",
                    "description",
                    sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
                  )
                },
              )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)
              clue(s"sv2-sv3 accept") {
                Seq(sv2Backend, sv3Backend).map(sv =>
                  eventuallySucceeds() {
                    sv.castVote(
                      voteRequest.contractId,
                      true,
                      "url",
                      "description",
                    )
                  }
                )
              }
            },
          )(
            "observing CoinRules with new package id",
            _ => {
              val newCoinRules = sv1Backend.getSvcInfo().coinRules
              newCoinRules.identifier.getPackageId shouldBe DarResources.cantonCoin_current.packageId
            },
          )

          actAndCheck(
            "Alice taps after upgrade",
            eventuallySucceeds() {
              aliceWalletClient.tap(20)
            },
          )(
            "Old and new coin get merged together into a new coin",
            _ => {
              val coin = aliceWalletClient.list().coins.loneElement.contract
              coin.identifier.getPackageId shouldBe DarResources.cantonCoin_current.packageId
              BigDecimal(coin.payload.amount.initialAmount) should beWithin(30 - smallAmount, 30)
            },
          )

          // Bob can join after the upgrade
          bobValidatorBackend.startSync()
          val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
          // This is just to invalidate the coin rules cache on Bob’s side. In a real upgrade, the upgrade will be announced days or weeks in advance
          // while cache expiration is a few minutes so this is a non-issue.
          clue("Bob taps after upgrade") {
            eventuallySucceeds() {
              bobWalletClient.tap(5)
            }
          }

          actAndCheck(
            "Alice makes p2p transfer after upgrade",
            eventuallySucceeds() {
              p2pTransfer(aliceWalletClient, bobWalletClient, bob, 4.0)
            },
          )(
            "old and new taps and transfers appear in scan tx log",
            _ => {
              val txs = sv1ScanBackend.listActivity(pageEndEventId = None, pageSize = 50)
              // old tap
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.coinOwner shouldBe alice.toProtoPrimitive
                tf.coinAmount shouldBe "10.0000000000"
              }
              // new taps
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.coinOwner shouldBe alice.toProtoPrimitive
                tf.coinAmount shouldBe "20.0000000000"
              }
              forExactly(1, txs) { tx =>
                val tf = tx.tap.value
                tf.coinOwner shouldBe bob.toProtoPrimitive
                tf.coinAmount shouldBe "5.0000000000"
              }
              // new transfer
              forExactly(1, txs) { tx =>
                val tf = tx.transfer.value
                tf.sender.party shouldBe alice.toProtoPrimitive
                tf.receivers.loneElement.party shouldBe bob.toProtoPrimitive
                BigDecimal(tf.receivers.loneElement.amount) shouldBe 4.0
              }
            },
          )

          // SV4 can join after the upgrade.
          clue("SV4 can join after upgrade") {
            startAllSync(sv4Backend, sv4ValidatorBackend)
          }

          clue("Splitwell works") {

            splitwellValidatorBackend.startSync()
            splitwellBackend.startSync()

            // There is no auto-vetting for splitwell yet so we upload the DARs manually.
            aliceValidatorBackend.participantClient.upload_dar_unless_exists(
              splitwellDarPathCurrent
            )
            bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPathV1)
            bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPathCurrent)
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
                  new walletCodegen.ReceiverCCAmount(
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
        }
      )
    }

  }
}

object AppUpgradeIntegrationTest {

  final case class MultiCnProcessResource(logSuffix: String, loggerFactory: NamedLoggerFactory)
      extends NamedLogging {

    val processes = scala.collection.mutable.Map[String, Process]()

    def startBundledCN(name: String, config: Path): Unit = {
      // For now, we get the base version from BuildInfo, which is auto-generated by build.sbt
      val version = BuildInfo.compatibleVersion
      val process = ProcessTestUtil.startProcess(
        Seq(
          getBundledCn(version).toString,
          "daemon",
          "--log-level-canton=DEBUG",
          "--log-level-stdout=OFF",
          "--log-encoder",
          "json",
          "--log-file-name",
          s"log/cn-node-$logSuffix-$name.clog",
          "-c",
          config.toString,
        ),
        Seq(),
      )
      processes += (name -> process.process)
    }

    def stopBundledCN(name: String) = {
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

    def getBundledCn(version: String) = {
      val dir = getDir(bundleDir().resolve(version))
      val bundledCn = dir.resolve("cn-node-0.1.0-SNAPSHOT/bin/cn-node")
      if (!bundledCn.toFile.exists()) {
        throw new RuntimeException(
          s"Bundled CN artifacts for version ${version} not found, did you run build-tools/prep-app-upgrade-test ?"
        )
      }
      bundledCn
    }
  }

  object MultiCnProcessResource {
    implicit val releasable: Releasable[MultiCnProcessResource] =
      (resource: MultiCnProcessResource) => resource.destroyAllAndWait()
  }

  def getDir(dir: Path) = {
    if (!dir.toFile.exists()) {
      dir.toFile.mkdirs()
    }
    dir
  }

  def generatedConfigDir(): Path = {
    getDir(Paths.get("apps/app/src/test/resources/generated"))
  }

  def bundleDir(): Path = {
    getDir(Paths.get("apps/app/src/test/resources/bundles"))
  }
}
