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

import java.time.Duration
import java.util.Optional
import scala.jdk.OptionConverters._

class DevelopmentFundFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTest("alice", "bob", "charlie")
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

  private val futureExpiresAtFormatted = "12/31/2030 12:00 pm"
  private val pastExpiresAtFormatted = "01/01/2020 12:00 pm"
  private val expiringExpiresAtFormatted = "12/31/2030 11:59 pm"

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.withDevelopmentFundPercentage(0.05)(config)
      )

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
              "Alice sees the Development Fund page without warning",
              _ => {
                find(className("MuiAlert-standardInfo")) shouldBe empty
                waitForQuery(id("development-fund-allocation-beneficiary"))
                waitForQuery(id("unclaimed-total-amount"))
              },
            )

            val totalUnclaimedBefore = clue("Check: Total unclaimed should be positive") {
              eventually() {
                val totalText = find(id("unclaimed-total-amount")).value.text
                totalText should not be "0"
                parseUnclaimedAmount(totalText)
              }
            }

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
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(allocationReason)
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and shown in active list with correct fields",
              _ => {
                eventually() {
                  val totalText = find(id("unclaimed-total-amount")).value.text
                  totalText should not be empty
                  val totalUnclaimedAfter = parseUnclaimedAmount(totalText)
                  totalUnclaimedAfter shouldBe (totalUnclaimedBefore - allocationAmount)

                  val rows = findAll(cssSelector("#active-coupons-table tbody tr")).toSeq
                  rows should have size 1
                  val rowText = rows.head.text
                  rowText should include("10.0000")
                  rowText should include(allocationReason)
                }
              },
            )

            val totalUnclaimedBeforeWithdrawal = eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              parseUnclaimedAmount(totalText)
            }

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

                  val totalText = find(id("unclaimed-total-amount")).value.text
                  totalText should not be empty
                  val totalUnclaimedAfterWithdrawal = parseUnclaimedAmount(totalText)
                  totalUnclaimedAfterWithdrawal shouldBe (totalUnclaimedBeforeWithdrawal + allocationAmount)

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

          var totalUnclaimedBeforeAllocation = BigDecimal(0)

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

            totalUnclaimedBeforeAllocation = eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              parseUnclaimedAmount(totalText)
            }

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

                  val totalText = find(id("unclaimed-total-amount")).value.text
                  totalText should not be empty
                  val totalUnclaimedAfterAllocation = parseUnclaimedAmount(totalText)
                  totalUnclaimedAfterAllocation shouldBe (totalUnclaimedBeforeAllocation - claimingAllocationAmount)
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
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              val totalUnclaimedAfterClaiming = parseUnclaimedAmount(totalText)
              totalUnclaimedAfterClaiming shouldBe (totalUnclaimedBeforeAllocation - claimingAllocationAmount)

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

          var totalUnclaimedBeforeAllocation = BigDecimal(0)
          val expiringAllocationAmount = BigDecimal(10)

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

            totalUnclaimedBeforeAllocation = eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              parseUnclaimedAmount(totalText)
            }

            actAndCheck(
              "Alice allocates a coupon with short expiration", {
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
              "Coupon is allocated and total unclaimed has decreased",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1

                  val totalText = find(id("unclaimed-total-amount")).value.text
                  totalText should not be empty
                  val totalUnclaimedAfterAllocation = parseUnclaimedAmount(totalText)
                  totalUnclaimedAfterAllocation shouldBe (totalUnclaimedBeforeAllocation - expiringAllocationAmount)
                }
              },
            )
            clue("Advance time past expiresAt") {
              advanceTime(Duration.ofDays(3650))
            }
            expiredDevelopmentFundCouponTriggers.foreach(_.resume())

            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }

            webDriver.navigate().refresh()
            eventuallyClickOn(id("navlink-development-fund"))
            eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              val totalUnclaimedAfterExpiry = parseUnclaimedAmount(totalText)
              totalUnclaimedAfterExpiry shouldBe totalUnclaimedBeforeAllocation

              val historyRows = findAll(cssSelector("#coupon-history-table tbody tr")).toSeq
              historyRows.exists(row => row.text.contains("Expired")) shouldBe true
            }
          }
        }
    }
  }

  "Development Fund - Unhappy path (invalid allocation values)" should {

    "reject allocation when amount exceeds unclaimed development fund total" in {
      implicit env =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

        clue("Set alice (wallet user) as DFM via governance vote") {
          changeDevelopmentFundManager(aliceParty)
        }

        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser

        clue("Advance rounds to generate unclaimed development fund coupons") {
          aliceWalletClient.tap(100.0)
          Range(0, 6).foreach(_ => {
            advanceTime(tickDurationWithBuffer)
            advanceRoundsByOneTickViaAutomation()
          })
        }

        withFrontEnd("alice") { implicit webDriver =>
          browseToAliceWallet(aliceDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))
          waitForQuery(id("development-fund-allocation-beneficiary"))

          val totalUnclaimed = clue("Read unclaimed total from UI") {
            eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be empty
              parseUnclaimedAmount(totalText)
            }
          }

          val invalidAmount = totalUnclaimed + BigDecimal(100000)
          clue("Fill allocation form with amount larger than unclaimed total") {
            setAnsField(
              textField(id("development-fund-allocation-beneficiary")),
              bobParty.toProtoPrimitive,
              bobParty.toProtoPrimitive,
            )
            eventuallyClickOn(id("development-fund-allocation-amount"))
            textField(id("development-fund-allocation-amount")).underlying.clear()
            textField(id("development-fund-allocation-amount")).underlying.sendKeys(
              invalidAmount.toString
            )
            waitForQuery(id("development-fund-allocation-expires-at"))
            setDateTimeWithoutScroll(
              "development-fund-allocation-expires-at",
              futureExpiresAtFormatted,
            )
            eventuallyClickOn(id("development-fund-allocation-reason"))
            textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
              "Invalid allocation test"
            )
          }

          clue("Submit button is disabled due to amount exceeding available") {
            eventually() {
              find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
            }
          }

          clue("Amount field shows available total (helper text)") {
            eventually() {
              val pageText = webDriver.getPageSource
              pageText should include("Available:")
            }
          }

          clue("Unclaimed total is unchanged (no allocation submitted)") {
            val totalTextAfter = find(id("unclaimed-total-amount")).value.text
            totalTextAfter should not be empty
            val totalUnclaimedAfter = parseUnclaimedAmount(totalTextAfter)
            totalUnclaimedAfter shouldBe totalUnclaimed
          }

          clue("No new coupon appears in active list") {
            eventually() {
              val emptyStateCell = find(
                cssSelector("#active-coupons-table tbody tr td[colspan='6']")
              )
              emptyStateCell.isDefined shouldBe true
              emptyStateCell.value.text should include("No development fund allocations found")
            }
          }
        }
    }

    "reject allocation with invalid form inputs" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      clue("Set alice (wallet user) as DFM via governance vote") {
        changeDevelopmentFundManager(aliceParty)
      }

      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser

      clue("Advance rounds to generate unclaimed development fund coupons") {
        aliceWalletClient.tap(100.0)
        Range(0, 6).foreach(_ => {
          advanceTime(tickDurationWithBuffer)
          advanceRoundsByOneTickViaAutomation()
        })
      }

      withFrontEnd("alice") { implicit webDriver =>
        browseToAliceWallet(aliceDamlUser)
        eventuallyClickOn(id("navlink-development-fund"))
        waitForQuery(id("development-fund-allocation-beneficiary"))

        clue("Submit button is disabled when form is empty") {
          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled with only beneficiary filled") {
          setAnsField(
            textField(id("development-fund-allocation-beneficiary")),
            bobParty.toProtoPrimitive,
            bobParty.toProtoPrimitive,
          )
          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled with zero amount") {
          eventuallyClickOn(id("development-fund-allocation-amount"))
          textField(id("development-fund-allocation-amount")).underlying.clear()
          textField(id("development-fund-allocation-amount")).underlying.sendKeys("0")
          waitForQuery(id("development-fund-allocation-expires-at"))
          setDateTimeWithoutScroll(
            "development-fund-allocation-expires-at",
            futureExpiresAtFormatted,
          )
          eventuallyClickOn(id("development-fund-allocation-reason"))
          textArea(id("development-fund-allocation-reason")).underlying.sendKeys("Test reason")

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled with negative amount") {
          eventuallyClickOn(id("development-fund-allocation-amount"))
          textField(id("development-fund-allocation-amount")).underlying.clear()
          textField(id("development-fund-allocation-amount")).underlying.sendKeys("-10")

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled without reason") {
          eventuallyClickOn(id("development-fund-allocation-amount"))
          textField(id("development-fund-allocation-amount")).underlying.clear()
          textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
          textArea(id("development-fund-allocation-reason")).underlying.clear()

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled when beneficiary is empty but other fields are filled") {
          webDriver.navigate().refresh()
          waitForQuery(id("development-fund-allocation-beneficiary"))
          eventuallyClickOn(id("development-fund-allocation-amount"))
          textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
          waitForQuery(id("development-fund-allocation-expires-at"))
          setDateTimeWithoutScroll(
            "development-fund-allocation-expires-at",
            futureExpiresAtFormatted,
          )
          textArea(id("development-fund-allocation-reason")).underlying.sendKeys("Test reason")

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }

        clue("Submit button is disabled when expires at is in the past") {
          webDriver.navigate().refresh()
          waitForQuery(id("development-fund-allocation-beneficiary"))
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
            pastExpiresAtFormatted,
          )
          textArea(id("development-fund-allocation-reason")).underlying.clear()
          textArea(id("development-fund-allocation-reason")).underlying.sendKeys("Test reason")

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }

          clue("Expires at field shows helper text when date is in the past") {
            eventually() {
              val pageText = webDriver.getPageSource
              pageText should include("Expiry must be in the future")
            }
          }
        }

        clue("Submit button is disabled with non-numeric amount") {
          webDriver.navigate().refresh()
          waitForQuery(id("development-fund-allocation-beneficiary"))
          setAnsField(
            textField(id("development-fund-allocation-beneficiary")),
            bobParty.toProtoPrimitive,
            bobParty.toProtoPrimitive,
          )
          eventuallyClickOn(id("development-fund-allocation-amount"))
          textField(id("development-fund-allocation-amount")).underlying.clear()
          textField(id("development-fund-allocation-amount")).underlying.sendKeys("abc")
          waitForQuery(id("development-fund-allocation-expires-at"))
          setDateTimeWithoutScroll(
            "development-fund-allocation-expires-at",
            futureExpiresAtFormatted,
          )
          textArea(id("development-fund-allocation-reason")).underlying.clear()
          textArea(id("development-fund-allocation-reason")).underlying.sendKeys("Test reason")

          eventually() {
            find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
          }
        }
      }
    }
  }

  "Development Fund - Happy Path (DFM changes)" should {

    "handle DFM transition correctly" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val user2Party = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlieParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      clue("Set alice (wallet user) as initial DFM via governance vote") {
        changeDevelopmentFundManager(aliceParty)
      }

      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val bobDamlUser = bobWalletClient.config.ledgerApiUser
      val charlieDamlUser = charlieWalletClient.config.ledgerApiUser

      val charlieCollectRewardsTrigger =
        aliceValidatorBackend
          .userWalletAutomation(charlieDamlUser)
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

      // ===================================================================
      // Section: Create 4 coupons for user_1 (3 archived, 1 active)
      // ===================================================================

      setTriggersWithin(
        triggersToPauseAtStart =
          Seq(charlieCollectRewardsTrigger) ++ expiredDevelopmentFundCouponTriggers
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
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
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

            charlieCollectRewardsTrigger.resume()
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            charlieCollectRewardsTrigger.pause().futureValue

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          clue("Create coupon 2 (to be withdrawn)") {
            actAndCheck(
              "user_1 allocates coupon 2", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
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

          clue("Create coupon 3 (to be expired)") {
            actAndCheck(
              "user_1 allocates coupon 3 with short expiration", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField(id("development-fund-allocation-amount")).underlying.clear()
                textField(id("development-fund-allocation-amount")).underlying.sendKeys("10")
                waitForQuery(id("development-fund-allocation-expires-at"))
                setDateTimeWithoutScroll(
                  "development-fund-allocation-expires-at",
                  expiringExpiresAtFormatted,
                )
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea(id("development-fund-allocation-reason")).underlying.sendKeys(
                  "Coupon 3 - for expiration"
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

            advanceTime(Duration.ofDays(3650))
            expiredDevelopmentFundCouponTriggers.foreach(_.resume())
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          clue("Create coupon 4 (to remain active)") {
            actAndCheck(
              "user_1 allocates coupon 4", {
                setAnsField(
                  textField(id("development-fund-allocation-beneficiary")),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
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
                  "Coupon 4 - stays active"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon 4 is allocated",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1
                }
              },
            )
          }
        }
      }

      // ===================================================================
      // Section: Change DFM from user_1 to user_2
      // ===================================================================

      clue("Change DFM from user_1 to user_2 via governance") {
        changeDevelopmentFundManager(user2Party)

        eventually() {
          val amuletRules = sv1ScanBackend.getAmuletRules()
          val currentDfm = amuletRules.payload.configSchedule.initialValue.optDevelopmentFundManager
            .map(PartyId.tryFromProtoPrimitive)
            .toScala
          currentDfm shouldBe Some(user2Party)
        }
      }

      // ===================================================================
      // Section: As user_1 (former DFM)
      // ===================================================================

      clue("As user_1 (former DFM)") {
        withFrontEnd("alice") { implicit webDriver =>
          browseToAliceWallet(aliceDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))

          clue("Check: user_1 sees warning text") {
            eventually() {
              find(className("MuiAlert-standardInfo")).isDefined shouldBe true
            }
          }

          clue("Check: user_1 cannot allocate (form disabled)") {
            eventually() {
              find(id("development-fund-allocation-submit-button")).value.isEnabled shouldBe false
            }
          }

          clue("Check: user_1's Active List shows 1 coupon") {
            eventually() {
              val rows = findAll(cssSelector("#active-coupons-table tbody tr")).toSeq
              rows.exists(row => row.text.contains("10.0000")) shouldBe true
            }
          }

          clue("Action: user_1 withdraws the active coupon") {
            actAndCheck(
              "user_1 withdraws coupon 4", {
                eventuallyClickOn(
                  cssSelector("#active-coupons-table tbody tr td:last-child button")
                )
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector(
                    "[role='dialog'] textarea[placeholder='Enter the reason for withdrawal']"
                  )
                )
                reasonField.sendKeys("Final withdrawal")
                eventuallyClickOn(cssSelector("[role='dialog'] button.MuiButton-contained"))
              },
            )(
              "Coupon 4 is withdrawn",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
                }
              },
            )
          }

          clue("Check: user_1's History List shows 4 coupons") {
            eventually() {
              val historyRows = findAll(cssSelector("#coupon-history-table tbody tr td")).toSeq
              val dataRows = historyRows.filterNot(_.text.contains("No history events found"))
              dataRows should have size 4
            }
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

          clue("Check: user_2 does not see warning text (user_2 is the DFM)") {
            eventually() {
              waitForQuery(id("development-fund-allocation-beneficiary"))
              find(className("MuiAlert-standardInfo")) shouldBe empty
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
              val emptyStateCell = find(cssSelector("#coupon-history-table tbody tr td[colspan='6']"))
              emptyStateCell.isDefined shouldBe true
              emptyStateCell.value.text should include("No history events found")
            }
          }

          clue("Check: user_2 can allocate new coupons") {
            setTriggersWithin(
              triggersToPauseAtStart =
                Seq(charlieCollectRewardsTrigger) ++ expiredDevelopmentFundCouponTriggers
            ) {
              actAndCheck(
                "user_2 allocates a new coupon", {
                  setAnsField(
                    textField(id("development-fund-allocation-beneficiary")),
                    charlieParty.toProtoPrimitive,
                    charlieParty.toProtoPrimitive,
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
                    coupons.head.payload.fundManager shouldBe user2Party.toProtoPrimitive
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
      onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

      clue("Set alice (wallet user) as DFM via governance vote") {
        changeDevelopmentFundManager(aliceParty)
      }

      val charlieDamlUser = charlieWalletClient.config.ledgerApiUser

      // ===================================================================
      // As user_3 (normal user - not DFM, no coupons)
      // ===================================================================

      clue("As user_3 (normal user)") {
        clue("Check via API: user_3's Active List is empty") {
          charlieWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
        }

        withFrontEnd("charlie") { implicit webDriver =>
          browseToCharlieWallet(charlieDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))

          clue("Check: Warning text is shown for non-DFM user") {
            eventually() {
              find(className("MuiAlert-standardInfo")).isDefined shouldBe true
              val alertText = find(className("MuiAlert-standardInfo")).value.text
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
              val emptyStateCell = find(cssSelector("#coupon-history-table tbody tr td[colspan='6']"))
              emptyStateCell.isDefined shouldBe true
              emptyStateCell.value.text should include("No history events found")
            }
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
      throw new RuntimeException(s"Could not extract number from: '$text'")
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
