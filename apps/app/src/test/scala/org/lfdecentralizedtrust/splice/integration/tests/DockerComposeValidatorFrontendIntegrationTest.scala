package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.Get
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Host
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  AnsFrontendTestUtil,
  FrontendLoginUtil,
  WalletFrontendTestUtil,
}

import java.lang.ProcessBuilder
import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class DockerComposeValidatorFrontendIntegrationTest
    extends FrontendIntegrationTest("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with AnsFrontendTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")
  val partyHint = "da-ComposeValidator-1"

  def startComposeValidator(
      extraClue: String = "",
      startFlags: Seq[String] = Seq.empty,
      extraEnv: Seq[(String, String)] = Seq.empty,
  ): Unit = {
    val command = (Seq(
      "build-tools/splice-compose.sh",
      "start",
      "-l",
      "-w",
      "-p",
      partyHint,
      "-b",
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
    implicit val actorSystem: ActorSystem = env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    val aliceTap = 123.4
    val adminTap = 234.5

    def userLoggedInAndHasBalance(userPrefix: String, tappedAmount: Double)(implicit
        webDriver: WebDriverType
    ): Unit = {
      seleniumText(find(id("logged-in-user"))) should startWith(userPrefix)
      val balanceUsd = find(id("wallet-balance-usd"))
        .valueOrFail("Couldn't find balance")
        .text
        .split(" ")
        .head
      balanceUsd.toDouble should be > tappedAmount - 5.0
    }

    val backupsDir: Path =
      testDumpDir
        .resolve("compose-validator-backup")
        .resolve(java.time.Instant.now.toEpochMilli.toString)

    withComposeValidator() {
      withFrontEnd("frontend") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck(timeUntilSuccess = 60.seconds)(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
        waitForTrafficPurchase()
        tapAmulets(adminTap)
        actAndCheck(
          "Login as alice",
          loginOnCurrentPage(80, "alice", "wallet.localhost"),
        )(
          "Alice can onboard",
          _ => find(id("onboard-button")).value.text should not be empty,
        )
        actAndCheck(
          "onboard alice",
          eventuallyClickOn(id("onboard-button")),
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

      clue("JSON ledger API is exposed") {
        val response =
          Http().singleRequest(Get("http://json-ledger-api.localhost/v2/version")).futureValue
        response.status should be(StatusCodes.OK)
        response.entity.toStrict(10.seconds).futureValue.data.utf8String should include(
          "\"version\":\"3." // check that it reports a version. We don't care about the exact version
        )
      }

      clue("GRPC ledger API is exposed") {
        import com.digitalasset.canton.ledger.client.GrpcChannel
        import scala.util.Using
        val channelConfig = com.digitalasset.canton.ledger.client.configuration
          .LedgerClientChannelConfiguration(sslContext = None)
        implicit val releasableChannel: Using.Releasable[io.grpc.ManagedChannel] =
          (resource: io.grpc.ManagedChannel) => {
            GrpcChannel.close(resource)
          }
        Using.resource(
          channelConfig
            .builderFor("grpc-ledger-api.localhost", 80)
            .executor(env.executionContext)
            .build
        ) { channel =>
          import com.daml.ledger.api.v2.version_service.*
          val stub = VersionServiceGrpc.stub(channel)
          val version = stub.getLedgerApiVersion(GetLedgerApiVersionRequest()).futureValue
          version.version should startWith("3.")
        }
      }

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
      withFrontEnd("frontend") { implicit webDriver =>
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
            taps.head.tapAmountAmulet shouldBe walletUsdToAmulet(
              BigDecimal.valueOf(aliceTap),
              1.0 / taps.head.usdToAmulet,
            )
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
        identities.toFile.exists() shouldBe true
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
      withFrontEnd("frontend") { implicit webDriver =>
        go to s"http://wallet.localhost"
        actAndCheck(timeUntilSuccess = 60.seconds)(
          "Login as administrator",
          login(80, "administrator", "wallet.localhost"),
        )(
          "administrator is already onboarded",
          _ => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
        // Wait for some traffic to be bought before proceeding, so that we don't
        // hit a "traffic below reserved amount" error
        waitForTrafficPurchase()
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

          actAndCheck(
            "onboard alice",
            eventuallyClickOn(id("onboard-button")),
          )(
            "Alice is logged in and maintained her balance",
            _ => userLoggedInAndHasBalance("alice", aliceTap),
          )
        }
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
      withFrontEnd("frontend") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        clue("Alice can login and is already onboarded") {
          actAndCheck()(
            s"Login as alice",
            loginOnCurrentPage(80, "alice", "wallet.localhost"),
          )(
            "Alice is already onboarded, and still sees here balance",
            _ => userLoggedInAndHasBalance("alice", aliceTap),
          )
        }
      }

      clue("validator and participant metrics work") {

        def metricsAreAvailableFor(node: String) = {
          val result = Http()
            .singleRequest(
              Get(s"http://localhost/metrics")
                // java can't resolve the *.localhost domain, so we need to set the Host header manually
                .withHeaders(Host(s"$node.localhost"))
            )
            .futureValue
          result.status should be(StatusCodes.OK)
          result.entity.toStrict(10.seconds).futureValue.data.utf8String should include(
            "target_info" // basic metric included by opentelemtry
          )
        }

        metricsAreAvailableFor(
          "validator"
        )
        metricsAreAvailableFor(
          "participant"
        )
      }

      withFrontEnd("frontend") { implicit webDriver =>
        // Navigate out of the wallet to prevent errors in the logs as we restart the validator with auth
        go to "about:blank"
      }

      clue("Stop the validator (without wiping its data)") {
        Seq("build-tools/splice-compose.sh", "stop") !
      }

      clue("Restart the validator, with auth") {
        startComposeValidator(
          extraClue = "with auth",
          startFlags = Seq("-a", "-P", "da-composeValidator-13"),
          extraEnv = Seq(
            "GCP_CLUSTER_BASENAME" -> "cidaily" // Any cluster should work, as long as its UI auth0 apps were created with the localhost callback URLs
          ),
        )
      }

      withFrontEnd("frontend") { implicit webDriver =>
        val validatorUserPassword = sys.env(s"COMPOSE_VALIDATOR_WEB_UI_PASSWORD")
        eventuallySucceeds()(go to s"http://wallet.localhost")
        completeAuth0LoginWithAuthorization(
          "http://wallet.localhost",
          "admin@compose-validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
        userLoggedInAndHasBalance("da-ComposeValidator-1::", adminTap)
        completeAuth0LoginWithAuthorization(
          "http://ans.localhost",
          "admin@compose-validator.com",
          validatorUserPassword,
          () => seleniumText(find(id("logged-in-user"))) should startWith(partyHint),
        )
      }

    }
  }
}
