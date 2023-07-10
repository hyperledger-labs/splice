package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{
  ConfigScheduleUtil,
  FrontendLoginUtil,
  TimeTestUtil,
  WalletTestUtil,
  DomainFeesTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.util.CNNodeUtil

import scala.jdk.CollectionConverters.*
import java.time.{Duration, Instant}

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
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
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
        p2pTransfer(aliceValidatorBackend, aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        p2pTransfer(
          aliceValidatorBackend,
          aliceValidatorWalletClient,
          bobWalletClient,
          bobUserParty,
          10.0,
        )
      })

      clue("Advance rounds to collect rewards") {
        Range(0, 5).foreach(_ => advanceRoundsByOneTick)
      }

      val aliceValidatorWalletParty = aliceValidatorWalletClient.userStatus().party

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
            Seq(s"${aliceValidatorWalletParty} 41.5 CC"),
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
            Seq(s"${aliceValidatorWalletParty} 0.083 CC"),
          )
        }
      }
    }

    "see expected current coin config" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to "http://localhost:3006")(
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

      clue("schedule a config change, and advance time for it to take effect") {
        val currentConfigSchedule = sv1ScanBackend.getCoinRules().contract.payload.configSchedule

        val ledgerNow = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
        val javaTomorrow = Instant.now().plusSeconds(86400) // one day later in seconds

        // Create two configs: the first scheduled one tick from now, so we can test changes to the coin config display itself
        // The second is scheduled one day past real-time, so we can test the "Next update at..." display, since the TimeBased tests start at
        // the beginning of the epoch, 1970, and thus wouldn't show up in the browser (which checks for future values relative to local time)
        val configSchedule =
          createConfigSchedule(
            currentConfigSchedule,
            (
              defaultTickDuration.asJava,
              mkUpdatedCoinConfig(
                currentConfigSchedule,
                tickDuration = defaultTickDuration,
                holdingFee = newHoldingFee,
              ),
            ),
            (
              Duration.between(ledgerNow.toInstant, javaTomorrow),
              mkUpdatedCoinConfig(
                currentConfigSchedule,
                tickDuration = defaultTickDuration,
                holdingFee = newHoldingFee,
              ),
            ),
          )
        setConfigSchedule(configSchedule)
        advanceRoundsByOneTick
        advanceRoundsByOneTick
      }

      clue("check new config change in the UI") {
        withFrontEnd("scan-ui") { implicit webDriver =>
          find(id("holding-fee")).value.text should matchText(
            s"${newHoldingFee} CC/Round"
          )

          // depending on timing, we might dip below 23 hours, 59 mins, 30 seconds which results in this string from date-fns
          find(id("next-config-update")).value.text should (equal("1 day") or equal(
            "about 24 hours"
          ))
        }
      }
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
          val trafficAmount = 1_000_000L
          buyExtraTraffic(
            aliceValidatorBackend,
            aliceValidatorWalletClient.list().coins,
            trafficAmount,
            env.environment.clock.now,
          )
          advanceRoundsByOneTick
          buyExtraTraffic(
            aliceValidatorBackend,
            aliceValidatorWalletClient.list().coins,
            trafficAmount,
            env.environment.clock.now,
          )
          buyExtraTraffic(
            bobValidatorBackend,
            bobValidatorWalletClient.list().coins,
            trafficAmount,
            env.environment.clock.now,
          )
          (0 to 4).foreach(_ => advanceRoundsByOneTick)
        },
      )(
        "Wait for round to close in scan",
        _ => sv1ScanBackend.getRoundOfLatestData()._1 shouldBe (firstRound + 1),
      )

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to "http://localhost:3006/domain-fees-leaderboard",
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
              s"${aliceValidatorWalletParty} 2 2000000 1 CC ${(firstRound + 1).toString}",
              s"${bobValidatorWalletParty} 1 1000000 0.5 CC ${(firstRound + 1).toString}",
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
        (0 to 4).foreach(_ => advanceRoundsByOneTick),
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
          go to "http://localhost:3006",
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
