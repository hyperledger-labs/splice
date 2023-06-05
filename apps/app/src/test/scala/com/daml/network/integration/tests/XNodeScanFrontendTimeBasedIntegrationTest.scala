package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class XNodeScanFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("scan-ui")
    with FrontendLoginUtil
    with WalletTestUtil
    with TimeTestUtil {

  val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomainWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withCoinPrice(coinPrice)

  "A scan UI" should {
    "see app provider leaderboard by rewards" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()

      waitForWalletUser(aliceValidatorWallet)
      waitForWalletUser(bobValidatorWallet)

      grantFeaturedAppRight(aliceValidatorWallet)

      clue("Tap to get some coins") {
        aliceWallet.tap(500.0)
        aliceValidatorWallet.tap(100.0)
      }

      clue("Feature alice's validator and transfer some CC, to generate reward coupons")({
        p2pTransfer(aliceValidator, aliceWallet, bobWallet, bobUserParty, 40.0)
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        p2pTransfer(aliceValidator, aliceValidatorWallet, bobWallet, bobUserParty, 10.0)
      })

      clue("Advance rounds to collect rewards") {
        Range(0, 5).foreach(_ => advanceRoundsByOneTick)
      }

      val aliceValidatorWalletParty = aliceValidatorWallet.userStatus().party

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to app leaderboard page in scan UI",
          go to "http://localhost:3006/app-leaderboard",
        )(
          "Check app leaderboard table and see entry",
          _ => {
            findAll(className("app-leaderboard-row")).length shouldBe 1
          },
        )

        def compareLeaderboardTable(
            resultRowClassName: String,
            expected: Seq[(String, String, String)],
        ) = {
          findAll(className(resultRowClassName)).toSeq.map(row => {
            val children = row.findAllChildElements(tagName("td"))
            children.map(c => c.text).toList match {
              case List(a, b, c) => (a, b, c)
              case _ => fail("Expected a list of 3 elements")
            }
          }) shouldBe expected
        }

        compareLeaderboardTable(
          "app-leaderboard-row",
          Seq((aliceValidatorWalletParty, "--.-- CC", "41.5 CC")),
        )
      }
    }
  }
}
