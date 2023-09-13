package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.automation.leaderbased.AdvanceOpenMiningRoundTrigger
import com.daml.network.util.*
import com.daml.network.wallet.automation.CollectRewardsAndMergeCoinsTrigger
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class AutomationControlIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4, to speed up the test
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) },
      )
      // Very short round ticks
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppFoundCollectiveConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )

  private val waitTimeInMillis = 1000L

  "report correct rounds as round automation is controlled" in { implicit env =>
    // The current round, as seen by the SV1 scan service (reflects the state of the scan app store)
    def currentRoundInScan(): Long =
      sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

    // The current round, as seen by the SV1 participant
    def currentRoundOnLedger(): Long =
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(OpenMiningRound.COMPANION)(svcParty)
        .map(_.data.round.number)
        .max

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv leader changes)
    def trigger = sv1Backend.leaderBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    // Note: this is just to illustrate that nothing is happening while the automation is paused.
    def verifyRoundsAreNotAdvancing(prevRound: Long): org.scalatest.Assertion = {
      clue("Verify that no rounds are advancing over a long time interval") {
        Threading.sleep(waitTimeInMillis)
        currentRoundInScan() should be(prevRound)
        currentRoundOnLedger() should be(prevRound)
      }
    }

    // ------------------------------------------------------------------------
    // Trigger starts in a paused state (see environmentDefinition)
    // ------------------------------------------------------------------------

    // There are exactly 2 initial rounds created when bootstrapping the network.
    // This is performed synchronously, so we know for sure that the initial rounds
    // exist on the ledger.
    currentRoundOnLedger() should be(2)

    // Although very unlikely, the scan app store might not have ingested the initial rounds
    // at this point yet.
    eventually() {
      currentRoundInScan() should be(2)
    }

    verifyRoundsAreNotAdvancing(2)

    // ------------------------------------------------------------------------
    // Trigger is running
    // ------------------------------------------------------------------------
    actAndCheck(
      "Resume automation and note current round", {
        val roundInScanBeforeResume = currentRoundInScan()
        trigger.resume()
        roundInScanBeforeResume
      },
    )(
      "Observe that rounds are advancing",
      roundInScanBeforeResume => currentRoundInScan() should be > roundInScanBeforeResume,
    )

    // ------------------------------------------------------------------------
    // Pausing the trigger
    // ------------------------------------------------------------------------
    val (_, roundAfterPause) = actAndCheck(
      "Pause automation and wait until all changes have completed on the participant",
      trigger.pause().futureValue,
    )(
      "Wait until changes have propagated to the rest of the network (in this case, the scan app store)",
      _ => {
        val latestOnLedger = currentRoundOnLedger()
        val latestFromScan = currentRoundInScan()
        latestOnLedger should be(latestFromScan)
        latestOnLedger
      },
    )

    verifyRoundsAreNotAdvancing(roundAfterPause)

    // ------------------------------------------------------------------------
    // Triggering one task manually
    // ------------------------------------------------------------------------
    val prevRound = currentRoundInScan()

    clue("Trigger automation exactly once") {
      // Note: runOnce() does nothing if there is no work to be done.
      // If the previous test code runs fast enough, then there are no new rounds due to be created.
      // We could sleep for one tick, or just keep trying until something happens.
      eventually() {
        trigger.runOnce().futureValue should be(true)
      }
    }

    val roundAfterManualTrigger = clue("Observe that rounds have advanced once") {
      eventually() {
        val current = currentRoundInScan()
        current should be(prevRound + 1)
        current
      }
    }

    verifyRoundsAreNotAdvancing(roundAfterManualTrigger)

    // ------------------------------------------------------------------------
    // Resuming the trigger
    // ------------------------------------------------------------------------
    actAndCheck(
      "Observe current round and resume automation", {
        val prevRound = currentRoundInScan()
        trigger.resume()
        prevRound
      },
    )(
      "Observe that current round advances",
      prevRound => currentRoundInScan() should be > prevRound,
    )

  }

  "merge coins as automation is controlled" in { implicit env =>
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

    // The trigger that merges coins for alice
    // Note: using `def`, as the trigger may be destroyed and recreated (when user is offboarded and onboarded)
    def aliceMergeCoinsTrigger =
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .trigger[CollectRewardsAndMergeCoinsTrigger]

    // ------------------------------------------------------------------------
    // Pausing the trigger
    // ------------------------------------------------------------------------

    // In the previous test, we had to make sure the trigger starts in a paused state by modifying the automation
    // config using a CNNodeConfigTransforms, because the AdvanceOpenMiningRoundTrigger might start doing work
    // before we enter the test body.
    // In this case, it's ok to pause the CollectRewardsAndMergeCoinsTrigger inside the test body, because we
    // know the trigger won't do any work unless we explicitly give Alice some coins.
    aliceMergeCoinsTrigger.pause().futureValue

    actAndCheck(
      "Tap 2 coins for Alice", {
        aliceWalletClient.tap(50.0)
        aliceWalletClient.tap(50.0)
      },
    )(
      "Coins should appear in Alice's wallet",
      _ => aliceWalletClient.list().coins should have length 2,
    )

    // Note: this is just to illustrate that nothing is happening while Alice's automation is paused.
    clue("Verify that no coins are merged over a long time interval") {
      Threading.sleep(waitTimeInMillis)
      aliceWalletClient.list().coins should have length 2
    }

    // Meanwhile, when Charlie taps 2 coins, they are quickly merged into 1 coin
    actAndCheck(
      "Tap 2 coins for Charlie", {
        charlieWalletClient.tap(50.0)
        charlieWalletClient.tap(50.0)
      },
    )(
      "Charlie should see one merged coin",
      _ => {
        charlieWalletClient.balance().unlockedQty should be > BigDecimal(99.0)
        charlieWalletClient.list().coins should have length 1
      },
    )

    // ------------------------------------------------------------------------
    // Resuming the trigger
    // ------------------------------------------------------------------------

    // Note: aliceMergeCoinsTrigger.runOnce().futureValue guarantees that the coins have been merged
    // on the ledger, but this might not immediately be visible in the wallet app.
    actAndCheck(
      "Run merge coins automation once",
      // Note: runOnce() does nothing if there is no work to be done, but in this case we know
      // that the user wallet store knows about 2 coins
      aliceMergeCoinsTrigger.runOnce().futureValue,
    )(
      "Verify that coins were merged",
      workDone => {
        workDone should be(true)
        aliceWalletClient.list().coins should have length 1
      },
    )
  }
}
