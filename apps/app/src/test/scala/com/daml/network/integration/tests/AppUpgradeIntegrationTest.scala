package com.daml.network.integration.tests

import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.{ProcessTestUtil, WalletTestUtil}

import java.nio.file.{Path, Paths}
import better.files.*
import com.daml.network.environment.BuildInfo
import com.daml.network.integration.tests.AppUpgradeIntegrationTest.generatedConfigDir
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.util.Using
import scala.util.Using.Releasable
import scala.concurrent.duration.*

class AppUpgradeIntegrationTest
    extends CNNodeIntegrationTest
    with ProcessTestUtil
    with WalletTestUtil {

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
      "validators",
    ).map(configFile => {
      val config = CNNodeConfig.parseAndLoadOrThrow(
        Seq(Paths.get(s"apps/app/src/test/resources/include/nodes/${configFile}.conf").toFile)
      )
      val configOut = CNNodeConfigTransforms.defaults().foldLeft(config)((c, t) => t(c))
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
  }

  "A set of CN apps" should {
    "be upgradeable" in { implicit env =>
      Using.resource(AppUpgradeIntegrationTest.MultiCnProcessResource("forUpgrade", loggerFactory))(
        cnProcs => {
          confs.foreach(conf => cnProcs.startBundledCN(conf._1, conf._2))

          eventually(5.minute) {
            Seq("sv1", "sv2", "sv3", "sv4").foreach(sv => {
              svcl(s"${sv}Client").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} SV app initialized"
              wc(s"${sv}Wallet").httpHealth.successOption.exists(_.active) should be(
                true
              ) withClue s"${sv} wallet initialized"
            })
            Seq("alice", "bob").foreach(validator =>
              vc(s"${validator}ValidatorClient").httpHealth.successOption
                .exists(_.active) should be(true)
            )
          }

          val sv2Wallet = wc("sv2Wallet")
          val sv1Client = svcl("sv1Client")

          clue("Tapping some coin in the network before any upgrades") {
            aliceValidatorWalletClient.tap(1_000_001)
            aliceValidatorWalletClient.balance().unlockedQty should be > BigDecimal(1_000_000)
            sv2Wallet.tap(1_000_002)
            sv2Wallet.balance().unlockedQty should be > BigDecimal(1_000_000)
          }

          clue("Upgrading validators") {
            cnProcs.stopBundledCN("validators")
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
