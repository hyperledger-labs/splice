package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.codegen.java.cc.globaldomain.{BaseRateTrafficLimits, ValidatorTraffic}
import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs
import com.daml.network.console.{ValidatorAppBackendReference, WalletAppClientReference}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DomainFeesConstants, TimeTestUtil, WalletTestUtil}
import com.daml.network.validator.util.ExtraTrafficTopupParameters
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.time.{Minutes, Span}
import org.slf4j.event.Level

import java.time.Duration
import scala.concurrent.Future

class DomainFeesTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withHttpSettingsForHigherThroughput
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllValidatorConfigs { case (name, validatorConfig) =>
          val domainFeesEnabledConfig = validatorConfig
            .focus(_.treasury.enableValidatorTrafficBalanceChecks)
            .replace(true)
            .focus(_.automation.enableAutomaticValidatorTrafficBalanceTopup)
            .replace(true)
            // reduced values to make the test run faster
            .focus(_.domains.global.buyExtraTraffic.targetThroughput)
            .replace(NonNegativeNumeric.tryCreate(BigDecimal(10_000)))
            .focus(_.domains.global.buyExtraTraffic.minTopupInterval)
            .replace(NonNegativeFiniteDuration.ofMinutes(5))
          /*
           * bobValidator is used in this test as an example of a validator that will submit requests
           * at the base rate only and will not purchase any extra traffic.
           * TODO(M3-44): Once we're no longer mocking the canton sequencer and the top-up trigger is live for all tests,
           *  it may be worthwhile to create a separate validator for this purpose to properly isolate this test
           *  from other tests making use of bobValidator and any residual traffic balance that may be left over as a result.
           */
          if (name.contains("bob"))
            domainFeesEnabledConfig
              .focus(_.domains.global.buyExtraTraffic.targetThroughput)
              .replace(NonNegativeNumeric.tryCreate(BigDecimal(0)))
          else domainFeesEnabledConfig
        }(cnNodeConfig)
      )
  }

  private val futureCompletionTimeout = Timeout(Span(3, Minutes))

  "A validator's traffic top-up loop" when {
    "the validator is configured to not buy extra traffic" should {
      "not top-up at all limiting throughput to base rate" in { implicit env =>
        onboardWalletUser(bobWallet, bobValidator)
        bobValidatorWallet.tap(1000)

        val topupParameters = getTopupParameters(bobValidator)
        topupParameters.topupAmount shouldBe 0L

        actAndCheck(
          "Advance time to trigger automation to purchase extra traffic",
          advanceTimeByPollingInterval(aliceValidator),
        )(
          "No top-up happens",
          _ => {
            // NOTE: this check is not very strong, as it is already true at the beginning of the test.
            // We'll recheck that no traffic contract has been created at the end of the test to be more sure.
            lookupCurrentValidatorTraffic(bobValidator) should have length 0
          },
        )

        actAndCheck(
          "Execute taps to consume available traffic balance", {
            val numTaps =
              (baseRateTrafficBalance / DomainFeesConstants.assumedCoinTxSizeBytes.value).toInt
            tryTapsAndCountSuccesses(bobWallet, numTaps)
          },
        )(
          "All taps are successful",
          actResult => actResult._1 shouldBe actResult._2,
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          clue("Execute another tap and see that it fails")(
            tryTapAndReturnOneOnSuccess(bobWallet, 100) shouldBe 0
          ),
          entries =>
            forAtLeast(1, entries)(
              _.message should include("insufficient validator traffic balance to create coins")
            ),
        )

        actAndCheck(
          "Advance time sufficiently for base rate to fill up again", {
            val seconds = Duration.ofSeconds(
              Math
                .ceil(
                  DomainFeesConstants.assumedCoinTxSizeBytes.value / baseRateLimits.rate
                    .doubleValue()
                )
                .toLong
            )
            advanceTime(seconds)
          },
        )(
          "Tap is successful once more",
          _ => tryTapAndReturnOneOnSuccess(bobWallet, 100) shouldBe 1,
        )

        clue("Recheck that no traffic contract has been created")(
          lookupCurrentValidatorTraffic(bobValidator) should have length 0
        )
      }
    }

    "the validator is configured to buy extra traffic" should {
      "top-up quickly enough to achieve the configured target rate" in { implicit env =>
        onboardWalletUser(aliceWallet, aliceValidator)
        aliceValidatorWallet.tap(1000)

        val topupParameters = getTopupParameters(aliceValidator)
        topupParameters.topupAmount should be > 0L

        actAndCheck(
          "Advance time to trigger automation to purchase extra traffic",
          advanceTimeByPollingInterval(aliceValidator),
        )(
          "The validator is able to successfully top up their extra traffic",
          _ => {
            inside(lookupCurrentValidatorTraffic(aliceValidator)) { case Seq(validatorTraffic) =>
              validatorTraffic.data.validator shouldBe aliceValidator
                .getValidatorPartyId()
                .toProtoPrimitive
              validatorTraffic.data.numPurchases shouldBe 1
              validatorTraffic.data.totalPurchased shouldBe topupParameters.topupAmount
            }
          },
        )

        actAndCheck(
          "Execute taps to consume available traffic balance", {
            val totalAvailableTrafficBalance: BigDecimal =
              baseRateTrafficBalance + topupParameters.topupAmount
            val numTaps =
              (totalAvailableTrafficBalance / DomainFeesConstants.assumedCoinTxSizeBytes.value).toInt
            tryTapsAndCountSuccesses(aliceWallet, numTaps)
          },
        )(
          "All taps are successful",
          actResult => actResult._1 shouldBe actResult._2,
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          clue("Execute another tap and see that it fails")(
            tryTapAndReturnOneOnSuccess(aliceWallet, 100) shouldBe 0
          ),
          entries =>
            forAtLeast(1, entries)(
              _.message should include("insufficient validator traffic balance to create coins")
            ),
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          clue("Advance time by half the min top-up interval")(
            advanceTimeByMinTopupInterval(aliceValidator, 0.5)
          ),
          entries =>
            clue("Top-up is skipped as not enough time has elapsed since the previous top-up") {
              forAtLeast(1, entries)(line =>
                assert(
                  line.loggerName.contains("validator=aliceValidator") &&
                    line.message.contains("Trying to top-up too soon after previous top-up")
                )
              )
            },
        )

        actAndCheck(
          "Advance time by half the min top-up interval",
          advanceTimeByMinTopupInterval(aliceValidator, 0.5),
        )(
          "Top-up should be successful",
          _ => {
            inside(lookupCurrentValidatorTraffic(aliceValidator)) { case Seq(validatorTraffic) =>
              validatorTraffic.data.validator shouldBe aliceValidator
                .getValidatorPartyId()
                .toProtoPrimitive
              validatorTraffic.data.numPurchases shouldBe 2
              validatorTraffic.data.totalPurchased shouldBe 2 * topupParameters.topupAmount
            }
          },
        )

        actAndCheck(
          "Execute taps to consume free base-rate traffic balance", {
            val numTaps =
              (baseRateTrafficBalance / DomainFeesConstants.assumedCoinTxSizeBytes.value).toInt
            tryTapsAndCountSuccesses(aliceWallet, numTaps)
          },
        )(
          "Taps are successful",
          actResult => actResult._1 shouldBe actResult._2,
        )

        actAndCheck(
          "Execute another tap",
          tryTapAndReturnOneOnSuccess(aliceWallet, 100),
        )(
          "Tap is successful since sufficient extra traffic balance has been purchased",
          _ shouldBe 1,
        )

        actAndCheck(
          "Advance time by min top-up interval",
          advanceTimeByMinTopupInterval(aliceValidator),
        )(
          // The low balance threshold at which top-up occurs is the same as the amount of extra traffic
          // purchased on each top-up.
          "Top-up is not skipped since the balance is below the top-up threshold",
          _ => {
            inside(lookupCurrentValidatorTraffic(aliceValidator)) { case Seq(validatorTraffic) =>
              validatorTraffic.data.validator shouldBe aliceValidator
                .getValidatorPartyId()
                .toProtoPrimitive
              validatorTraffic.data.numPurchases shouldBe 3
              validatorTraffic.data.totalPurchased shouldBe 3 * topupParameters.topupAmount
            }
          },
        )

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
          clue("Advance time to try and trigger top-up again")(
            advanceTimeByMinTopupInterval(aliceValidator, 2)
          ),
          entries =>
            clue("Top-up is skipped as previously purchased traffic has not been consumed") {
              forAtLeast(1, entries)(line =>
                assert(
                  line.loggerName.contains("validator=aliceValidator") &&
                    line.message.contains("sufficient traffic balance remains")
                )
              )
            },
        )

      }
    }
  }

  private def lookupCurrentValidatorTraffic(validatorApp: ValidatorAppBackendReference) =
    validatorApp.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(ValidatorTraffic.COMPANION)(validatorApp.getValidatorPartyId())

  private def baseRateLimits(implicit env: CNNodeTestConsoleEnvironment): BaseRateTrafficLimits =
    scan.getCoinRules().payload.configSchedule.currentValue.globalDomain.fees.baseRateTrafficLimits

  private def baseRateTrafficBalance(implicit env: CNNodeTestConsoleEnvironment): BigDecimal = {
    BigDecimal(baseRateLimits.burstWindow.microseconds) / 1e6 * baseRateLimits.rate
  }

  private def getTopupParameters(
      validatorApp: ValidatorAppBackendReference
  )(implicit env: CNNodeTestConsoleEnvironment): ExtraTrafficTopupParameters = {
    ExtraTrafficTopupParameters(
      scan.getCoinRules().payload.configSchedule.currentValue.globalDomain.fees,
      validatorApp.config.domains.global.buyExtraTraffic,
      validatorApp.config.automation.pollingInterval,
    )
  }

  private def tryTapsAndCountSuccesses(wallet: WalletAppClientReference, numTaps: Int) = {
    val successfulTaps = Range
      .inclusive(1, numTaps)
      .toList
      .traverse(i => Future(tryTapAndReturnOneOnSuccess(wallet, BigDecimal(i))))
      .map(_.sum)
      .futureValue(futureCompletionTimeout)
    (successfulTaps, numTaps)
  }

  private def tryTapAndReturnOneOnSuccess(
      wallet: WalletAppClientReference,
      amount: BigDecimal,
  ): Int = {
    logger.debug(s"Tapping $amount CC")
    try {
      wallet.tap(amount)
      1
    } catch {
      case scala.util.control.NonFatal(ex) =>
        logger.debug(s"Ignored exception while tapping $amount CC", ex)
        0
    }
  }

}
