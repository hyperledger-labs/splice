package com.daml.network.integration.tests

import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms, GcpBucketConfig}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest
import com.daml.network.util.{GcpBucket, ProcessTestUtil, WalletTestUtil}

import java.nio.file.{Files, Path, Paths}
import better.files.*
import com.daml.network.environment.BuildInfo
import com.daml.network.integration.tests.AppUpgradeIntegrationTest.generatedConfigDir
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory

import scala.util.{Failure, Success, Try, Using}
import scala.util.Using.Releasable
import scala.concurrent.duration.*
import java.io.{BufferedInputStream, ByteArrayInputStream}

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

    def startBundledCN(name: String, config: Path)(implicit tc: TraceContext): Unit = {
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
          s"log/cn-node-${logSuffix}.clog",
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

    def getBundledCn(version: String)(implicit
        tc: TraceContext
    ) = {
      val dir = getDir(bundleDir().resolve(version))
      val bundledCn = dir.resolve("cn-node-0.1.0-SNAPSHOT/bin/cn-node")
      if (!bundledCn.toFile.exists()) {
        downloadBundledCn(version)
      }
      bundledCn
    }

    def downloadBundledCn(version: String)(implicit tc: TraceContext) = {
      val config = GcpBucketConfig.inferForBundles
      val bucket = new GcpBucket(config, loggerFactory)
      val filename = s"${version}_cn-node-0.1.0-SNAPSHOT.tar.gz"
      logger.debug(s"Downloading bundled release for version ${version}")
      val bytes = downloadWithRetry(bucket, filename, 5)
      val inputStream = new ByteArrayInputStream(bytes)

      val outDir = getDir(bundleDir().resolve(version))
      getDir(outDir.resolve("cn-node-0.1.0-SNAPSHOT/lib"))
      getDir(outDir.resolve("cn-node-0.1.0-SNAPSHOT/bin"))
      val uncompressedInputStream = new CompressorStreamFactory().createCompressorInputStream(
        new BufferedInputStream(inputStream)
      )
      val tarStream = new TarArchiveInputStream(new BufferedInputStream(uncompressedInputStream))
      LazyList
        .continually(tarStream.getNextTarEntry())
        .takeWhile(_ != null)
        .filter(entry => entry.getName.endsWith(".jar") || entry.getName.endsWith("cn-node"))
        .foreach { entry =>
          val content = ByteString.readFrom(tarStream).toByteArray
          val filename = entry.getName
          Files.write(outDir.resolve(filename), content)
          if (filename.endsWith("cn-node")) {
            outDir.resolve(filename).toFile.setExecutable(true)
          }
        }
    }

    def downloadWithRetry(bucket: GcpBucket, filename: String, maxRetries: Int)(implicit
        tc: TraceContext
    ): Array[Byte] = {
      Try(bucket.readBytesFromBucket(filename)) match {
        case Success(value) => value
        case Failure(exception) =>
          if (maxRetries < 0) throw exception
          else {
            logger.debug(s"Failed download: ${exception.getMessage}, retrying in 5 seconds")
            Threading.sleep(5000)
            downloadWithRetry(bucket, filename, maxRetries - 1)
          }
      }
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
