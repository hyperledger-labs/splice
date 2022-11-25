package com.daml.network.integration.tests

import com.daml.network.console.WalletAppReference
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.{CoinUtil, CommonCoinAppInstanceReferences}
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  BaseIntegrationTest,
  IsolatedEnvironments,
  SharedEnvironment,
  TestConsoleEnvironment,
}
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import scala.concurrent.duration.*

/** Analogue to Canton's CommunityTests */
object CoinTests {
  type CoinTestConsoleEnvironment = TestConsoleEnvironment[CoinEnvironmentImpl]
  type SharedCoinEnvironment =
    SharedEnvironment[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
  type IsolatedCoinEnvironments =
    IsolatedEnvironments[CoinEnvironmentImpl, CoinTestConsoleEnvironment]

  trait CoinIntegrationTest
      extends BaseIntegrationTest[CoinEnvironmentImpl, CoinTestConsoleEnvironment]
      with IsolatedCoinEnvironments
      with CommonCoinAppInstanceReferences {

    override def environmentDefinition
        : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
      CoinEnvironmentDefinition
        .simpleTopology(this.getClass.getSimpleName)

    // Advance time by `duration`; works only if the used Canton instance uses simulated time.
    protected def advanceTime(
        duration: Duration
    )(implicit env: CoinTestConsoleEnvironment): Unit = {
      if (duration.isNegative()) {
        fail("Cannot advance time by negative duration.");
      } else if (!duration.isZero()) {
        // it doesn't seem to matter which participant we run these from - all get synced
        val now = svc.remoteParticipant.ledger_api.time.get()
        try {
          // actually advance the time
          svc.remoteParticipant.ledger_api.time.set(now, now.plus(duration))
        } catch {
          case _: CommandFailure =>
            fail(
              "Could not advance time. " +
                "Is Canton configured with `parameters.clock.type = sim-clock`?"
            )
        }
        // We don't get feedback about the success of setting the time here, so we check ourselves.
        if (svc.remoteParticipant.ledger_api.time.get() == now) {
          fail(
            "Could not advance time. " +
              "Are participants configured with `testing-time.type = monotonic-time`?"
          )
        }
      }
    }

    /** @param expectedQuantityRanges : lower and upper bounds for coins sorted by their initial quantity in ascending order. */
    def checkWallet(
        walletParty: PartyId,
        wallet: WalletAppReference,
        expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
    ): Unit = clue(s"checking wallet with $expectedQuantityRanges") {
      eventually(10.seconds, 500.millis) {
        val coins =
          wallet.list().coins.sortBy(coin => coin.contract.payload.quantity.initialQuantity)
        coins should have size (expectedQuantityRanges.size.toLong)
        coins
          .zip(expectedQuantityRanges)
          .foreach { case (coin, quantityBounds) =>
            coin.contract.payload.owner shouldBe walletParty.toPrim
            val coinQuantity =
              coin.contract.payload.quantity
            assertInRange(coinQuantity.initialQuantity, quantityBounds)
            coinQuantity.ratePerRound shouldBe
              CoinUtil.defaultHoldingFee
          }
      }
    }

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
      value should (be >= range._1 and be <= range._2)
    }

    /** A function abstracting the common pattern of acting and then waiting for the action to
      * eventually have its expected results.
      */
    def actAndCheck[T, U](
        action: String,
        actionExpr: => T,
    )(check: String, checkFun: T => U): (T, U) = {
      {
        val x = clue(s"(act) $action")(actionExpr)
        clue(s"(check) $check") {
          eventually() {
            val y = checkFun(x)
            (x, y)
          }
        }
      }
    }
  }
}
