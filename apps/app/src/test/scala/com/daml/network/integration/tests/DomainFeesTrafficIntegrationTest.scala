package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.config.CNNodeConfigTransforms.updateAllWalletAppBackendConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.SuppressionRule
import monocle.macros.syntax.lens.*
import org.scalatest.Ignore
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.prop.TableFor1
import org.scalatest.time.{Seconds, Span}
import org.slf4j.event.Level

import scala.concurrent.Future
import scala.concurrent.duration.*

/** This test submits a certain of coin transactions equidistant over an interval targeting a certain coins txs/s.
  * It tests that if we configure the domain-fees free-rate to be a certain amount of txs/s, clients can
  * actually approximately submit coin txs at that frequency.
  */
@Ignore
class DomainFeesTrafficIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil {

  override def environmentDefinition: CNNodeEnvironmentDefinition = {
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withoutAutomaticRewardsCollectionAndCoinMerging
      .withHttpSettingsForHigherThroughput
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig.focus(_.treasury.batchSize).replace(1)
        )(cnNodeConfig)
      )
      .addConfigTransform((_, cnNodeConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig.focus(_.treasury.enableValidatorCreditChecks).replace(true)
        )(cnNodeConfig)
      )
  }

  private lazy val loadTestDuration = 10.seconds
  private lazy val defaultThroughputRate = 2
  private lazy val testCases: TableFor1[Double] = Table("coinTxsPerSecond", 2, 4)

  testCases.forEvery { case (coinTxsPerSecond) =>
    s"coinTxsPerSecond of $coinTxsPerSecond is attempted" in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)
      val totalTxAttempts = (loadTestDuration.toSeconds * coinTxsPerSecond).toInt

      def sendCommands() = {
        // Submit commands for loadTestDuration at an even rate
        val sleepDurationInMilli = (1 / coinTxsPerSecond * 1e3).toLong
        logger.info(s"sleeping for $sleepDurationInMilli millis")
        Range(1, totalTxAttempts + 1).toList
          .traverse(i => {
            val future = Future {
              try {
                logger.debug(s"executing tap $i")
                aliceWallet.tap(i)
                1
              } catch {
                case scala.util.control.NonFatal(ex) =>
                  logger.debug(s"Ignoring exception when executing tap $i", ex)
                  0
              }
            }
            Threading.sleep(sleepDurationInMilli)
            future
          })
          .map(_.sum)
      }

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
        {
          val successesF = sendCommands()
          // The test fails at this point, if we are submitting at a rate that is higher than the amount of coin txs/s
          // the system is able to handle. We add a grace period of 3 seconds for the last-submitted commands to go through.
          val successes =
            successesF.futureValue(timeout = Timeout(Span(loadTestDuration.toSeconds + 3, Seconds)))
          logger.debug(s"${successes}/${totalTxAttempts} coin transaction attempts successful")
          if (coinTxsPerSecond <= defaultThroughputRate) {
            // if the tx/s being tested is less than the default throughput rate, all attempts should be successful.
            successes shouldBe totalTxAttempts
          } else {
            // if the tx/s is more than the default throughput rate, they should be throttled to around the default throughput rate
            successes.toDouble should (be >= (defaultThroughputRate * loadTestDuration.toSeconds).toDouble and be < 1.5 * totalTxAttempts)
          }
        },
        lines => {
          forAll(lines) { line =>
            line.message should (include regex (
              """'execute coin operation batch' failed with a non-retryable error(.|\n)*statusCode=ABORTED"""
            ) or include(
              "Skipping batch due to unexpected execution failure"
            ) or include(
              "Unexpected coin operation execution failure of operation CO_Tap"
            ))
          }
        },
      )
    }
  }

}
