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

  "A SV UI" should {

    "have basic login functionality" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "login works with correct password", {
            login(3010, sv1.config.ledgerApiUser)
          },
        )(
          "login does not work with wrong password",
          _ => find(id("app-title")).value.text should matchText("SV OPERATIONS"),
        )
      }
    }

    "have a proper table" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "svc infos are displayed", {
            login(3010, sv1.config.ledgerApiUser)
          },
        )(
          "We see a table with sv1 as SV Name",
          _ => {
            val rows = findAll(className("value-name")).toSeq
            rows should have length 15
            forExactly(1, rows)(
              _.text should matchText(sv1.getSvcInfo().svUser)
            )
            forExactly(3, rows)(
              _.text should matchText(sv1.getSvcInfo().svParty.toProtoPrimitive)
            )
          },
        )

      }
    }
  }

}
