package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.JsonObject
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.PackageIdResolver.HasAmuletRules
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import org.openqa.selenium.By
import spray.json.DefaultJsonProtocol.StringJsonFormat

import java.time.{Duration, Instant}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class ScanFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("scan-ui")
    with AmuletConfigUtil
    with WalletTestUtil
    with WalletFrontendTestUtil
    with TimeTestUtil
    with SynchronizerFeesTestUtil
    with TriggerTestUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil
    with SvTestUtil {

  val amuletPrice = 2

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )

  def compareLeaderboardTable(
      resultRowClassName: String,
      expected: Seq[String],
  )(implicit webDriver: WebDriverType) = {
    findAll(className(resultRowClassName)).toSeq.map(seleniumText) shouldBe expected
  }

  private def stripTrailingZeros(num: BigDecimal) = BigDecimal(num.bigDecimal.stripTrailingZeros())

  "A scan UI" should {
    "see expected rewards leaderboards" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()

      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Note this has no effect on the wallet app, as it is not a featured app and thus does not use the featured app
      // right in the transfer contexts of its submissions. We leave it here to test that it has no effect.
      grantFeaturedAppRight(aliceValidatorWalletClient)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(500.0)
        aliceValidatorWalletClient.tap(100.0)
      }

      clue(
        s"Feature alice's validator and transfer some Amulet, to generate reward coupons"
      )({
        p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0)
      })

      clue("Advance rounds to collect rewards") {
        Range(0, 6).foreach(_ => advanceRoundsToNextRoundOpening)
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

        // TODO(DACH-NY/canton-network-node#2930): consider de-hard-coding the expected values here somehow, e.g. by only checking them relative to each other
        clue("Compare app leaderboard values") {
          compareLeaderboardTable(
            "app-leaderboard-row",
            Seq(s"${aliceValidatorWalletParty} 0.249 $amuletNameAcronym"),
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
            Seq(s"${aliceValidatorWalletParty} 0.083 $amuletNameAcronym"),
          )
        }
      }
    }

    "see recent activity in infinte scroll" in { implicit env =>
      val (aliceUserParty, _) = onboardAliceAndBob()

      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      clue("Tap amulets for Alice to create transactions") {
        (1 to 5).foreach { i =>
          aliceWalletClient.tap(i * 100)
        }
      }

      clue("Bob transfers to alice") {
        bobWalletClient.tap(100.0)
        (1 to 5).foreach { i =>
          p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, i)
        }
        advanceRoundsToNextRoundOpening
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to recent activity page in scan UI",
          go to s"http://localhost:${scanUIPort}/recent-activity",
        )(
          "Check the recent activity has more items than a single page size",
          _ => {
            // frontend pagination is 10 items per page so we should see more than the first page on load
            // 5 taps and 5 transfers plus some automation should give us more than 10 items
            findAll(className("activity-row")).length should be > 10
          },
        )
      }
    }

    "see DSO and Amulet Info" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan homepage and switch to the Network Info Tab", {
            go to s"http://localhost:${scanUIPort}"
            eventuallyClickOn(id("navlink-/dso"))
          },
        )(
          "The tabs 'DSO Info' and 'Amulet Info' are visible",
          _ => {
            findAll(id("information-tab-dso-info")).length shouldBe 1
            findAll(id("information-tab-amulet-info")).length shouldBe 1
          },
        )

        actAndCheck(
          "Click on DSO Info", {
            eventuallyClickOn(id("information-tab-dso-info"))
          },
        )(
          "The DSO info is visible",
          _ => {
            val dsoInfo = sv1ScanBackend.getDsoInfo()
            val contract = find(id("dso-rules-information"))
              .map(_.text)
              .map { text =>
                val json =
                  io.circe.parser.parse(text).valueOrFail(s"Couldn't parse JSON from $text")
                json.hcursor
                  .downField("dsoRules")
                  .downField("payload")
                  .as[JsonObject]
                  .valueOrFail(s"Couldn't find dsoRules in $text")
              }
            contract should be(
              Some(
                dsoInfo.dsoRules.contract.payload.asObject
                  .valueOrFail("This is definitely an object.")
              )
            )
          },
        )

        actAndCheck(
          "Click on Amulet Info", {
            eventuallyClickOn(id("information-tab-amulet-info"))
          },
        )(
          "The Amulet info is visible",
          _ => {
            val amuletRules = sv1ScanBackend
              .getAmuletRules()
              .contract
              .toHttp
              .payload
              .asObject
              .valueOrFail("This is definitely an object.")
            find(id("amulet-rules-information"))
              .map(_.text)
              .map(json =>
                io.circe.parser
                  .parse(json)
                  .valueOrFail(s"Couldn't parse JSON from $json")
                  .asObject
                  .valueOrFail(s"Could not decode $json as Amulet rules.")
              ) should be(Some(amuletRules))
          },
        )
      }
    }

    "see expected current and future amulet configurations" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to s"http://localhost:${scanUIPort}")(
          "Check the initial amulet config matches the defaults",
          _ => {
            find(id("base-transfer-fee")).value.text should matchText(
              s"${SpliceUtil.defaultCreateFee.fee.doubleValue()} USD"
            )

            find(id("holding-fee")).value.text should matchText(
              s"${SpliceUtil.defaultHoldingFee.rate} USD/Round"
            )

            find(id("lock-holder-fee")).value.text should matchText(
              s"${SpliceUtil.defaultLockHolderFee.fee.doubleValue()} USD"
            )

            find(id("round-tick-duration")).value.text should matchText {
              // the `.toMinutes` method rounds down to 0
              val minutes = BigDecimal(defaultTickDuration.duration.toSeconds) / 60
              s"${minutes.bigDecimal.stripTrailingZeros.toPlainString} Minutes"
            }

            findAll(className("transfer-fee-row")).toList
              .map(_.text)
              .zip(SpliceUtil.defaultTransferFee.steps.asScala.toList)
              .foreach({
                case (txFeeRow, defaultStep) => {
                  txFeeRow should include(defaultStep._1.setScale(0).toString)
                }
              })
          },
        )
      }

      // Note that the ledger time is in 1970. It will however not change anything because
      // `sv1ScanBackend.getAmuletRules().contract.payload.configSchedule`
      // is a contract such as it was written when it got accepted (e.g. like in 1970).
      // The values are not processed as of now, but the frontend does post-process
      // the Amulet Rules contract to get the actual amulet configurations (see getAmuletConfigurationAsOfNow()).
      val ledgerNow = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
      val javaTomorrow = Instant.now().plusSeconds(86400) // tomorrow

      val newHoldingFee = 0.1

      actAndCheck(
        "add an amulet configuration effective from tomorrow", {
          val amuletRules =
            sv1ScanBackend.getAmuletRules().contract

          val configs = Seq(
            (
              Some(Duration.between(ledgerNow.toInstant, javaTomorrow)), // effective in 1 day
              mkUpdatedAmuletConfig(
                amuletRules,
                defaultTickDuration,
                holdingFee = 3 * newHoldingFee,
              ),
              amuletRules.payload.configSchedule.initialValue,
            )
          )
          setAmuletConfig(configs)
          advanceTime(Duration.ofSeconds(70))
        },
      )(
        "check that the next change will be applied in 24 hours",
        _ => {
          withFrontEnd("scan-ui") { implicit webDriver =>
            find(id("holding-fee")).value.text should matchText(
              s"${sv1ScanBackend.getAmuletRules().contract.payload.configSchedule.initialValue.transferConfig.holdingFee.rate} USD/Round"
            )

            find(id("next-config-update-time")).value.text should equal("1 day").or(
              equal("About 24 hours")
            )
          }
        },
      )

    }

    "see open rounds" in { implicit env =>
      def fmtTime(i: java.time.Instant) = {
        import java.time.*
        format.DateTimeFormatter
          .ofPattern("yyyy-MM-dd HH:mm")
          .format(LocalDateTime.ofInstant(i, ZoneOffset.UTC))
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck("Go to Scan UI main page", go to s"http://localhost:${scanUIPort}")(
          "Check that open rounds match scan backend",
          _ => {
            val openRounds = sv1ScanBackend
              .getOpenAndIssuingMiningRounds()
              ._1
              .map(_.payload)
              .sortBy(_.round.number)
            openRounds should not be empty
            val shownRounds = findAll(className("open-mining-round-row")).toList
            shownRounds should have size openRounds.size.toLong
            forEvery(shownRounds zip openRounds) { case (shownRound, openRound) =>
              def rt(n: String) = shownRound.childElement(className(n)).text
              rt("round-number") should matchText(openRound.round.number.toString)
              rt("round-opens-at") should matchText(fmtTime(openRound.opensAt))
              rt("round-target-closes-at") should matchText(fmtTime(openRound.targetClosesAt))
            }
          },
        )
      }
    }

    "See expected synchronizer fees leaderboard" in { implicit env =>
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
      val synchronizerFeesConfig = sv1ScanBackend
        .getAmuletConfigAsOf(env.environment.clock.now)
        .decentralizedSynchronizer
        .fees
      val trafficAmount = synchronizerFeesConfig.minTopupAmount
      val (_, trafficCostCc) = SpliceUtil.synchronizerFees(
        trafficAmount,
        synchronizerFeesConfig.extraTrafficPrice,
        amuletPrice,
      )

      actAndCheck(
        "Buy some traffic in rounds 1&2, and advance enough rounds for round 2 to close", {
          aliceValidatorWalletClient.tap(100.0)
          bobValidatorWalletClient.tap(100.0)
          buyMemberTraffic(
            aliceValidatorBackend,
            trafficAmount,
            env.environment.clock.now,
          )
          advanceRoundsToNextRoundOpening
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
          (1 to 5).foreach(_ => advanceRoundsToNextRoundOpening)
        },
      )(
        "Wait for round to close in scan",
        _ => sv1ScanBackend.getRoundOfLatestData()._1 shouldBe (firstRound + 1),
      )

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}/synchronizer-fees-leaderboard",
        )(
          "See both entries in the leaderboard",
          _ => {
            findAll(className("synchronizer-fees-leaderboard-row")).toSeq should have length 2
          },
        )

        clue("Compare synchronizer fees leaderboard values") {

          compareLeaderboardTable(
            "synchronizer-fees-leaderboard-row",
            Seq(
              s"${aliceValidatorWalletParty} 2 ${2 * trafficAmount} ${stripTrailingZeros(
                  2 * trafficCostCc
                )} $amuletNameAcronym ${(firstRound + 1).toString}",
              s"${bobValidatorWalletParty} 1 ${trafficAmount} ${stripTrailingZeros(trafficCostCc)} $amuletNameAcronym ${(firstRound + 1).toString}",
            ),
          )
        }
      }
    }

    "See expected total amulet balance" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val firstRound = sv1ScanBackend
        .getLatestOpenMiningRound(env.environment.clock.now)
        .contract
        .payload
        .round
        .number

      advanceRoundsToNextRoundOpening
      aliceWalletClient.tap(100.0)

      actAndCheck(
        "Advance rounds",
        (1 to 5).foreach(_ => advanceRoundsToNextRoundOpening),
      )(
        "Wait for round to close in scan",
        _ => sv1ScanBackend.getRoundOfLatestData()._1 shouldBe (firstRound + 1),
      )
      // We do not check the backend computation here, nor do we want to rely on the exact amulet balance created in other tests,
      // so here we simply test that:
      // The total balance increased as a result of our tap by the tap amount minus some amount to account for holding fees
      // The frontend shows the balance from the backend
      sv1ScanBackend
        .getTotalAmuletBalance(firstRound + 1)
        .valueOrFail("Amulet balance not yet computed") should
        (be > (sv1ScanBackend
          .getTotalAmuletBalance(firstRound)
          .valueOrFail("Amulet balance not yet computed") + 99.0))

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}",
        )(
          "See valid total amulet balance",
          _ => {
            val totalText = seleniumText(find(id("total-amulet-balance-amulet")))
            val totalBalance = BigDecimal(
              sv1ScanBackend
                .lookupInstrument("Amulet")
                .flatMap(_.totalSupply)
                .valueOrFail("Amulet balance not yet computed")
            )
            parseAmountText(totalText, amuletNameAcronym) shouldBe totalBalance
            val totalUsdText = seleniumText(find(id("total-amulet-balance-usd")))
            val totalUsdBalance = totalBalance * amuletPrice
            parseAmountText(totalUsdText, "USD") shouldBe totalUsdBalance
          },
        )
      }
    }

    "see the validator faucet leaderboard" in { implicit env =>
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)
      val aliceValidatorWalletParty = aliceValidatorWalletClient.userStatus().party
      val bobValidatorWalletParty = bobValidatorWalletClient.userStatus().party

      val openRounds = sv1ScanBackend
        .getOpenAndIssuingMiningRounds()
        ._1
        .filter(_.payload.opensAt.isBefore(env.environment.clock.now.toInstant))

      clue("Alice starts claiming Faucet coupons") {
        setTriggersWithin(
          Seq.empty,
          Seq(aliceValidatorBackend.validatorAutomation.trigger[ReceiveFaucetCouponTrigger]),
        ) {
          eventually() {
            aliceValidatorWalletClient
              .listValidatorLivenessActivityRecords() should have length openRounds.length.toLong
          }
        }
      }

      openRounds.foreach(_ => advanceRoundsToNextRoundOpening)
      advanceRoundsToNextRoundOpening
      eventually() {
        aliceValidatorWalletClient.listValidatorLivenessActivityRecords() should have length 0
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI for validator faucets leaderboard",
          go to s"http://localhost:${scanUIPort}/validator-faucets-leaderboard",
        )(
          "See the entry for the faucet in the leaderboard",
          _ => {
            val firstCollectedInRound = openRounds
              .minByOption(_.contract.payload.round.number)
              .toList
              .loneElement
              .payload
              .round
              .number
            val lastCollectedInRound =
              openRounds
                .maxByOption(_.contract.payload.round.number)
                .toList
                .loneElement
                .payload
                .round
                .number
            val actual =
              findAll(className("validator-faucets-leaderboard-row")).toSeq.map(seleniumText)
            actual should have length 4
            actual.head should be(
              s"${aliceValidatorWalletParty} ${openRounds.size} 0 $firstCollectedInRound $lastCollectedInRound"
            )
            actual.tail should contain theSameElementsAs Seq(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              splitwellValidatorBackend.getValidatorPartyId().toProtoPrimitive,
              bobValidatorWalletParty,
            ).map(party => s"$party 0 0 0 0")
          },
        )
      }
    }

    "see the votes" in { implicit env =>
      val dsoInfo = sv1Backend.getDsoInfo()
      val amuletRules = dsoInfo.amuletRules

      val baseAmuletConfig = amuletRules.payload.configSchedule.initialValue

      val newMaxNumInputs = baseAmuletConfig.transferConfig.maxNumInputs.toInt + 1
      val newAmuletConfig = mkUpdatedAmuletConfig(
        amuletRules.contract,
        NonNegativeFiniteDuration.tryFromDuration(
          scala.concurrent.duration.Duration.fromNanos(
            baseAmuletConfig.tickDuration.microseconds * 1000
          )
        ),
        newMaxNumInputs,
      )

      val mockVoteAction = new ARC_AmuletRules(
        new CRARC_SetConfig(
          new AmuletRules_SetConfig(
            newAmuletConfig,
            baseAmuletConfig,
          )
        )
      )

      // only 1 SV in this test suite, so the vote is approved
      sv1Backend.createVoteRequest(
        dsoInfo.svParty.toProtoPrimitive,
        mockVoteAction,
        "url",
        "Testing Testingaton",
        dsoInfo.dsoRules.payload.config.voteRequestTimeout,
        None,
      )

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI for votes",
          go to s"http://localhost:$scanUIPort/governance",
        )(
          "See the vote as executed",
          _ => {
            closeVoteModalsIfOpen

            eventuallyClickOn(id("tab-panel-executed"))
            val rows = getAllVoteRows("sv-vote-results-executed-table-body")

            forExactly(1, rows) { reviewButton =>
              closeVoteModalsIfOpen
              reviewButton.underlying.click()

              // TODO(#934): needs to be changed by using parseAmuletConfigValue() once the diff exists for the first change
              try {
                val newScheduleItem = webDriver.findElement(By.id("accordion-details"))
                val json = newScheduleItem.findElement(By.tagName("pre")).getText
                spray.json
                  .JsonParser(json)
                  .asJsObject("transferConfig")
                  .fields("transferConfig")
                  .asJsObject
                  .fields("maxNumInputs")
                  .convertTo[String] should be(newMaxNumInputs.toString)
              } catch {
                case _: NoSuchElementException => false
              }
            }
          },
        )
      }
    }

    "see amulet price votes" in { implicit env =>
      clue("SVs update amulet prices") {
        eventuallySucceeds() {
          sv1Backend.updateAmuletPriceVote(BigDecimal(1.11))
        }
      }

      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to scan UI homepage",
          go to s"http://localhost:${scanUIPort}",
        )(
          "Switch to the Amulet Prices tab",
          _ => {
            inside(find(id("navlink-/amulet-price-votes"))) { case Some(navlink) =>
              navlink.underlying.click()
            }
            val amuletPriceRows = findAll(className("amulet-price-table-row")).toList

            amuletPriceRows.size shouldBe 1

            amuletPriceShouldMatch(amuletPriceRows, sv1Backend.getDsoInfo().svParty, s"1.11 USD")
          },
        )

      }
    }

    "see the validator licenses" in { implicit env =>
      withFrontEnd("scan-ui") { implicit webDriver =>
        actAndCheck(
          "Go to Scan UI main page",
          go to s"http://localhost:${scanUIPort}",
        )(
          "Switch to the validator licenses tab",
          _ => {
            inside(find(id("navlink-/validator-licenses"))) { case Some(navlink) =>
              navlink.underlying.click()
            }
            // make sure that seed licenses are rendered in the UI before proceeding to mitigate flakeyness
            getLicensesTableRows.size shouldBe >(0)
          },
        )

        val licenseRows = getLicensesTableRows
        val newValidatorParty = allocateRandomSvParty("validatorX")
        val newSecret = sv1Backend.devNetOnboardValidatorPrepare()

        actAndCheck(
          "onboard new validator using the secret",
          sv1Backend.onboardValidator(
            newValidatorParty,
            newSecret,
            s"${newValidatorParty.uid.identifier}@example.com",
          ),
        )(
          "a new validator row is added",
          _ => {
            checkValidatorLicenseRow(
              licenseRows.size.toLong,
              sv1Backend.getDsoInfo().svParty,
              newValidatorParty,
            )
          },
        )
      }
    }

  }

  private def amuletPriceShouldMatch(
      rows: Seq[Element],
      svParty: PartyId,
      amuletPrice: String,
  ) = {
    forExactly(1, rows) { row =>
      seleniumText(row.childElement(className("sv-party"))) shouldBe svParty.toProtoPrimitive
      row.childElement(className("amulet-price")).text shouldBe amuletPrice
    }
  }
}

case class HasAmuletRulesWrapper(amuletRules: Contract[AmuletRules.ContractId, AmuletRules])
    extends HasAmuletRules {
  override def getAmuletRules()(implicit
      tc: TraceContext
  ): Future[Contract[AmuletRules.ContractId, AmuletRules]] =
    Future.successful(amuletRules)
}
