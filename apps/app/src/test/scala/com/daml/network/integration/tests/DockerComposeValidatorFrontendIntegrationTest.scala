package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{AnsFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.sys.process.*
import java.lang.ProcessBuilder
import java.nio.file.{Path, Paths}

class DockerComposeValidatorFrontendIntegrationTest
    extends FrontendIntegrationTest("selfhosted")
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with AnsFrontendTestUtil {
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

  "docker-compose based validator works" in { implicit env =>
    val aliceTap = 123.4
    val builder = new ProcessBuilder("build-tools/splice-compose.sh", "start", "-l", "-w")
    val ret = builder.!
    if (ret != 0) {
      fail("Failed to start docker-compose validator")
    }
    val backupsDir: Path =
      testDumpDir.resolve("compose-validator-backup").resolve(java.time.Instant.now.toString)
    try {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck()(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith("da-composeValidator-1"),
        )
        actAndCheck(
          "Login as alice",
          loginOnCurrentPage(80, "alice", "wallet.localhost"),
        )(
          "Alice can onboard",
          _ => find(id("onboard-button")).value.text should not be empty,
        )
        actAndCheck(
          "onboard alice",
          click on "onboard-button",
        )(
          "Alice is logged in",
          _ => seleniumText(find(id("logged-in-user"))) should not be "",
        )
        tapAmulets(aliceTap)
        val ansName =
          s"alice_${(new scala.util.Random).nextInt().toHexString}.unverified.$ansAcronym"
        reserveAnsNameFor(
          () => login(80, "alice", "ans.localhost"),
          ansName,
          "1.0000000000",
          "USD",
          "90 days",
          ansAcronym,
        )
      }

      if (!backupsDir.toFile.exists())
        backupsDir.toFile.mkdirs()

      // Take a backup of the validator
      Seq("build-tools/splice-compose.sh", "backup_node", backupsDir.toString) !

    } finally {
      Seq("build-tools/splice-compose.sh", "stop", "-D", "-f") !
    }

    // Restore the node from backup
    val validatorBackup =
      backupsDir.toFile.listFiles().filter(_.getName.startsWith("validator")).head
    val participantBackup =
      backupsDir.toFile.listFiles().filter(_.getName.startsWith("participant")).head
    Seq(
      "build-tools/splice-compose.sh",
      "restore_node",
      validatorBackup.toString,
      participantBackup.toString,
      "0",
    ) !

    // Spin up the validator node again
    val ret2 = builder.!
    if (ret2 != 0) {
      fail("Failed to start docker-compose validator after restoring from backup")
    }
    try {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        clue("Alice can login and is already onboarded") {
          actAndCheck()(
            s"Login as alice",
            loginOnCurrentPage(80, "alice", "wallet.localhost"),
          )(
            "Alice is already onboarded",
            _ => seleniumText(find(id("logged-in-user"))) should startWith("alice"),
          )
        }
        clue(s"We see Alice's tap") {
          eventually() {
            val txs = findAll(className("tx-row")).toSeq
            val taps = txs.flatMap(readTapFromRow)
            taps should have length 1
            taps.head.tapAmount shouldBe walletUsdToAmulet(BigDecimal.valueOf(aliceTap))
          }
        }
      }
    } finally {
      Seq("build-tools/splice-compose.sh", "stop", "-D", "-f") !
    }
  }

  "docker-compose based validator with auth works" in { _ =>
    val validatorUserPassword = sys.env(s"VALIDATOR_WEB_UI_PASSWORD")
    val builder =
      new ProcessBuilder("build-tools/splice-compose.sh", "start", "-l", "-a", "-w")
    builder
      .environment()
      .put(
        "GCP_CLUSTER_BASENAME",
        "cidaily",
      ) // Any cluster should work, as long as its UI auth0 apps were created with the localhost callback URLs
    val ret = builder.!
    if (ret != 0) {
      fail("Start script failed")
    }
    try {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        completeAuth0LoginWithAuthorization(
          "http://wallet.localhost",
          "admin@validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith("da-composeValidator-1"),
        )
        completeAuth0LoginWithAuthorization(
          "http://ans.localhost",
          "admin@validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith("da-composeValidator-1"),
        )
      }
    } finally {
      Seq("build-tools/splice-compose.sh", "stop", "-D", "-f") !
    }
  }
}
