package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.console.WalletAppClientReference
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{DomainFeesConstants, TimeTestUtil, WalletTestUtil}

import java.time.Duration
import monocle.macros.syntax.lens.*

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

class DomainFeesTimeBasedIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withHttpSettingsForHigherThroughput
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllValidatorConfigs_(validatorConfig =>
          validatorConfig
            .focus(_.treasury.enableValidatorTrafficBalanceChecks)
            .replace(true)
            .focus(_.automation.enableAutomaticValidatorTrafficBalanceTopup)
            .replace(true)
        )(cnNodeConfig)
      )
  }

  private lazy val loadTestDuration = 10.seconds

  "A validator with a correctly configured traffic top-up loop" when {
    s"target throughput is ${DomainFeesConstants.targetThroughput} MB/s" must {
      // bobValidator is used in this test as an example of a validator that will submit requests
      // at the base rate only and will not purchase any extra traffic.
      // TODO(M3-44): Once we're no longer mocking the canton sequencer and the top-up trigger is live for all tests,
      //  it may be worthwhile to create a separate validator for this purpose to properly isolate this test
      //  from other tests making use of bobValidator and any residual traffic balance that may be left over as a result.
      s"be limited to base rate if unable to buy extra traffic" in { implicit env =>
        actAndCheck(
          "Create self-hosted wallet with insufficient balance",
          onboardWalletUser(bobWallet, bobValidator),
        )(
          "Taps throttled to base rate",
          _ => {
            val baseRateLimits = getBaseRateTrafficLimits()
            val (successes, totalTxs) =
              testProvidedRateAndAssertLogs(
                bobWallet,
                2 * baseRateLimits.rate.doubleValue(),
                baseRateLimits.burstWindow.microseconds,
              )
            successes.toDouble should be > 0.3 * totalTxs
            successes.toDouble should be < 0.7 * totalTxs
          },
        )
      }

      // aliceValidator is used in the subsequent tests as an example of a validator that will
      // purchase extra traffic by paying domain fees to the SVC
      s"be able to hit target throughput by buying extra traffic" in { implicit env =>
        actAndCheck(
          "Create self-hosted wallet with sufficient balance", {
            onboardWalletUser(aliceWallet, aliceValidator)
            aliceValidatorWallet.tap(1000)
          },
        )(
          "All tap operations are successful",
          _ => {
            // Advance time by 1 polling interval to ensure that the validator has
            // purchased extra traffic for the first time.
            advanceTimeByPollingInterval(aliceValidator)
            val baseRateLimits = getBaseRateTrafficLimits()
            val (successes, totalTxs) =
              testProvidedRateAndAssertLogs(
                aliceWallet,
                DomainFeesConstants.targetThroughput.value,
                baseRateLimits.burstWindow.microseconds,
              )
            successes shouldBe totalTxs
          },
        )
      }

      s"be limited to just around the target rate" in { implicit env =>
        actAndCheck(
          "Create self-hosted wallet with sufficient balance", {
            onboardWalletUser(aliceWallet, aliceValidator)
            aliceValidatorWallet.tap(1000)
          },
        )(
          "Taps throttled to target rate",
          _ => {
            // Advance time by 1 polling interval to ensure that the validator has
            // purchased extra traffic for the first time.
            advanceTimeByPollingInterval(aliceValidator)
            val baseRateLimits = getBaseRateTrafficLimits()
            val (successes, totalTxs) =
              testProvidedRateAndAssertLogs(
                aliceWallet,
                2 * DomainFeesConstants.targetThroughput.value,
                baseRateLimits.burstWindow.microseconds,
              )
            successes.toDouble should be > 0.3 * totalTxs
            successes.toDouble should be < 0.7 * totalTxs
          },
        )
      }

    }
  }

  private def getBaseRateTrafficLimits()(implicit env: CNNodeTestConsoleEnvironment) = {
    val coinRules = scan.getCoinRules()
    coinRules.payload.configSchedule.currentValue.domainFeesConfig.baseRateTrafficLimits
  }

  private def testProvidedRateAndAssertLogs(
      wallet: WalletAppClientReference,
      testRateMBps: Double,
      waitTimeMicros: Long,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    loggerFactory.assertLoggedWarningsAndErrorsSeq(
      {
        val coinTxRate = testRateMBps * 1e6 / DomainFeesConstants.assumedCoinTxSizeBytes.value
        val result = tryCoinTxs(wallet, coinTxRate)
        // Advance time to allow the base rate traffic limiter to reset.
        // Without this, the initial tap in subsequent tests may get throttled.
        advanceTime(
          Duration.ofNanos(waitTimeMicros * 1000)
        )
        result
      },
      lines => {
        forAll(lines) { line =>
          line.message should (include(
            "Aborted operation - insufficient validator credit to create coins"
          ))
        }
      },
    )
  }

  private def tryCoinTxs(
      wallet: WalletAppClientReference,
      coinTxsPerSecond: Double,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val totalTxs = (coinTxsPerSecond * loadTestDuration.toSeconds).toInt
    val deltaT = Duration.ofMillis((1e3 / coinTxsPerSecond).toLong)

    @tailrec
    def go(start: Int = 1, successes: Int = 0): Int = {
      def tapAndReturnOneOnSuccess() = {
        try {
          logger.debug(s"executing tap $start")
          wallet.tap(start)
          1
        } catch {
          case NonFatal(ex) =>
            logger.debug(s"Ignoring exception when executing tap $start", ex)
            0
        }
      }

      if (start > totalTxs) successes
      else {
        advanceTime(deltaT)
        val result = tapAndReturnOneOnSuccess()
        go(start + 1, successes + result)
      }
    }

    val successes = go()
    (successes, totalTxs)
  }
}
