package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{AnsFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*
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
  val partyHint = "da-ComposeValidator-1"

  def startComposeValidator(
      extraClue: String = "",
      startFlags: Seq[String] = Seq.empty,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ) = {
    val command = (Seq(
      "build-tools/splice-compose.sh",
      "start",
      "-l",
      "-w",
      "-p",
      partyHint,
    ) ++ startFlags).asJava
    val builder = new ProcessBuilder(command)
    extraEnv.foreach { case (k, v) => builder.environment().put(k, v) }
    val ret = builder.!
    if (ret != 0) {
      fail(s"Failed to start docker-compose validator ($extraClue)")
    }
  }

  def withComposeValidator[A](
      extraClue: String = "",
      startFlags: Seq[String] = Seq.empty,
      extraEnv: Seq[(String, String)] = Seq.empty,
  )(
      test: => A
  ): A = {
    try {
      startComposeValidator(extraClue, startFlags, extraEnv)
      test
    } finally {
      Seq("build-tools/splice-compose.sh", "stop", "-D", "-f") !
    }
  }

  "docker-compose based validator works" in { implicit env =>
    val aliceTap = 123.4

    def aliceLoggedInAndHasBalance()(implicit webDriver: WebDriverType): Unit = {
      seleniumText(find(id("logged-in-user"))) should startWith("alice")
      val balanceUsd = find(id("wallet-balance-usd"))
        .valueOrFail("Couldn't find balance")
        .text
        .split(" ")
        .head
      balanceUsd.toDouble should be > aliceTap - 5.0
    }

    val backupsDir: Path =
      testDumpDir.resolve("compose-validator-backup").resolve(java.time.Instant.now.toString)

    withComposeValidator() {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck()(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
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

    val identities = backupsDir.resolve("identities.json")

    withComposeValidator("after restoring from backup") {
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
        clue("Logout Alice") {
          click on find(id("logout-button")).value
        }
      }

      clue("Getting participant identities dump from validator") {
        if (
          Seq(
            "build-tools/splice-compose.sh",
            "identities_dump",
            identities.toAbsolutePath.toString,
          ).! != 0
        ) {
          fail("Failed to create identities dump")
        }
      }
    }

    withComposeValidator(
      "recovering from identities dump",
      Seq(
        "-i",
        identities.toAbsolutePath.toString,
        "-P",
        "da-composeValidator-13",
      ),
    ) {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        clue("Alice can onboard again") {
          actAndCheck(
            "Login as alice",
            loginOnCurrentPage(80, "alice", "wallet.localhost"),
          )(
            "Alice can re-onboard",
            _ => {
              find(id("onboard-button")).value.text should not be empty
            },
          )
        }
        actAndCheck(
          "onboard alice",
          click on "onboard-button",
        )(
          "Alice is logged in and maintained her balance",
          _ => aliceLoggedInAndHasBalance(),
        )
        clue("Logout Alice") {
          click on find(id("logout-button")).value
        }
      }

      clue("Stop the validator (without wiping its data)") {
        Seq("build-tools/splice-compose.sh", "stop") !
      }
      clue("Restart the validator, with the new participant ID") {
        startComposeValidator("with the new participant ID", Seq("-P", "da-composeValidator-13"))
      }
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        clue("Alice can login and is already onboarded") {
          actAndCheck()(
            s"Login as alice",
            loginOnCurrentPage(80, "alice", "wallet.localhost"),
          )(
            "Alice is already onboarded, and still sees here balance",
            _ => aliceLoggedInAndHasBalance(),
          )
        }
      }
    }
  }

  "docker-compose based validator with auth works" in { _ =>
    val validatorUserPassword = sys.env(s"VALIDATOR_WEB_UI_PASSWORD")

    withComposeValidator(
      extraClue = "with auth",
      startFlags = Seq("-a"),
      extraEnv = Seq(
        "GCP_CLUSTER_BASENAME" -> "cidaily" // Any cluster should work, as long as its UI auth0 apps were created with the localhost callback URLs
      ),
    ) {
      withFrontEnd("selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        completeAuth0LoginWithAuthorization(
          "http://wallet.localhost",
          "admin@validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
        completeAuth0LoginWithAuthorization(
          "http://ans.localhost",
          "admin@validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
      }
    }
  }
}
