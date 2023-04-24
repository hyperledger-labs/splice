package com.daml.network.integration.tests

import com.daml.network.config.CNNodeConfigTransforms.updateAllValidatorConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.wallet.store.UserWalletTxLogParser.TxLogEntry as walletLogEntry
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
    with WalletTxLogTestUtil
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

  // TODO(#3816): Move this test to WalletTxLogWithRewardsCollectionTimeBasedIntegrationTest once
  //  the env config flags have been removed
  "A validator wallet's tx log" should {
    "handle domain fees properly" in { implicit env =>
      clue("Create validator wallet with sufficient balance") {
        onboardWalletUser(bobWallet, bobValidator)
        bobValidatorWallet.tap(20)
      }
      actAndCheck(
        "Purchase extra traffic",
        // Advance time by 1 polling interval to allow the automation
        // to kick in and purchase extra traffic.
        advanceTimeByPollingInterval(bobValidator),
      )(
        "Verify the transaction history",
        _ => {
          // Amount of fees paid equals amount of extra traffic purchased since 1 CC = 1 Traffic Unit
          val domainFeesPaid = BigDecimal(
            (DomainFeesConstants.targetThroughput.value - DomainFeesConstants.defaultThroughput.value) *
              bobValidator.config.automation.pollingInterval.duration.toSeconds
          )
          bobValidatorWallet.balance().unlockedQty should be < BigDecimal(20)
          checkTxHistory(
            bobValidatorWallet,
            Seq[CheckTxHistoryFn](
              { case logEntry: walletLogEntry.Transfer =>
                // Payment of domain fees by validator to SVC
                logEntry.transactionSubtype shouldBe walletLogEntry.Transfer.ExtraTrafficPurchase
                inside(logEntry.sender) { case (sender, amount) =>
                  sender shouldBe bobValidator.getValidatorPartyId().toProtoPrimitive
                  amount should beWithin(-domainFeesPaid - smallAmount, -domainFeesPaid)
                }
                inside(logEntry.receivers) { case Seq((receiver, amount)) =>
                  receiver shouldBe svcParty.toProtoPrimitive
                  // domain fees paid is immediately burnt by SVC
                  amount shouldBe 0
                }
              },
              { case logEntry: walletLogEntry.BalanceChange =>
                logEntry.transactionSubtype shouldBe walletLogEntry.BalanceChange.Tap
              },
            ),
          )
        },
      )
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
