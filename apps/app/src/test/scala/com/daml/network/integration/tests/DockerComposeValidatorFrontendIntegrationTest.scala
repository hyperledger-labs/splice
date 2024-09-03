package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.{AnsFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.sys.process.*

import java.lang.ProcessBuilder

class DockerComposeValidatorFrontendIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with AnsFrontendTestUtil {
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "docker-compose based validator works" in { implicit env =>
    val builder = new ProcessBuilder("build-tools/splice-compose.sh", "start", "-l", "-w")
    val ret = builder.!
    if (ret != 0) {
      fail("Failed to start docker-compose validator")
    }
    try {
      withFrontEnd("alice-selfhosted") { implicit webDriver =>
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
        tapAmulets(123.4)
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
      withFrontEnd("alice-selfhosted") { implicit webDriver =>
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
