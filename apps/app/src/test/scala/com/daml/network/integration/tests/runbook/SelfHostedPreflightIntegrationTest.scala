package com.daml.network.integration.tests.runbook

import better.files.*
import com.daml.network.LiveDevNetTest
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTest
import com.daml.network.util.{
  CantonProcessTestUtil,
  DirectoryFrontendTestUtil,
  WalletFrontendTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.integration.tests.HasConsoleScriptRunner
import monocle.macros.syntax.lens.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.util.Using
import com.daml.network.util.FrontendLoginUtil

/** Preflight test that spins up a new validator following our runbook.
  */
class SelfHostedPreflightIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with HasConsoleScriptRunner
    with CantonProcessTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with DirectoryFrontendTestUtil {

  // We need to delay this until we started the validator. Otherwise we might
  // end up with port collisions.
  override lazy val autostartWebDrivers = false

  private val examplesPath: File = "apps" / "app" / "src" / "pack" / "examples"
  private val validatorPath: File = examplesPath / "validator"

  // We cache this because we only need it for one test case
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var validatorOnboardingSecret: Option[String] = None

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .fromFiles(
        this.getClass.getSimpleName,
        validatorPath / "validator.conf",
        validatorPath / "validator-onboarding-nosecret.conf",
      )
      // clearing default config transforms because they have settings
      // we don't want such as adjusting daml names or triggering automation every second
      .clearConfigTransforms()
      .addConfigTransforms((_, conf) => CNNodeConfigTransforms.bumpCantonPortsBy(1000)(conf))
      .addConfigTransforms((_, conf) =>
        CNNodeConfigTransforms.useSelfSignedTokensForWalletValidatorApiAuth("test")(conf)
      )
      // Disable autostart, because our apps require the participant to be connected to a domain
      // when the app starts. The apps are started manually in `validator-participant.sc` below.
      .addConfigTransforms((_, conf) => conf.focus(_.parameters.manualStart).replace(true))
      // Obtain a fresh onboarding secret from a SV because this is what we want runbook users to do.
      .addConfigTransforms((_, conf) => insertValidatorOnboardingSecret(conf))

  "run through runbook with self-hosted validator" taggedAs LiveDevNetTest in { implicit env =>
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
    Using.resource(startCanton(cantonArgs)) { process =>
      runScript(validatorPath / "validator.sc")(env.environment)
      runScript(validatorPath / "tap-transfer-demo.sc")(env.environment)

      v("validatorApp").remoteParticipant.dars
        .upload("./daml/directory-service/.daml/dist/directory-service-0.1.0.dar")

      val walletUiPort = 3000
      val directoryUiPort = 3001

      // Generate new random CNS names to avoid conflicts between multiple preflight check runs
      val id = (new scala.util.Random).nextInt().toHexString
      val cnsName = s"alice+${id}.cns"

      startWebDriver("alice-selfhosted")

      withFrontEnd("alice-selfhosted") { implicit webDriver =>
        login(walletUiPort, "alice")
        tapAndListCoins(100)
        reserveDirectoryNameFor(() => login(directoryUiPort, "alice"), cnsName)
        // "Close" frontend before Canton is shut down to avoid failures in ACS queries.
        go to "about:blank"
        eventually() {
          pageTitle shouldBe ""
        }
      }
      // Stop nodes before Canton is shutdown
      env.coinNodes.local.foreach(_.stop())
    }
  }

  private def insertValidatorOnboardingSecret(conf: CNNodeConfig): CNNodeConfig = {

    conf.validatorApps.size shouldBe 1

    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      val oc = vc.onboarding.value

      // obtain an onboarding secret
      val secret = validatorOnboardingSecret match {
        case Some(s) => s
        case None => {
          val s = prepareValidatorOnboarding(oc.remoteSv.adminApi.url)
          validatorOnboardingSecret = Some(s)
          s
        }
      }
      // insert it
      vc.focus(_.onboarding).replace(Some(oc.copy(secret = secret)))
    })(conf)
  }

  // We invoke the API via a basic HTTP request, just like we expect runbook users to do for now.
  private def prepareValidatorOnboarding(url: String): String = {
    val client = HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(20))
      .build()

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(url + "/admin/validator/onboarding/prepare"))
      .header("content-type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("{\"expires_in\":3600}"))
      .build();

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val secret = (io.circe.parser.parse(response.body).value \\ "secret" head).asString.value
    secret
  }
}
