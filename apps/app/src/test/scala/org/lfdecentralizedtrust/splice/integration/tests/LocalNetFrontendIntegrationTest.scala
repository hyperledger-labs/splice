package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, WalletFrontendTestUtil}

import scala.concurrent.duration.*
import scala.sys.process.*

class LocalNetFrontendIntegrationTest
    extends FrontendIntegrationTest("frontend")
    with FrontendLoginUtil
    with WalletFrontendTestUtil {
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(Seq("localnet-topology.conf"), this.getClass.getSimpleName)
      .withManualStart

  // This does nothing as the wallet clients will not actually be connected to the compose setup
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override lazy val resetRequiredTopologyState = false

  val partyHint = "localnet-localparty-1"

  "docker-compose based localnet work" in { implicit env =>
    try {
      val ret = Seq("build-tools/splice-localnet-compose.sh", "start").!
      if (ret != 0) {
        fail("Failed to start docker-compose SV and validator")
      }

      clue("Test validators") {
        List(
          ("app-user", 2000, "app_user"),
          ("app-provider", 3000, "app_provider"),
        ).foreach { case (user, port, partyHintPrefix) =>
          clue(s"Test $user validator") {
            val host = "wallet.localhost"
            val url = s"http://$host:$port"
            withFrontEnd("frontend") { implicit webDriver =>
              eventuallySucceeds()(go to url)
              actAndCheck(timeUntilSuccess = 60.seconds)(
                s"Login as $user",
                login(port, user, host),
              )(
                s"$user is already onboarded",
                _ =>
                  seleniumText(find(id("logged-in-user"))) should startWith(
                    s"${partyHintPrefix}_$partyHint"
                  ),
              )
              // Wait for some traffic to be bought before proceeding so that we don't hit a "traffic below reserved amount" error
              waitForTrafficPurchase()
              go to url
              actAndCheck(
                s"Login as $user",
                loginOnCurrentPage(port, user, host),
              )(
                s"$user is already onboarded",
                _ =>
                  seleniumText(find(id("logged-in-user"))) should startWith(
                    s"${partyHintPrefix}_$partyHint"
                  ),
              )
              tapAmulets(345.6)
            }
          }
        }

        clue("Basic test of SV UIs") {
          withFrontEnd("frontend") { implicit webDriver =>
            actAndCheck(
              "Open the Scan UI",
              go to "scan.localhost:4000",
            )(
              "Open rounds should be listed",
              _ => findAll(className("open-mining-round-row")) should have length 2,
            )

            actAndCheck(timeUntilSuccess = 60.seconds)(
              "Login to the wallet as sv",
              login(4000, "sv", "wallet.localhost"),
            )(
              "sv is already onboarded",
              _ => seleniumText(find(id("logged-in-user"))) should startWith("sv.sv.ans"),
            )

            actAndCheck()(
              "Login to the SV app as sv",
              login(4000, "sv", "sv.localhost"),
            )(
              "sv is already onboarded, and the app is working",
              _ => {
                seleniumText(
                  find(id("svUser")).value
                    .childElement(className("general-dso-value-name"))
                ) should be("ledger-api-user")
              },
            )

          }
        }
      }

      clue("Test token standard APIs") {
        val registryInfo = scancl("scanClient").getRegistryInfo()
        registryInfo.adminId should startWith("DSO::")
        val token = AuthUtil.testToken(AuthUtil.testAudience, "ledger-api-user", "unsafe")
        val userRegistryInfo =
          vc("userValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
        val providerRegistryInfo =
          vc("providerValidatorClient").copy(token = Some(token)).scanProxy.getRegistryInfo()
        registryInfo shouldBe userRegistryInfo
        registryInfo shouldBe providerRegistryInfo
      }
    } finally {
      Seq("build-tools/splice-localnet-compose.sh", "stop", "-D").!
    }
  }
}
