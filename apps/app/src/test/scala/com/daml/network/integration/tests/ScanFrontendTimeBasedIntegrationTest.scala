package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class ScanFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("scan-ui")
    with FrontendLoginUtil
    with WalletTestUtil
    with TimeTestUtil {

  val coinPrice = 2
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
      .withCoinPrice(coinPrice)

  def compareLeaderboardTable(
      resultRowClassName: String,
      expected: Seq[(String, String)],
  )(implicit webDriver: WebDriverType) = {
    findAll(className(resultRowClassName)).toSeq.map(row => {
      val children = row.findAllChildElements(tagName("td"))
      children.map(c => c.text).toList match {
        case List(a, b) => (a, b)
        case _ => fail("Expected a list of 2 elements")
      }
    }) shouldBe expected
  }

  "A scan UI" should {
    "see expected rewards leaderboards" in { implicit env =>
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

        // TODO(#2930): consider de-hard-coding the expected values here somehow, e.g. by only checking them relative to each other
        clue("Compare app leaderboard values") {
          compareLeaderboardTable(
            "app-leaderboard-row",
            Seq((aliceValidatorWalletParty, "41.5 CC")),
          )
        }

        actAndCheck(
          "Go to validator leaderboard page in scan UI",
          go to "http://localhost:3006/validator-leaderboard",
        )(
          "Check validator leaderboard table and see entry",
          _ => {
            findAll(className("validator-leaderboard-row")).toSeq should have length 1
          },
        )

        clue("Compare validator leaderboard values") {
          compareLeaderboardTable(
            "validator-leaderboard-row",
            Seq((aliceValidatorWalletParty, "0.083 CC")),
          )
        }
      }
    }
  }
}
