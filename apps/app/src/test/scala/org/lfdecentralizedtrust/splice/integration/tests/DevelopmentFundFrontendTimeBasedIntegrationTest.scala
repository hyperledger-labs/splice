package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
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
      // Configure Alice Wallet as the Development Fund Manager
      .addConfigTransform((_, config) => {
        val aliceParticipant =
          ConfigTransforms
            .getParticipantIds(config.parameters.clock)("alice")
        val alicePartyHint =
          config.validatorApps(InstanceName.tryCreate("aliceValidator")).validatorPartyHint.value
        val alicePartyId = PartyId
          .tryFromProtoPrimitive(
            s"$alicePartyHint::${aliceParticipant.split("::").last}"
          )
        ConfigTransforms.withDevelopmentFundManager(alicePartyId)(config)
      })
      .addConfigTransform((_, config) =>
        ConfigTransforms.withDevelopmentFundPercentage(0.05)(config)
      )

  "Development Fund - Happy Path (DFM doesn't change)" should {

    "complete full lifecycle via UI: allocation, withdrawal, claiming, and expiring" in {
      implicit env =>
        // Setup: Onboard users
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        val bobDamlUser = bobWalletClient.config.ledgerApiUser

        // Get triggers for controlling automation
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
          // Tap to create some activity that generates coupons
          aliceWalletClient.tap(100.0)
          // Advance multiple rounds to accumulate unclaimed coupons
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
                // DFM should NOT see the warning alert
                find(className("MuiAlert-standardInfo")) shouldBe empty
                // Should see the allocation form
                waitForQuery(id("development-fund-allocation-beneficiary"))
              },
            )

            // Check if total unclaimed should be greater than 0
            clue("Check: Total unclaimed should be positive") {
              eventually() {
                val totalText = find(cssSelector(".MuiCard-root h4")).value.text
                totalText should not be "0"
              }
            }

            // Get expiration date 30 days from now
            val expiresAt = env.environment.clock.now.plusSeconds(30 * 24 * 60 * 60)
            val expiresAtFormatted = DateTimeFormatter
              .ofPattern("MM/dd/yyyy hh:mm a")
              .format(expiresAt.toInstant.atZone(java.time.ZoneId.systemDefault()))

            actAndCheck(
              "Alice allocates a coupon to Bob via UI", {
                // Fill in beneficiary
                setAnsField(
                  textField("development-fund-allocation-beneficiary"),
                  bobParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                )

                // Fill in amount
                eventuallyClickOn(id("development-fund-allocation-amount"))
                textField("development-fund-allocation-amount").underlying.clear()
                textField("development-fund-allocation-amount").underlying.sendKeys("10")

                // Fill in expiration date
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)

                // Fill in reason
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
                  "Test allocation for Bob"
                )

                // Submit
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated and shown in active list",
              _ => {
                // Check if new coupon appears in active list
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true
                }
              },
            )

            // ===================================================================
            // Section: Withdrawal via UI
            // ===================================================================

            actAndCheck(
              "Alice withdraws the coupon via UI", {
                // Click withdraw button on the row
                eventuallyClickOn(cssSelector("tbody tr button"))

                // Wait for dialog and fill in reason
                waitForQuery(cssSelector("[role='dialog']"))
                val reasonField = webDriver.findElement(
                  org.openqa.selenium.By.cssSelector("[role='dialog'] textarea")
                )
                reasonField.sendKeys("Test withdrawal reason")

                // Confirm withdrawal
                eventuallyClickOn(cssSelector("[role='dialog'] button.MuiButton-contained"))
              },
            )(
              "Coupon is withdrawn and removed from active list",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  // Either no rows or the "no allocations" message
                  rows.forall(row =>
                    row.text.contains("No development fund allocations found")
                  ) || rows.isEmpty shouldBe true
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

          // Pause triggers
          bobCollectRewardsTrigger.pause().futureValue
          expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

          // Allocate a coupon via UI
          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

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
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
                  "Coupon for claiming test"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated",
              _ => {
                eventually() {
                  val rows = findAll(cssSelector("tbody tr")).toSeq
                  rows.exists(row => row.text.contains("10.0000")) shouldBe true
                }
              },
            )
          }

          // Resume beneficiary's trigger to claim
          bobCollectRewardsTrigger.resume()

          eventually() {
            // Check if coupon is claimed (removed from active list)
            aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty

            // Check if beneficiary's balance has increased
            val bobBalanceAfter = bobWalletClient.balance().unlockedQty
            bobBalanceAfter should be > bobBalanceBefore
          }

          bobCollectRewardsTrigger.pause().futureValue
        }

        // ===================================================================
        // Section: Expiring (coupon expires after time passes)
        // ===================================================================

        clue("Expiring test") {
          // Pause triggers
          bobCollectRewardsTrigger.pause().futureValue
          expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

          // Allocate a coupon with short expiration via UI
          withFrontEnd("alice") { implicit webDriver =>
            browseToAliceWallet(aliceDamlUser)
            eventuallyClickOn(id("navlink-development-fund"))
            waitForQuery(id("development-fund-allocation-beneficiary"))

            // Use a very short expiration (5 seconds from now)
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
                textField("development-fund-allocation-amount").underlying.sendKeys("10")
                setDateTime("alice", "development-fund-allocation-expires-at", expiresAtFormatted)
                eventuallyClickOn(id("development-fund-allocation-reason"))
                textArea("development-fund-allocation-reason").underlying.sendKeys(
                  "Coupon for expiring test"
                )
                eventuallyClickOn(id("development-fund-allocation-submit-button"))
              },
            )(
              "Coupon is allocated",
              _ => {
                eventually() {
                  aliceWalletClient.listActiveDevelopmentFundCoupons() should have size 1
                }
              },
            )
          }

          // Resume expiration triggers
          expiredDevelopmentFundCouponTriggers.foreach(_.resume())

          eventually() {
            // Check if coupon is expired (removed from active list)
            aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
          }
        }
    }
  }

  "Development Fund - Happy Path (DFM changes)" should {

    "handle DFM transition correctly" in { implicit env =>
      // Setup: Onboard users
      // user_1 = aliceWalletClient (initial DFM)
      // user_2 = bobWalletClient (will become new DFM)
      // charlie = beneficiary for coupons
      val user1Party = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val user2Party = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlieParty = onboardWalletUser(charlieWalletClient, charlieValidatorBackend)

      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      val bobDamlUser = bobWalletClient.config.ledgerApiUser
      val charlieDamlUser = charlieWalletClient.config.ledgerApiUser

      // Get triggers
      val charlieCollectRewardsTrigger =
        charlieValidatorBackend
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

          // Coupon 1: Will be CLAIMED
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

            // Resume charlie's trigger to claim
            charlieCollectRewardsTrigger.resume()
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            charlieCollectRewardsTrigger.pause().futureValue

            // Refresh page for next allocation
            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          // Coupon 2: Will be WITHDRAWN
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

            // Withdraw via UI
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

          // Coupon 3: Will be EXPIRED
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

            // Resume expiration triggers
            expiredDevelopmentFundCouponTriggers.foreach(_.resume())
            eventually() {
              aliceWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
            }
            expiredDevelopmentFundCouponTriggers.foreach(_.pause().futureValue)

            webDriver.navigate().refresh()
            waitForQuery(id("development-fund-allocation-beneficiary"))
          }

          // Coupon 4: Will remain ACTIVE
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

          // Check if warning text is shown (former DFM should see the info alert)
          clue("Check: user_1 sees warning text") {
            eventually() {
              find(className("MuiAlert-standardInfo")).isDefined shouldBe true
            }
          }

          // Check if active list shows 1 coupon
          clue("Check: user_1's Active List shows 1 coupon") {
            eventually() {
              val rows = findAll(cssSelector("tbody tr")).toSeq
              rows.exists(row => row.text.contains("10.0000")) shouldBe true
            }
          }

          // Action: Withdraw the active coupon
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

          // Note: History List verification would show 4 coupons when API is available
        }
      }

      // ===================================================================
      // Section: As user_2 (new DFM)
      // ===================================================================

      clue("As user_2 (new DFM)") {
        withFrontEnd("bob") { implicit webDriver =>
          browseToBobWallet(bobDamlUser)
          eventuallyClickOn(id("navlink-development-fund"))

          // Check if warning text is NOT shown (user_2 is the current DFM)
          clue("Check: user_2 does not see warning text") {
            eventually() {
              waitForQuery(id("development-fund-allocation-beneficiary"))
              find(className("MuiAlert-standardInfo")) shouldBe empty
            }
          }

          // Check if active list is empty
          clue("Check: user_2's Active List is empty") {
            eventually() {
              val rows = findAll(cssSelector("tbody tr")).toSeq
              rows.forall(row =>
                row.text.contains("No development fund allocations found")
              ) shouldBe true
            }
          }

          // Note: History List is empty (when API is available)

          // Check if user_2 can allocate new coupons
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

    "show appropriate UI and restrictions for non-DFM users" in { implicit env =>
      // Setup: Onboard users
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieParty = onboardWalletUser(charlieWalletClient, charlieValidatorBackend)

      val charlieDamlUser = charlieWalletClient.config.ledgerApiUser

      // ===================================================================
      // As user_3 (normal user - not DFM, no coupons)
      // ===================================================================

      clue("As user_3 (normal user)") {
        // Check if warning text is shown (via frontend - tab should not be visible)
        withFrontEnd("charlie") { implicit webDriver =>
          actAndCheck(
            "user_3 logs in to wallet", {
              browseToCharlieWallet(charlieDamlUser)
            },
          )(
            "user_3 does not see the Development Fund tab",
            _ => {
              // The Development Fund tab should not be visible for non-DFM users
              find(id("navlink-development-fund")) shouldBe empty
            },
          )
        }

        // Check if active list is empty
        clue("Check: user_3's Active List is empty") {
          charlieWalletClient.listActiveDevelopmentFundCoupons() shouldBe empty
        }

        // Note: History List is empty (when API is available)
      }
    }
  }

  // ===================================================================
  // Helper Methods
  // ===================================================================

  /** Changes the Development Fund Manager via governance vote */
  private def changeDevelopmentFundManager(
      newDfm: PartyId
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val amuletRules = sv1Backend.getDsoInfo().amuletRules
    val existingConfig = AmuletConfigSchedule(amuletRules)
      .getConfigAsOf(env.environment.clock.now)

    // Create new config with updated DFM
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

    // Create and execute vote request (single SV environment auto-approves)
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
        // In single SV environment, the vote is auto-approved
        sv1Backend.listVoteRequests() shouldBe empty
      },
    )
  }
}
