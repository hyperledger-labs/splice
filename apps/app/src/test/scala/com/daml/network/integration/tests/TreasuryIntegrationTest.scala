package com.daml.network.integration.tests

import cats.syntax.traverse.*
import com.daml.network.config.CoinConfigTransforms.updateAllWalletAppBackendConfigs_
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.daml.network.util.WalletTestUtil
import com.daml.network.wallet.config.TreasuryConfig
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.concurrent.Threading
import io.grpc.StatusRuntimeException
import monocle.macros.syntax.lens.*
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future}

class TreasuryIntegrationTest
    extends CoinIntegrationTest
    with HasExecutionContext
    with WalletTestUtil {

  private val batchSize = 10
  // need a large queue size so we can guarantee that it is still pretty full once we shutdown.
  private val queueSize = 150

  override def environmentDefinition = {
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, coinConfig) =>
        updateAllWalletAppBackendConfigs_(walletConfig =>
          walletConfig.focus(_.treasury).replace(TreasuryConfig(batchSize, queueSize))
        )(coinConfig)
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
}
