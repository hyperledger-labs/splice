package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp

class AutomationControlIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // start only sv1 but not sv2-4, to speed up the test
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Very short round ticks
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
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
        .filterJava(OpenMiningRound.COMPANION)(dsoParty)
        .map(_.data.round.number)
        .max

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated
    def trigger = sv1Backend.dsoDelegateBasedAutomation
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

  "merge amulets as automation is controlled" in { implicit env =>
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    onboardWalletUser(charlieWalletClient, aliceValidatorBackend)

    // The trigger that merges amulets for alice
    // Note: using `def`, as the trigger may be destroyed and recreated (when user is offboarded and onboarded)
    def aliceMergeAmuletsTrigger =
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    // ------------------------------------------------------------------------
    // Pausing the trigger
    // ------------------------------------------------------------------------

    // In the previous test, we had to make sure the trigger starts in a paused state by modifying the automation
    // config using a ConfigTransforms, because the AdvanceOpenMiningRoundTrigger might start doing work
    // before we enter the test body.
    // In this case, it's ok to pause the CollectRewardsAndMergeAmuletsTrigger inside the test body, because we
    // know the trigger won't do any work unless we explicitly give Alice some amulets.
    aliceMergeAmuletsTrigger.pause().futureValue

    actAndCheck(
      "Tap 2 amulets for Alice", {
        aliceWalletClient.tap(50.0)
        aliceWalletClient.tap(50.0)
      },
    )(
      "Amulets should appear in Alice's wallet",
      _ => aliceWalletClient.list().amulets should have length 2,
    )

    // Note: this is just to illustrate that nothing is happening while Alice's automation is paused.
    clue("Verify that no amulets are merged over a long time interval") {
      Threading.sleep(waitTimeInMillis)
      aliceWalletClient.list().amulets should have length 2
    }

    // Meanwhile, when Charlie taps 2 amulets, they are quickly merged into 1 amulet
    actAndCheck(
      "Tap 2 amulets for Charlie", {
        charlieWalletClient.tap(50.0)
        charlieWalletClient.tap(50.0)
      },
    )(
      "Charlie should see one merged amulet",
      _ => {
        charlieWalletClient.balance().unlockedQty should be > BigDecimal(99.0)
        charlieWalletClient.list().amulets should have length 1
      },
    )

    // ------------------------------------------------------------------------
    // Resuming the trigger
    // ------------------------------------------------------------------------

    // Note: aliceMergeAmuletsTrigger.runOnce().futureValue guarantees that the amulets have been merged
    // on the ledger, but this might not immediately be visible in the wallet app.
    actAndCheck(
      "Run merge amulets automation once",
      // Note: runOnce() does nothing if there is no work to be done, but in this case we know
      // that the user wallet store knows about 2 amulets
      aliceMergeAmuletsTrigger.runOnce().futureValue,
    )(
      "Verify that amulets were merged",
      workDone => {
        workDone should be(true)
        aliceWalletClient.list().amulets should have length 1
      },
    )
  }
}
