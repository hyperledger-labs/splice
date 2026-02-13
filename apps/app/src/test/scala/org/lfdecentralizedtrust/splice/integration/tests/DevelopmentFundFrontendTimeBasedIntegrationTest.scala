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

import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Optional
import scala.jdk.OptionConverters._

class DevelopmentFundFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with TimeTestUtil
    with TriggerTestUtil {

  override protected def runUpdateHistorySanityCheck: Boolean = false

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
          Range(0, 6).foreach(_ => advanceRoundsToNextRoundOpening)
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
              },
            )

            val totalUnclaimedBefore = clue("Check: Total unclaimed should be positive") {
              eventually() {
                val totalText = find(id("unclaimed-total-amount")).value.text
                totalText should not be "0"
                BigDecimal(totalText.split(" ").head)
              }
            }

            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            val allocationAmount = BigDecimal(10)
            val allocationReason = "Test allocation for Bob"

            actAndCheck(
              "Alice allocates a coupon to Bob via UI", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys(
                  allocationAmount.toString
                )
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(allocationReason)
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and shown in active list with correct fields",
              _ => {
                eventually() {
                  val totalText = find(id("unclaimed-total-amount")).value.text
                  val totalUnclaimedAfter = BigDecimal(totalText.split(" ").head)
                  totalUnclaimedAfter shouldBe (totalUnclaimedBefore - allocationAmount)

                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows should have size 1
                  val rowText = rows.head.text
                  rowText should include(bobParty.toProtoPrimitive.take(20))
                  rowText should include("10.0000")
                  rowText should include(allocationReason)
                }
              },
            )

            val totalUnclaimedBeforeWithdrawal = eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              BigDecimal(totalText.split(" ").head)
            }

            // ===================================================================
            // Section: Withdrawal via UI
            // ===================================================================

            actAndCheck(
              "Alice withdraws the coupon via UI", {
                eventuallyClickOn(cssSelector("tbody tr button"))
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector("[role='dialog'] textarea")
                )
                reasonField.sendKeys("Test withdrawal reason")
                eventuallyClickOn(cssSelector("[role='dialog'] button.MuiButton-contained"))
              },
            )(
              "Coupon is withdrawn, removed from active list, and unclaimed total increased",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows.forall(row =>
                    row.text.contains("No development fund allocations found")
                  ) || rows.isEmpty shouldBe true

                  val totalText = find(id("unclaimed-total-amount")).value.text
                  val totalUnclaimedAfterWithdrawal = BigDecimal(totalText.split(" ").head)
                  totalUnclaimedAfterWithdrawal shouldBe (totalUnclaimedBeforeWithdrawal + allocationAmount)
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
              BigDecimal(totalText.split(" ").head)
            }

            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "Alice allocates a coupon for claiming test", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys(
                  claimingAllocationAmount.toString
                )
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
                  "Coupon for claiming test"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and unclaimed total has decreased",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true

                  val totalText = find(id("unclaimed-total-amount")).value.text
                  val totalUnclaimedAfterAllocation = BigDecimal(totalText.split(" ").head)
                  totalUnclaimedAfterAllocation shouldBe (totalUnclaimedBeforeAllocation - claimingAllocationAmount)
                }
              },
            )
          }

          bobCollectRewardsTrigger.resume()

          eventually() {
            aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            val bobBalanceAfter = bobWalletClient.balance().unlockedQty
            bobBalanceAfter should be > bobBalanceBefore
          }

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              val totalUnclaimedAfterClaiming = BigDecimal(totalText.split(" ").head)
              totalUnclaimedAfterClaiming shouldBe (totalUnclaimedBeforeAllocation - claimingAllocationAmount)
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
              BigDecimal(totalText.split(" ").head)
            }

            val expiresAt = env.environment.clock.now.plusSeconds(5)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "Alice allocates a coupon with short expiration", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys(
                  expiringAllocationAmount.toString
                )
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
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
                  val totalUnclaimedAfterAllocation = BigDecimal(totalText.split(" ").head)
                  totalUnclaimedAfterAllocation shouldBe (totalUnclaimedBeforeAllocation - expiringAllocationAmount)
                }
              },
            )
          }

          expiredDevelopmentFundCouponTriggers.foreach(_.resume())

          eventually() {
            aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
          }

          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              val totalUnclaimedAfterExpiry = BigDecimal(totalText.split(" ").head)
              totalUnclaimedAfterExpiry shouldBe totalUnclaimedBeforeAllocation
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
          Range(0, 6).foreach(_ => advanceRoundsToNextRoundOpening)
        }

        withFrontEnd("alice") { implicit webDriver =>
          browseToAliceWallet(aliceDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))
          waitForQuery(id("development-fund-allocation-beneficiary"))

          val totalUnclaimed = clue("Read unclaimed total from UI") {
            eventually() {
              val totalText = find(id("unclaimed-total-amount")).value.text
              totalText should not be "0"
              BigDecimal(totalText.split(" ").head)
            }
          }

          val invalidAmount = totalUnclaimed + BigDecimal(100000)
          val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
          val expiresAtFormatted = DateTimeFormatter
            .ofPattern("MM/dd/yyyy hh:mm a")
            .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

          clue("Fill allocation form with amount larger than unclaimed total") {
            setAnsField(
              textField("development-fund-allocation-beneficiary"),
              bobParty.toProtoPrimitive,
              bobParty.toProtoPrimitive,
            )
            eventuallyClickOn(id("development-fund-allocation-amount"))
            textField("development-fund-allocation-amount").underlying.clear()
            textField("development-fund-allocation-amount").underlying.sendKeys(
              invalidAmount.toString
            )
            setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
            eventuallyClickOn(id("development-fund-allocation-reason"))
            textArea("development-fund-allocation-reason").underlying.sendKeys(
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
            val totalUnclaimedAfter = BigDecimal(totalTextAfter.split(" ").head)
            totalUnclaimedAfter shouldBe totalUnclaimed
          }

          clue("No new coupon appears in active list") {
            val rows = findAll(cssSelector("tbody tr")).toSeq
            rows.forall(row =>
              row.text.contains("No development fund allocations found")
            ) || rows.isEmpty shouldBe true
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
        Range(0, 6).foreach(_ => advanceRoundsToNextRoundOpening)
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
            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "user_1 allocates coupon 1", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
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
            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "user_1 allocates coupon 2", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
                  "Coupon 2 - for withdrawal"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon 2 is allocated",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true
                }
              },
            )

            actAndCheck(
              "user_1 withdraws coupon 2", {
                eventuallyClickOn(cssSelector("tbody tr button"))
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector("[role='dialog'] textarea")
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
            val expiresAt = env.environment.clock.now.plusSeconds(5)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "user_1 allocates coupon 3 with short expiration", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
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

            expiredDevelopmentFundCouponTriggers.foreach(_.resume())
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          clue("Create coupon 4 (to remain active)") {
            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "user_1 allocates coupon 4", {
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  charlieParty.toProtoPrimitive,
                  charlieParty.toProtoPrimitive,
                )
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
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

          clue("Check: user_1's Active List shows 1 coupon") {
            eventually() {
              val rows = findAll(cssSelector("tbody tr")).toSeq
              rows.exists(row => row.text.contains("10.0000")) shouldBe true
            }
          }

          clue("Action: user_1 withdraws the active coupon") {
            actAndCheck(
              "user_1 withdraws coupon 4", {
                eventuallyClickOn(cssSelector("tbody tr button"))
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector("[role='dialog'] textarea")
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
        }
      }

      // ===================================================================
      // Section: As user_2 (new DFM)
      // ===================================================================

      clue("As user_2 (new DFM)") {
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))

          clue("Check: user_2 does not see warning text") {
            eventually() {
              waitForQuery(id("development-fund-allocation-beneficiary"))
              find(className("MuiAlert-standardInfo")) shouldBe empty
            }
          }

          clue("Check: user_2's Active List is empty") {
            eventually() {
              val rows = findAll(cssSelector("tbody tr")).toSeq
              rows.forall(row =>
                row.text.contains("No development fund allocations found")
              ) shouldBe true
            }
          }

          clue("Check: user_2 can allocate new coupons") {
            setTriggersWithin(
              triggersToPauseAtStart =
                Seq(charlieCollectRewardsTrigger) ++ expiredDevelopmentFundCouponTriggers
            ) {
              val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
              val expiresAtFormatted = DateTimeFormatter
                .ofPattern("MM/dd/yyyy hh:mm a")
                .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

              actAndCheck(
                "user_2 allocates a new coupon", {
                  setAnsField(
                    textField("development-fund-allocation-beneficiary"),
                    charlieParty.toProtoPrimitive,
                    charlieParty.toProtoPrimitive,
                  )
                  eventuallyClickOn(id("development-fund-allocation-amount"))
                  textField("development-fund-allocation-amount").underlying.clear()
                  textField("development-fund-allocation-amount").underlying.sendKeys("10")
                  setDateTime("bob", "development-fund-allocation-expires-at", expiresAtFormatted)
                  eventuallyClickOn(id("development-fund-allocation-reason"))
                  textArea("development-fund-allocation-reason").underlying.sendKeys(
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

      // ===================================================================
      // As user_3 (normal user - not DFM, no coupons)
      // ===================================================================

      clue("As user_3 (normal user)") {
        clue("Check: user_3's Active List is empty") {
          charlieWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
        }
      }
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
