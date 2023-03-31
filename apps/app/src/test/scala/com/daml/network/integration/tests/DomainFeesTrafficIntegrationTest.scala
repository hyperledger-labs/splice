package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.config.CNNodeConfigTransforms.updateAllWalletAppBackendConfigs_
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTestWithSharedEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.Threading
import monocle.macros.syntax.lens.*
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.prop.TableFor1
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.Future
import scala.concurrent.duration.*

/** This test submits a certain of coin transactions equidistant over an interval targeting a certain coins txs/s.
  * It tests that if we configure the domain-fees free-rate to be a certain amount of txs/s, clients can
  * actually approximately submit coin txs at that frequency.
  */
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
  // Locally, I observe a rate of up to ~6-7 txs/s. On CI (more powerful machine), its slightly higher.
  private lazy val testCases: TableFor1[Double] = Table("coinTxsPerSecond", 2, 4)

  testCases.forEvery { case (coinTxsPerSecond) =>
    s"coinTxsPerSecond of $coinTxsPerSecond is hit " in { implicit env =>
      onboardWalletUser(aliceWallet, aliceValidator)

      def sendCommands() = {
        // Submit commands for loadTestDuration
        val end = (loadTestDuration.toSeconds * coinTxsPerSecond).toInt
        // .. at an even rate
        val sleepDurationInMilli = (1 / coinTxsPerSecond * 1e3).toLong
        logger.info(s"sleeping for $sleepDurationInMilli millis")
        Range(1, end + 1).toList
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

      val successesF = sendCommands()
      // The test fails at this point, if we are submitting at a rate that is higher than the amount of coin txs/s
      // the system is able to handle. We add a grace period of 2 seconds for the last-submitted commands to go through.
      successesF.futureValue(timeout = Timeout(Span(loadTestDuration.toSeconds + 2, Seconds)))
    }
  }

}
