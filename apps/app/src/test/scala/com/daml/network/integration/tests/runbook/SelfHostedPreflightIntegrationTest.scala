package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.{
  ProcessTestUtil,
  DirectoryFrontendTestUtil,
  FrontendLoginUtil,
  WalletFrontendTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import scala.util.Using

/** Preflight test that spins up a new validator following our runbook.
  */
class SelfHostedPreflightIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with HasConsoleScriptRunner
    with ProcessTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with PreflightIntegrationTestUtil
    with DirectoryFrontendTestUtil {

  // We need to delay this until we started the validator. Otherwise we might
  // end up with port collisions.
  override lazy val autostartWebDrivers = false

  private val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  private val validatorPath: File = examplesPath / "validator"

  override protected def extraPortsToWaitFor: Seq[(String, Int)] = Seq(
    ("ParticipantLedgerApi", 6001),
    ("ParticipantAdminApi", 6002),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-onboarding-nosecret.conf",
      )
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(1000)(conf))
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.sc` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // Obtain a fresh onboarding secret from a SV because this is what we want runbook users to do.
      .addConfigTransforms((_, conf) => insertValidatorOnboardingSecret(conf))

  "run through runbook with self-hosted validator" in { implicit env =>
    // Start Canton as a separate process. We do that here rather than in the env setup
    // because it is only needed for this one test.

    val cantonArgs = Seq(
      "-c",
      (validatorPath / "validator-participant.conf").toString,
      "-C",
      "canton.participants.validatorParticipant.ledger-api.port=6001",
      "-C",
      "canton.participants.validatorParticipant.admin-api.port=6002",
      "--bootstrap",
      (validatorPath / "validator-participant.sc").toString,
    )
    checkFrontendsNetworkAppsAddress(sys.env("NETWORK_APPS_ADDRESS"))

    Using.resource(startCanton(cantonArgs, "self-hosted-validator")) { _ =>
      runScript(validatorPath / "validator.sc")(env.environment)
      // We start the JSON API after the participant because the
      // reconnection logic in the JSON API doesn't seem to work well and we otherwise
      // sometimes fail requests for quite some time.
      Using.resource(startJsonApi(6001, File("log/json-api-self-hosted.log").toJava)) { _ =>
        runScript(validatorPath / "tap-transfer-demo.sc")(env.environment)

        v("validatorApp").participantClient.dars
          .upload("./daml/directory-service/.daml/dist/directory-service-0.1.0.dar")

        val walletUiPort = 3000
        val directoryUiPort = 3004

        // Generate new random CNS names to avoid conflicts between multiple preflight check runs
        val id = (new scala.util.Random).nextInt().toHexString
        val cnsName = s"alice_${id}.cns"

        startWebDriver("alice-selfhosted")

        withFrontEnd("alice-selfhosted") { implicit webDriver =>
          login(walletUiPort, "alice")
          tapCoins(100)
          reserveDirectoryNameFor(() => login(directoryUiPort, "alice"), cnsName)
          // "Close" frontend before Canton is shut down to avoid failures in ACS queries.
          go to "about:blank"
          eventually() {
            pageTitle shouldBe ""
          }
        }
      }

      // Stop nodes before Canton is shutdown
      env.coinNodes.local.foreach(_.stop())
    }
  }

  private def checkFrontendsNetworkAppsAddress(networkAppsAddress: String): Unit = {
    clue(s"Checking frontends match given network apps address: ${networkAppsAddress}") {
      eventually() {
        "start-frontends-network-address".toFile.lines.headOption shouldBe Some(networkAppsAddress)
      }
    }
  }
}
