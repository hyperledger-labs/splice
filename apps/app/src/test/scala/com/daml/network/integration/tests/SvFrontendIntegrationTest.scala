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

    "have basic functionalities" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 can login", {
            login(3010, "sv1")
          },
        )(
          "We see a UI with an expected title",
          _ => find(id("app-title")).value.text should matchText("SV OPERATIONS"),
        )

        actAndCheck(
          "svc infos are displayed", {
            go to s"http://localhost:3010/svc"
          },
        )(
          "We see a table with sv1 as SV Name",
          _ => {
            val rows = findAll(className("value-name")).toSeq
            println(rows)
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
