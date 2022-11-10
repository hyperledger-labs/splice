package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.CommonCoinAppInstanceReferences
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.integration.{
  BaseEnvironmentDefinition,
  BaseIntegrationTest,
  IsolatedEnvironments,
  SharedEnvironment,
  TestConsoleEnvironment,
}

import java.time.Duration

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
  }
}
