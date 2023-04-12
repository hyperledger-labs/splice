package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, SvTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class SvFrontendIntegrationTest
    extends FrontendIntegrationTest("sv1")
    with SvTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  "A SV UI" should {

    "exist" in { _ =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck("We open SV1's web UI", { go to s"http://localhost:3010" })(
          "We see a UI with an expected title",
          _ => find(id("app-title")).value.text should matchText("SV OPERATIONS"),
        )
      }
    }
  }
}
