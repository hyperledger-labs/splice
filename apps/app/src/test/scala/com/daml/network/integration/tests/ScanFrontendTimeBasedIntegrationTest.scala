package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

class ScanFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("scan-ui")
    with FrontendLoginUtil
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil
    with DomainFeesTestUtil {

  val coinPrice = 2

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withCoinPrice(coinPrice)

  def compareLeaderboardTable(
      resultRowClassName: String,
      expected: Seq[String],
  )(implicit webDriver: WebDriverType) = {
    findAll(className(resultRowClassName)).toSeq.map(seleniumText) shouldBe expected
  }

  "A scan UI" should {
    "see expected rewards leaderboards" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()

      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      grantFeaturedAppRight(aliceValidatorWalletClient)

      clue("Tap to get some coins") {
        aliceWalletClient.tap(500.0)
        aliceValidatorWalletClient.tap(100.0)
      }

      clue("Feature alice's validator and transfer some CC, to generate reward coupons")({
        p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      })

      clue("Advance rounds to collect rewards") {
        Range(0, 6).foreach(_ => advanceRoundsByOneTick)
      }

      val aliceValidatorWalletParty = aliceValidatorWalletClient.userStatus().party

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to app leaderboard page in scan UI",
          go to s"http://localhost:${scanUIPort}/app-leaderboard",
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
            Seq(s"${aliceValidatorWalletParty} 91.5 CC"),
          )
        }

        actAndCheck(
          "Go to validator leaderboard page in scan UI",
          go to s"http://localhost:${scanUIPort}/validator-leaderboard",
        )(
          "Check validator leaderboard table and see entry",
          _ => {
            findAll(className("validator-leaderboard-row")).toSeq should have length 1
          },
        )

        clue("Compare validator leaderboard values") {
          compareLeaderboardTable(
            "validator-leaderboard-row",
            Seq(s"${aliceValidatorWalletParty} 0.083 CC"),
          )
        }
      }
    }

    "see expected current and future coin configurations" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to s"http://localhost:${scanUIPort}")(
          "Check the initial coin config matches the defaults",
          _ => {
            find(id("coin-creation-fee")).value.text should matchText(
              s"${CNNodeUtil.defaultCreateFee.fee.doubleValue()} CC"
            )

            find(id("holding-fee")).value.text should matchText(
              s"${CNNodeUtil.defaultHoldingFee.rate} CC/Round"
            )

            find(id("lock-holder-fee")).value.text should matchText(
              s"${CNNodeUtil.defaultLockHolderFee.fee.doubleValue()} CC"
            )

            find(id("round-tick-duration")).value.text should matchText(
              // For some reason the `.toMinutes` method rounds down to 0
              s"${defaultTickDuration.duration.toSeconds / 60.toDouble} Minutes"
            )

            findAll(className("transfer-fee-row")).toList
              .map(_.text)
              .zip(CNNodeUtil.defaultTransferFee.steps.asScala.toList)
              .foreach({
                case (txFeeRow, defaultStep) => {
                  txFeeRow should include(defaultStep._1.setScale(0).toString)
                }
              })
          },
        )
      }

      val newHoldingFee = 0.1

      // Note that the ledger time is in 1970. It will however not change anything because
      // `sv1ScanBackend.getCoinRules().contract.payload.configSchedule`
      // is a contract such as it was written when it got accepted (e.g. like in 1970).
      // The values are not processed as of now, but the frontend does post-process
      // the Coin Rules contract to get the actual coin configurations (see getCoinConfigurationAsOfNow()).
      val ledgerNow = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
      val javaYesterday = Instant.now().minusSeconds(86400) // yesterday
      val javaTomorrow = Instant.now().plusSeconds(86400) // tomorrow

      actAndCheck(
        "schedule a coin configuration in which the last configuration's time was yesterday", {
          val currentConfigSchedule = sv1ScanBackend.getCoinRules().contract.payload.configSchedule

          val configSchedule =
            createConfigSchedule(
              currentConfigSchedule,
              (
                Duration.between(ledgerNow.toInstant, javaYesterday),
                mkUpdatedCoinConfig(
                  currentConfigSchedule,
                  tickDuration = defaultTickDuration,
                  holdingFee = 2 * newHoldingFee,
                ),
              ),
            )

          setFutureConfigSchedule(configSchedule)
        },
      )(
        "check that the new config has changed in the UI",
        _ => {
          withFrontEnd("scan-ui") { implicit webDriver =>
            find(id("holding-fee")).value.text should matchText(
              s"${2 * newHoldingFee} CC/Round"
            )

            find(id("next-config-update-time")).value.text should equal(
              "No currently scheduled configuration changes"
            )
          }
        },
      )

      actAndCheck(
        "schedule a coin configuration in which the configuration's time is tomorrow", {
          val currentConfigSchedule = sv1ScanBackend.getCoinRules().contract.payload.configSchedule

          val configSchedule =
            createConfigSchedule(
              currentConfigSchedule,
              (
                Duration.between(ledgerNow.toInstant, javaTomorrow),
                mkUpdatedCoinConfig(
                  currentConfigSchedule,
                  tickDuration = defaultTickDuration,
                  holdingFee = 3 * newHoldingFee,
                ),
              ),
            )

          setFutureConfigSchedule(configSchedule)
        },
      )(
        "check that the next change will be applied in 24 hours",
        _ => {
          withFrontEnd("scan-ui") { implicit webDriver =>
            find(id("holding-fee")).value.text should matchText(
              s"${sv1ScanBackend.getCoinRules().contract.payload.configSchedule.initialValue.transferConfig.holdingFee.rate} CC/Round"
            )

            find(id("next-config-update-time")).value.text should equal("1 day").or(
              equal("About 24 hours")
            )
          }
        },
      )

    }

    "See expected domain fees leaderboard" in { implicit env =>
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)
      val aliceValidatorWalletParty = aliceValidatorWalletClient.userStatus().party
      val bobValidatorWalletParty = bobValidatorWalletClient.userStatus().party
      val firstRound = sv1ScanBackend
        .getLatestOpenMiningRound(env.environment.clock.now)
        .contract
        .payload
        .round
        .number
      actAndCheck(
        "Buy some traffic in rounds 1&2, and advance enough rounds for round 2 to close", {
          aliceValidatorWalletClient.tap(100.0)
          bobValidatorWalletClient.tap(100.0)
          val trafficAmount = sv1ScanBackend
            .getCoinConfigAsOf(env.environment.clock.now)
            .globalDomain
            .fees
            .minTopupAmount
          buyMemberTraffic(
            aliceValidatorBackend,
            trafficAmount,
            env.environment.clock.now,
          )
          advanceRoundsByOneTick
          buyMemberTraffic(
            aliceValidatorBackend,
            trafficAmount,
            env.environment.clock.now,
          )
          buyMemberTraffic(
            bobValidatorBackend,
            trafficAmount,
            env.environment.clock.now,
          )
          (0 to 5).foreach(_ => advanceRoundsByOneTick)
        },
      )(
        "Wait for round to close in scan",
        _ => sv1ScanBackend.getRoundOfLatestData()._1 shouldBe (firstRound + 1),
      )

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}/domain-fees-leaderboard",
        )(
          "See both entries in the leaderboard",
          _ => {
            findAll(className("domain-fees-leaderboard-row")).toSeq should have length 2
          },
        )

        clue("Compare domain fees leaderboard values") {

          compareLeaderboardTable(
            "domain-fees-leaderboard-row",
            Seq(
              s"${aliceValidatorWalletParty} 2 20000000 10 CC ${(firstRound + 1).toString}",
              s"${bobValidatorWalletParty} 1 10000000 5 CC ${(firstRound + 1).toString}",
            ),
          )
        }
      }
    }

    "See expected total coin balance" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val firstRound = sv1ScanBackend
        .getLatestOpenMiningRound(env.environment.clock.now)
        .contract
        .payload
        .round
        .number

      advanceRoundsByOneTick
      aliceWalletClient.tap(100.0)

      actAndCheck(
        "Advance rounds",
        (0 to 5).foreach(_ => advanceRoundsByOneTick),
      )(
        "Wait for round to close in scan",
        _ => sv1ScanBackend.getRoundOfLatestData()._1 shouldBe (firstRound + 1),
      )
      // We do not check the backend computation here, nor do we want to rely on the exact coin balance created in other tests,
      // so here we simply test that:
      // The total balance increased as a result of our tap by the tap amount minus some amount to account for holding fees
      // The frontend shows the balance from the backend
      sv1ScanBackend.getTotalCoinBalance(firstRound + 1) should
        (be > (sv1ScanBackend.getTotalCoinBalance(firstRound) + 99.0))

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}",
        )(
          "See valid total coin balance",
          _ => {
            screenshot()
            seleniumText(find(id("total-coin-balance-cc"))) should matchText(
              s"${sv1ScanBackend.getTotalCoinBalance(firstRound + 1)} CC"
            )
          },
        )
      }
    }
  }
}
