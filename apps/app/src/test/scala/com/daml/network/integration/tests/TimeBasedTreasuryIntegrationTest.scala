package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.config.CoinConfigTransforms.{
  setPollingInterval,
  updateAllWalletAppBackendConfigs_,
}
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.{CoinUtil, TimeTestUtil, WalletTestUtil}
import com.daml.network.wallet.config.TreasuryConfig
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.{HasExecutionContext, time}
import io.grpc.StatusRuntimeException
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import java.time.Duration
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class TimeBasedTreasuryIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TimeTestUtil {

  private val batchSize = 10
  // need a large queue size so we can guarantee that it is still pretty full once we shutdown.
  private val queueSize = 150

  override def environmentDefinition: CoinEnvironmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .addConfigTransform((_, coinConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig.focus(_.treasury).replace(TreasuryConfig(batchSize, queueSize))
        )(coinConfig)
      )
      .addConfigTransform((_, config) =>
        // for testing non-automation-based coin merging.
        setPollingInterval(time.NonNegativeFiniteDuration.ofSeconds(30))(config)
      )
  }

  "shutdown cleanly with lots of coin operations in flight" in { implicit env =>
    onboardWalletUser(aliceWallet, aliceValidator)

    def tapInRange(start: Int, end: Int) = {
      Range(start, end).toList.traverse(i =>
        Future {
          try {
            aliceWallet.tap(i)
          } catch {
            case scala.util.control.NonFatal(ex) =>
              logger.info("Ignoring exception when executing tap", ex)
          }
        }
      )
    }

    loggerFactory.assertLoggedWarningsAndErrorsSeq(
      {
        // waiting for a few taps succeed - so...
        val futuresWait = tapInRange(1, batchSize)
        // .. these taps here can fill up the queue
        val futuresDontWait = tapInRange(batchSize + 1, queueSize + batchSize * 5)

        Await.result(futuresWait, atMost = 15.seconds)
        // such that some of them will still be in-flight when we shutdown
        futuresDontWait.isCompleted shouldBe false

        clue("Stopping alice's wallet") {
          // In manual runs, we've observed that sometimes the gRPC server reports a slow shutdown.
          // We accept that for now.
          aliceWalletBackend.stop()
        }
        // sleeping 1s, as sometimes error from TODO(#1942) are logged delayed.
        Threading.sleep(1000)
      },
      lines =>
        forAll(lines) { line =>
          // \r is carriage return
          val regexAnything = "(.|\\n|\\r)*"
          val errorRegex = Seq(
            "Request failed for aliceWallet",
            "(ABORTED|UNAVAILABLE|CANCELLED|UNIMPLEMENTED)",
          ).mkString(regexAnything)

          val errorRegex2 = // TODO(#1942): these errors shouldn't occur during this test.
            s"(Skipping batch due to unexpected execution failure|Unexpected coin operation execution failure)"

          if (line.level == Level.ERROR) {
            line.throwable match {
              // TODO(#1942): we are only aware of this unhandled exception during shutdowns.
              case Some(throwable: StatusRuntimeException) if line.message.matches(errorRegex2) =>
                throwable.getMessage should include("UNAVAILABLE")
              case None =>
              case other => fail(s"unexpected exception: $other")
            }
            line.message should (include regex errorRegex or include regex errorRegex2)

          } else if (line.level == Level.WARN) {
            // If the coin operation buffer is large or batch execution is slow, then shutdown is not quick enough.
            // We accept that for now, as it is non-trivial to propagate the shutdown signal from the server to the
            // coin operation batch executor.
            line.message should include regex ("NettyServer.*shutdown did not complete gracefully")
          } else {
            fail(s"unexpected warning or error: $line")
          }
        },
    )
  }

  "automatically merge transfer inputs when the automation is triggered" in { implicit env =>
    val (alice, bob) = onboardAliceAndBob()
    // create two coins in alice's wallet
    aliceWallet.tap(50)
    checkWallet(alice, aliceWallet, Seq(exactly(50)))

    // run a transfer such that alice and her validator have some rewards
    p2pTransfer(aliceWallet, bobWallet, bob, 40.0, 0.0)
    eventually()(aliceWallet.listAppRewardCoupons() should have size 1)
    eventually()(aliceValidatorWallet.listValidatorRewardCoupons() should have size 1)
    // and give alice another coin.
    aliceWallet.tap(50)
    checkWallet(alice, aliceWallet, Seq((9, 10), exactly(50)))

    // advance by two ticks, so the issuing round of round 1 is created
    advanceRoundsByOneTick
    advanceRoundsByOneTick

    // advance time such that issuing round 1 is open to rewards collection.
    advanceRoundsByOneTick

    eventually()({ // rewards are automatically collected
      aliceWallet.listAppRewardCoupons().filter(_.payload.round.number == 1) should have size 0
      // and coins are automatically merged.
      checkWallet(alice, aliceWallet, Seq((59, 61)))
      // same for aliceValidator's wallet
      aliceValidatorWallet
        .listValidatorRewardCoupons()
        .filter(_.payload.round.number == 1) should have size 0
    })
  }

  "allow calling tap, list the created coins, and get the balance - locally and remotely" in {
    implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val aliceValidatorParty = aliceValidator.getValidatorPartyId()
      aliceWallet.tap(110)

      checkBalance(aliceWallet, 1, exactly(110), exactly(0), exactly(0))
      // leads to archival of open round 0
      advanceRoundsByOneTick

      lockCoins(
        aliceWalletBackend,
        aliceUserParty,
        aliceValidatorParty,
        aliceWallet.list().coins,
        10,
        scan.getAppTransferContext(),
        Duration.ofDays(10),
      )
      checkBalance(
        aliceWallet,
        2,
        (99, 100),
        exactly(10),
        // due to merge in this round, no holding fees.
        exactly(0),
      )

      // leads to latest round being round 3
      advanceRoundsByOneTick

      checkBalance(
        aliceWallet,
        3,
        (99, 100),
        (9, 10),
        exactly(CoinUtil.defaultHoldingFee.rate),
      )
  }

  "don't collect rewards if their collection is more expensive than they reward in coins" in {
    implicit env =>
      val (_, bob) = onboardAliceAndBob()

      // giving alice 2 coins...
      aliceWallet.tap(1)
      aliceWallet.tap(1)
      eventually() {
        aliceWallet.list().coins should have length 2
      }
      // ..so when she pays bob, she doesn't have to pay a transfer fee which
      // will result in alice validator's reward being small enough that its not worth it to collect the reward
      p2pTransfer(aliceWallet, bobWallet, bob, 0.00001)
      eventually() {
        aliceWallet.listAppRewardCoupons() should have length 1
        aliceValidatorWallet.listValidatorRewardCoupons() should have length 1
      }

      // advancing the rounds so the rewards would be collectable.
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          // reward is now collectable..
          advanceRoundsByOneTick
          Threading.sleep(3.seconds.toMillis)
        },
        entries => {
          forAtLeast(1, entries)( // however, we see that we choose not to the validator reward..
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )

      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
        {
          aliceValidatorWallet.tap(1)
          eventually() {
            aliceValidatorWallet.list().coins should have length 1
          }
          advanceTime(Duration.ofMinutes(1))
          Threading.sleep(3.seconds.toMillis)
        },
        entries => {
          forAtLeast(
            1,
            entries,
          )( // .. even when alice's validator has another coin and would only need to pay
            // an create-fee for collecting the reward.
            _.message should include(
              "is smaller than the create-fee"
            )
          )
        },
      )
  }

  "don't run merge if rewards and coins are too small" in { implicit env =>
    val (_, _) = onboardAliceAndBob()
    aliceWallet.tap(0.001)
    aliceWallet.tap(0.001)

    eventually() {
      aliceWallet.list().coins should have length 2
    }

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {
        // trigger automation.
        advanceRoundsByOneTick
        // TODO(#2414): remove magic number.
        Threading.sleep(3.seconds.toMillis)
      },
      entries => {
        forAtLeast(
          1,
          entries,
        )(
          // but do nothing since our coins are too small to be worth merging.
          _.message should include regex (
            "the total rewards and coin quantity .* is smaller than the create-fee"
          )
        )
      },
    )
  }

}
