package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
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
    s"default throughput is ${DomainFeesConstants.defaultThroughput} txs/s & target throughput is ${DomainFeesConstants.targetThroughput} txs/s" must {
      s"be limited to default rate if unable to buy extra traffic" in { implicit env =>
        actAndCheck(
          "Create self-hosted wallet with insufficient balance",
          onboardWalletUser(aliceWallet, aliceValidator),
        )(
          "Taps throttled to default throughput rate",
          _ => {
            val (successes, totalTxs) =
              testCoinTxsAndAssertLogs(2 * DomainFeesConstants.defaultThroughput.value)
            successes.toDouble should be > 0.3 * totalTxs
            successes.toDouble should be < 0.7 * totalTxs
          },
        )
      }

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
            val (successes, totalTxs) =
              testCoinTxsAndAssertLogs(DomainFeesConstants.targetThroughput.value)
            successes shouldBe totalTxs
          },
        )
      }

      s"be limited to target rate" in { implicit env =>
        actAndCheck(
          "Create self-hosted wallet with sufficient balance", {
            onboardWalletUser(aliceWallet, aliceValidator)
            aliceValidatorWallet.tap(1000)
          },
        )(
          "Taps throttled to target throughput rate",
          _ => {
            // Advance time by 1 polling interval to ensure that the validator has
            // purchased extra traffic for the first time.
            advanceTimeByPollingInterval(aliceValidator)
            val (successes, totalTxs) =
              testCoinTxsAndAssertLogs(2 * DomainFeesConstants.targetThroughput.value)
            successes.toDouble should be > 0.3 * totalTxs
            successes.toDouble should be < 0.7 * totalTxs
          },
        )
      }
    }
  }

  private def testCoinTxsAndAssertLogs(
      coinTxsPerSecond: Double
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    loggerFactory.assertLoggedWarningsAndErrorsSeq(
      {
        val result = tryCoinTxs(coinTxsPerSecond)
        // Advance time to allow the default traffic limiter to reset.
        // Without this, the initial tap in subsequent tests may get throttled.
        advanceTime(
          Duration.ofMillis((DomainFeesConstants.defaultTrafficBurstWindow.value * 1e3).toLong)
        )
        result
      },
      lines => {
        forAll(lines) { line =>
          line.message should (include(
            "Aborted operation - insufficient validator credit to create coins"
          ) or include(
            // TODO(#4067): Remove this and properly handle the coin archival in tx log parsers
            "Unexpected coin archive event"
          ))
        }
      },
    )
  }

  private def tryCoinTxs(
      coinTxsPerSecond: Double
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val totalTxs = (coinTxsPerSecond * loadTestDuration.toSeconds).toInt
    val deltaT = Duration.ofMillis((1e3 / coinTxsPerSecond).toLong)

    @tailrec
    def go(start: Int = 1, successes: Int = 0): Int = {
      def tapAndReturnOneOnSuccess() = {
        try {
          logger.debug(s"executing tap $start")
          aliceWallet.tap(start)
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
