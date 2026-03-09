package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredDevelopmentFundCouponTrigger,
  MergeUnclaimedDevelopmentFundCouponsTrigger,
}
import org.lfdecentralizedtrust.splice.util.{
  AmuletConfigSchedule,
  FrontendLoginUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.openqa.selenium.WebDriver

import java.time.{Duration, Instant}
import java.util.Optional
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

class DevelopmentFundFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTest("alice", "bob", "sv1")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override protected def runUpdateHistorySanityCheck: Boolean = false

  override protected def login(
      port: Int,
      ledgerApiUser: String,
      hostname: String = "localhost",
  )(implicit webDriver: WebDriver) = {
    go to s"http://$hostname:$port"
    loginOnCurrentPage(port, ledgerApiUser, hostname)
  }

  private def formatDateTimeForUI(instant: Instant): String = {
    val formatter = java.time.format.DateTimeFormatter
      .ofPattern("MM/dd/yyyy hh:mm a", java.util.Locale.US)
    formatter.format(instant.atZone(java.time.ZoneOffset.UTC))
  }

  private def latestTime(implicit env: SpliceTestConsoleEnvironment): Instant = {
    val ledgerTime = getLedgerTime.toInstant
    val wallClock = Instant.now()
    if (wallClock.isAfter(ledgerTime)) wallClock else ledgerTime
  }

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[MergeUnclaimedDevelopmentFundCouponsTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.withDevelopmentFundPercentage(0.05)(config)
      )

  "Development Fund - Happy Path (DFM changes)" should {

    "handle DFM transition correctly" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      clue("Set alice (wallet user) as initial DFM via governance vote") {
        changeDevelopmentFundManager(aliceParty)
      }

      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val bobDamlUser = bobWalletClient.config.ledgerApiUser

      val bobCollectRewardsTrigger =
        bobValidatorBackend
          .userWalletAutomation(bobDamlUser)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]

      val expiredDevelopmentFundCouponTriggers =
        activeSvs.map(
          _.dsoDelegateBasedAutomation.trigger[ExpiredDevelopmentFundCouponTrigger]
        )

      // ===================================================================
      // Section: Generate unclaimed coupons by advancing rounds
      // ===================================================================

      clue("Advance rounds to generate unclaimed development fund coupons") {
        aliceWalletClient.tap(100.0)
        Range(0, 6).foreach(_ => {
          advanceTime(tickDurationWithBuffer)
          advanceRoundsByOneTickViaAutomation()
        })
      }

      val futureExpiresAtFormatted =
        formatDateTimeForUI(latestTime.plus(Duration.ofDays(365 * 30)))

      // ===================================================================
      // Section: Create coupons for user_1, change DFM, and verify transition
      // ===================================================================

      setTriggersWithin(
        triggersToPauseAtStart =
          Seq(bobCollectRewardsTrigger) ++ expiredDevelopmentFundCouponTriggers
      ) {
        withFrontEnd("alice") { implicit webDriver =>
          browseToAliceWallet(aliceDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))
          waitForQuery(id("development-fund-allocation-beneficiary"))

          clue("Create coupon 1 (to be claimed)") {
            actAndCheck(
              "user_1 allocates coupon 1", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  futureExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon 1 - for claiming"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon 1 is allocated",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1
                }
              },
            )

            bobCollectRewardsTrigger.resume()
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            bobCollectRewardsTrigger.pause().futureValue

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          clue("Create coupon 2 (to be withdrawn)") {
            actAndCheck(
              "user_1 allocates coupon 2", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  futureExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon 2 - for withdrawal"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon 2 is allocated",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("#active-coupons-table tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true
                }
              },
            )

            actAndCheck(
              "user_1 withdraws coupon 2", {
                eventuallyClickOn(
                  cssSelector("#active-coupons-table tbody tr td:last-child button")
                )
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector(
                    "[role='dialog'] textarea[placeholder='Enter the reason for withdrawal']"
                  )
                )
                reasonField.click()
                reasonField.sendKeys("Withdrawal reason")
                eventuallyClickOn(cssSelector("[role='dialog'] button.MuiButton-contained"))
              },
            )(
              "Coupon 2 is withdrawn",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
                }
              },
            )

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          clue("Create coupon 3 (to remain active)") {
            actAndCheck(
              "user_1 allocates coupon 3", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  futureExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon 3 - stays active"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon 3 is allocated",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1
                }
              },
            )
          }
        }

        // ===================================================================
        // Section: Change DFM from user_1 to user_2
        // ===================================================================

        clue("Change DFM from user_1 to user_2 via governance") {
          changeDevelopmentFundManager(bobParty)

          eventually() {
            val amuletRules = sv1ScanBackend.getAmuletRules()
            val currentDfm =
              amuletRules.payload.configSchedule.initialValue.optDevelopmentFundManager
                .map(PartyId.tryFromProtoPrimitive)
                .toScala
            currentDfm shouldBe Some(bobParty)
          }

          // Advance sim time to expire the amulet rules cache (TTL=1s) on validator scan proxies
          advanceTime(Duration.ofSeconds(2))
        }

        // ===================================================================
        // Section: As user_1 (former DFM)
        // ===================================================================

        clue("As user_1 (former DFM) - verify backend state") {
          clue("Check: user_1 has 1 active coupon") {
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1
            }
          }

          clue("Action: user_1 withdraws the active coupon via backend") {
            val activeCoupons = aliceWalletClient.listActiveDevelopmentFundCoupons()
            activeCoupons should have size 1
            aliceWalletClient.withdrawDevelopmentFundCoupon(
              activeCoupons.head.contractId,
              "Final withdrawal",
            )
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
          }
        }

        // ===================================================================
        // Section: As user_2 (new DFM)
        // ===================================================================

        clue("As user_2 (new DFM)") {
          withFrontEnd("bob") { implicit webDriver =>
            browseToBobWallet(bobDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))

            clue("Check: user_2 sees DFM info alert (user_2 is the DFM)") {
              eventually() {
                waitForQuery(id("development-fund-allocation-beneficiary"))
                find(className("MuiAlert-standardWarning")) shouldBe empty
                find(className("MuiAlert-standardInfo")).isDefined shouldBe true
              }
            }

            clue("Check: user_2's Active List is empty") {
              eventually() {
                val emptyStateCell = find(
                  cssSelector("#active-coupons-table tbody tr td[colspan='6']")
                )
                emptyStateCell.isDefined shouldBe true
                emptyStateCell.value.text should include("No development fund allocations found")
              }
            }

            clue("Check: user_2's History List is empty") {
              eventually() {
                val emptyStateCell =
                  find(cssSelector("#coupon-history-table tbody tr td[colspan='6']"))
                emptyStateCell.isDefined shouldBe true
                emptyStateCell.value.text should include("No history events found")
              }
            }

            clue("Check: user_2 can allocate new coupons") {
              actAndCheck(
                "user_2 allocates a new coupon", {
                  setAnsField(
                    textField(id("development-fund-allocation-beneficiary")),
                    aliceParty.toProtoPrimitive,
                    aliceParty.toProtoPrimitive,
                  )
                  eventuallyClickOn(id("development-fund-allocation-amount"))
                  textField(id("development-fund-allocation-amount")).underlying.clear()
                  textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
                  waitForQuery(id("development-fund-allocation-expires-at"))
                  setDateTimeWithoutScroll(
                    "development-fund-allocation-expires-at",
                    futureExpiresAtFormatted,
                  )
                  eventuallyClickOn(id("development-fund-allocation-reason"))
                  textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                    "First coupon from new DFM"
                  )
                  eventuallyClickOn(id("development-fund-allocation-submit-button"))
                },
              )(
                "Coupon is allocated successfully",
                _ => {
                  eventually() {
                    val coupons = bobWalletClient.listActiveDevelopmentFundCoupons()
                    coupons should have size 1
                    coupons.head.payload.fundManager shouldBe bobParty.toProtoPrimitive
                  }
                },
              )
            }
          }
        }
      }
    }
  }

  "Development Fund - Happy Path (normal user)" should {

    "show appropriate restrictions for non-DFM users" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      clue("Set alice (wallet user) as DFM via governance vote") {
        changeDevelopmentFundManager(aliceParty)
      }

      val sv1DamlUser = sv1WalletClient.config.ledgerApiUser

      // ===================================================================
      // As sv1 (normal user - not DFM, no coupons)
      // ===================================================================

      clue("As sv1 (non-DFM user)") {
        clue("Check via API: sv1's Active List is empty") {
          sv1WalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
        }

        withFrontEnd("sv1") { implicit webDriver =>
          browseToSv1Wallet(sv1DamlUser)
          eventuallyClickOn(id("navlink-development-fund"))

          clue("Check: Warning text is shown for non-DFM user") {
            eventually() {
              find(className("MuiAlert-standardWarning")).isDefined shouldBe true
              val alertText = find(className("MuiAlert-standardWarning")).value.text
              alertText should include("not the development fund manager")
            }
          }

          clue("Check: Allocation form is disabled (cannot allocate)") {
            eventually() {
              find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
            }
          }

          clue("Check: Active List is empty") {
            eventually() {
              val emptyStateCell = find(
                cssSelector("#active-coupons-table tbody tr td[colspan='6']")
              )
              emptyStateCell.isDefined shouldBe true
              emptyStateCell.value.text should include("No development fund allocations found")
            }
          }

          clue("Check: History List is empty") {
            eventually() {
              val emptyStateCell =
                find(cssSelector("#coupon-history-table tbody tr td[colspan='6']"))
              emptyStateCell.isDefined shouldBe true
              emptyStateCell.value.text should include("No history events found")
            }
          }
        }
      }
    }
  }

  "Development Fund - Happy Path (DFM doesn't change)" should {

    "complete full lifecycle via UI: allocation, withdrawal, claiming, and expiring" in {
      implicit env =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

        clue("Set alice (wallet user) as initial DFM via governance vote") {
          changeDevelopmentFundManager(aliceParty)
        }

        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val bobDamlUser = bobWalletClient.config.ledgerApiUser

        val bobCollectRewardsTrigger =
          bobValidatorBackend
            .userWalletAutomation(bobDamlUser)
            .futureValue
            .trigger[CollectRewardsAndMergeAmuletsTrigger]

        val expiredDevelopmentFundCouponTriggers =
          activeSvs.map(
            _.dsoDelegateBasedAutomation.trigger[ExpiredDevelopmentFundCouponTrigger]
          )

        // ===================================================================
        // Section: Generate unclaimed coupons by advancing rounds
        // ===================================================================

        clue("Advance rounds to generate unclaimed development fund coupons") {
          aliceWalletClient.tap(100.0)
          Range(0, 6).foreach(_ => {
            advanceTime(tickDurationWithBuffer)
            advanceRoundsByOneTickViaAutomation()
          })
        }

        val futureExpiresAtFormatted =
          formatDateTimeForUI(latestTime.plus(Duration.ofDays(365 * 30)))

        // ===================================================================
        // Section: Allocation via UI
        // ===================================================================

        setTriggersWithin(
          triggersToPauseAtStart =
            Seq(bobCollectRewardsTrigger) ++ expiredDevelopmentFundCouponTriggers
        ) {
          withFrontEnd("alice") { implicit webDriver =>
            actAndCheck(
              "Alice (DFM) logs in and navigates to Development Fund page", {
                browseToAliceWallet(aliceDamlUser)
                eventuallyClickOn(id("navlink-development-fund"))
              },
            )(
              "Alice sees the Development Fund page with DFM info alert",
              _ => {
                find(className("MuiAlert-standardWarning")) shouldBe empty
                find(className("MuiAlert-standardInfo")).isDefined shouldBe true
                waitForQuery(id("development-fund-allocation-beneficiary"))
                waitForQuery(id("unclaimed-total-amount"))
              },
            )

            val allocationAmount = BigDecimal(10)
            val allocationReason = "Test allocation for Bob"

            actAndCheck(
              "Alice allocates a coupon to Bob via UI", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys(
                  allocationAmount.toString
                )
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  futureExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying
                  .sendKeys(allocationReason)
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and shown in active list with correct fields",
              _ => {
                eventually() {
                  val backendTotal = sv1ScanBackend
                    .listUnclaimedDevelopmentFundCoupons()
                    .map(c => BigDecimal(c.contract.payload.amount))
                    .sum
                  val displayed =
                    parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
                  displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)

                  val rows = findAll(cssSelector("#active-coupons-table tbody tr")).toSeq
                  rows should have size 1
                  val rowText = rows.head.text
                  rowText should include("10.0000")
                  rowText should include(allocationReason)
                }
              },
            )

            // ===================================================================
            // Section: Withdrawal via UI
            // ===================================================================

            actAndCheck(
              "Alice withdraws the coupon via UI", {
                eventuallyClickOn(
                  cssSelector("#active-coupons-table tbody tr td:last-child button")
                )
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector(
                    "[role='dialog'] textarea[placeholder='Enter the reason for withdrawal']"
                  )
                )
                reasonField.click()
                reasonField.sendKeys("Test withdrawal reason")
                eventuallyClickOn(cssSelector("[role='dialog'] button.MuiButton-contained"))
              },
            )(
              "Coupon is withdrawn, removed from active list, unclaimed total increased, and shown in History as withdrawn",
              _ => {
                eventually() {
                  val emptyStateCell = find(
                    cssSelector("#active-coupons-table tbody tr td[colspan='6']")
                  )
                  emptyStateCell.isDefined shouldBe true
                  emptyStateCell.value.text should include("No development fund allocations found")

                  val backendTotal = sv1ScanBackend
                    .listUnclaimedDevelopmentFundCoupons()
                    .map(c => BigDecimal(c.contract.payload.amount))
                    .sum
                  val displayed =
                    parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
                  displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)

                  val historyRows = findAll(cssSelector("#coupon-history-table tbody tr")).toSeq
                  historyRows should not be empty
                  historyRows.exists(row => row.text.contains("Withdrawn")) shouldBe true
                }
              },
            )
          }
        }

        // ===================================================================
        // Section: Claiming (coupon is claimed by beneficiary's automation)
        // ===================================================================

        clue("Claiming test") {
          val bobBalanceBefore = bobWalletClient.balance().unlockedQty
          val claimingAllocationAmount = BigDecimal(10)

          bobCollectRewardsTrigger.pause().futureValue
          expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

            actAndCheck(
              "Alice allocates a coupon for claiming test", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys(
                  claimingAllocationAmount.toString
                )
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  futureExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon for claiming test"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and unclaimed total has decreased",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("#active-coupons-table tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true

                  val backendTotal = sv1ScanBackend
                    .listUnclaimedDevelopmentFundCoupons()
                    .map(c => BigDecimal(c.contract.payload.amount))
                    .sum
                  val displayed =
                    parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
                  displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)
                }
              },
            )
            bobCollectRewardsTrigger.resume()

            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
              val bobBalanceAfter = bobWalletClient.balance().unlockedQty
              bobBalanceAfter should be > bobBalanceBefore
            }

            webDriver.navigate().refresh()
            eventuallyClickOn(id("navlink-development-fund"))
            eventually() {
              val backendTotal = sv1ScanBackend
                .listUnclaimedDevelopmentFundCoupons()
                .map(c => BigDecimal(c.contract.payload.amount))
                .sum
              val displayed = parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
              displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)

              val historyRows = findAll(cssSelector("#coupon-history-table tbody tr")).toSeq
              historyRows.exists(row => row.text.contains("Claimed")) shouldBe true
            }
          }

          bobCollectRewardsTrigger.pause().futureValue
        }

        // ===================================================================
        // Section: Expiring (coupon expires after time passes)
        // ===================================================================

        clue("Expiring test") {
          expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

          val expiringAllocationAmount = BigDecimal(1)
          val expiringExpiresAt = latestTime.plus(Duration.ofDays(365 * 5))
          val expiringExpiresAtFormatted = formatDateTimeForUI(expiringExpiresAt)

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

            actAndCheck(
              "Alice allocates a coupon with expiration", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys(
                  expiringAllocationAmount.toString
                )
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  expiringExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon for expiring test"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1

                  val backendTotal = sv1ScanBackend
                    .listUnclaimedDevelopmentFundCoupons()
                    .map(c => BigDecimal(c.contract.payload.amount))
                    .sum
                  val displayed =
                    parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
                  displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)
                }
              },
            )
            clue("Advance time past expiresAt") {
              val now = getLedgerTime.toInstant
              val timeToAdvance = Duration.between(now, expiringExpiresAt).plusDays(30)
              advanceTime(timeToAdvance)
            }
            expiredDevelopmentFundCouponTriggers.foreach(_.resume())

            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }

            webDriver.navigate().refresh()
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("unclaimed-total-amount"))
            eventually(timeUntilSuccess = 40.seconds) {
              val backendTotal = sv1ScanBackend
                .listUnclaimedDevelopmentFundCoupons()
                .map(c => BigDecimal(c.contract.payload.amount))
                .sum
              val displayed = parseUnclaimedAmount(find(id("unclaimed-total-amount")).value.text)
              displayed shouldBe backendTotal.setScale(4, BigDecimal.RoundingMode.HALF_UP)

              val historyRows = findAll(cssSelector("#coupon-history-table tbody tr")).toSeq
              historyRows.exists(row => row.text.contains("Expired")) shouldBe true
            }
          }
        }
    }
  }

  private def parseUnclaimedAmount(text: String): BigDecimal = {
    val numPattern = """[\d.]+""".r
    numPattern.findFirstIn(text) match {
      case Some(value) => BigDecimal(value)
      case None =>
        fail(s"Could not extract number from: '$text'")
    }
  }

  private def changeDevelopmentFundManager(
      newDfm: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existingConfig = AmuletConfigSchedule(amuletRules)
      .getConfigAsOf(env.environment.clock.now)

    val newConfig = new AmuletConfig[USD](
      existingConfig.transferConfig,
      existingConfig.issuanceCurve,
      existingConfig.decentralizedSynchronizer,
      existingConfig.tickDuration,
      existingConfig.packageConfig,
      existingConfig.transferPreapprovalFee,
      existingConfig.featuredAppActivityMarkerAmount,
      Optional.of(newDfm.toProtoPrimitive),
    )

    val action = new ARC_AmuletRules(
      new CRARC_SetConfig(
        new AmuletRules_SetConfig(
          newConfig,
          existingConfig,
        )
      )
    )

    val sv1Party = sv1Backend.getDsoInfo().svParty

    actAndCheck(
      "Create vote request to change DFM",
      sv1Backend.createVoteRequest(
        sv1Party.toProtoPrimitive,
        action,
        "url",
        "Change Development Fund Manager",
        new RelTime(Duration.ofSeconds(60).toMillis * 1000L),
        None,
      ),
    )(
      "Vote request is processed",
      _ => {
        sv1Backend.listVoteRequests() shouldBe empty
      },
    )
  }
}
