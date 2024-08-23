package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.util.ProcessTestUtil.startProcess
import com.daml.network.util.{AnsFrontendTestUtil, FrontendLoginUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.io.File
import scala.concurrent.duration.*
import scala.util.Using
import scala.sys.process.*

class DockerComposeValidatorFrontendIntegrationTest
    extends FrontendIntegrationTest("alice-selfhosted")
    with FrontendLoginUtil
    with WalletFrontendTestUtil
    with AnsFrontendTestUtil {
  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  // TODO(#14303): auth0 variant (see AnsAuth0FrontendIntegrationTest)
  // TODO(#14303): test the administrator user

  "run through runbook with self-hosted validator" in { _ =>
    Using.resource(
      startProcess(
        Seq("scripts/compose-validator-for-integration-test.sh"),
        Seq.empty,
      )
    )(_ => {
      withFrontEnd("alice-selfhosted") { implicit webDriver =>
        eventuallySucceeds()(go to s"http://wallet.localhost")
        actAndCheck(5.minute)(
          "Login as alice",
          login(80, "alice", "wallet.localhost"),
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
        val ansName = s"alice_${(new scala.util.Random).nextInt().toHexString}.unverified.cns"
        reserveAnsNameFor(
          () => login(80, "alice", "ans.localhost"),
          ansName,
          "1.0000000000",
          "USD",
          "90 days",
        )
      }
    })(_ =>
      "cluster/deployment/compose/stop.sh" ! new FileProcessLogger(new File("log/compose.log"))
    )
  }
}
