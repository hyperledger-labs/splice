package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.config.ConfigTransforms
import com.daml.network.config.ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.console.ScanAppBackendReference
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.environment.ledger.api.TransactionTreeUpdate
import com.daml.network.http.v0.definitions
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.scan.admin.http.LosslessScanHttpEncodings
import com.daml.network.scan.automation.ScanHistoryBackfillingTrigger
import com.daml.network.store.{PageLimit, TreeUpdateWithMigrationId}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.daml.network.sv.automation.delegatebased.AdvanceOpenMiningRoundTrigger
import com.daml.network.util.{UpdateHistoryTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.scalactic.source.Position

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class ScanBackfillingIntegrationTest
    extends IntegrationTest
    with UpdateHistoryTestUtil
    with WalletTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.withPausedTrigger[ScanHistoryBackfillingTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      .withTrafficTopupsDisabled
      .withManualStart

  "scan can backfill update history" in { implicit env =>
    val tapAmount = com.digitalasset.daml.lf.data.Numeric.assertFromString("42.0")

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv delegate changes)
    def advanceRoundsTrigger = sv1Backend.dsoDelegateBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    val ledgerBeginSv1 = sv1Backend.participantClient.ledger_api.state.end()

    clue(s"Backfilling is configured") {
      // Configuration is set in `ConfigTransforms.enableScanHistoryBackfilling`
      sv1ScanBackend.config.updateHistoryBackfillEnabled should be(true)
      sv1ScanBackend.config.updateHistoryBackfillFromScanURL should be(empty)

      sv2ScanBackend.config.updateHistoryBackfillEnabled should be(true)
      sv2ScanBackend.config.updateHistoryBackfillFromScanURL should not be empty
    }

    clue(s"Starting CN nodes: SV1 and Alice validator") {
      startAllSync(
        sv1Backend,
        sv1ScanBackend,
        sv1ValidatorBackend,
        aliceValidatorBackend,
      )
    }

    actAndCheck(
      "Tap some amulets for Alice", {
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(tapAmount)
        aliceWalletClient.tap(tapAmount + 1)
        aliceWalletClient.tap(tapAmount + 2)
        aliceWalletClient.tap(tapAmount + 3)
      },
    )(
      "Amulets should appear in Alice's wallet",
      _ => {
        // Amulet merging and round advancement are both paused
        aliceWalletClient.list().amulets should have length 4
      },
    )

    // The current round, as seen by the given scan service (reflects the state of the scan app store)
    def currentRoundInScan(backend: ScanAppBackendReference): Long =
      backend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

    actAndCheck(
      "Advance one round, to commit transactions related to the round infrastructure", {
        val previousRound = currentRoundInScan(sv1ScanBackend)
        // Note: runOnce() does nothing if there is no work to be done.
        eventually() {
          advanceRoundsTrigger.runOnce().futureValue should be(true)
        }
        previousRound
      },
    )(
      "Observe that rounds have advanced once",
      previous => {
        val currentRound = currentRoundInScan(sv1ScanBackend)
        currentRound should be(previous + 1)
        currentRound
      },
    )

    // TODO(#14270): Also start SV3 and SV4
    clue(s"Starting CN nodes: SV2") {
      startAllSync(
        sv2Backend,
        sv2ScanBackend,
        sv2ValidatorBackend,
      )
    }

    clue("Make sure SV2 is fully onboarded and operational") {
      eventually() {
        val dsoInfo = sv1Backend.getDsoInfo()
        val scans = for {
          (_, nodeState) <- dsoInfo.svNodeStates
          (_, synchronizerNode) <- nodeState.payload.state.synchronizerNodes.asScala
          scan <- synchronizerNode.scan.toScala
        } yield scan
        scans should have size 2 // sv1&2's scans
      }
    }

    // Add another update on which we can easily synchronize the update histories of the two scans
    val (_, latestAmuletCid) = actAndCheck(
      "Tap some more amulets for Alice", {
        aliceWalletClient.tap(tapAmount + 4)
      },
    )(
      "Amulets should appear in Alice's wallet",
      _ => {
        // Amulet merging and round advancement are both paused
        val amulets = aliceWalletClient.list().amulets
        amulets should have length 5
        amulets
          .find(_.contract.payload.amount.initialAmount > walletUsdToAmulet(tapAmount + 3.9))
          .value
          .contract
          .contract
          .contractId
          .contractId
      },
    )

    val sv1updatesBeforeBackfill = clue(s"SV1 scan has ingested the latest update") {
      eventually() {
        val updates = allUpdatesFromScanBackend(sv1ScanBackend)
        updates should not be empty
        containsCreateEvent(updates, latestAmuletCid) should be(true)
        updates
      }
    }
    val sv2updatesBeforeBackfill = clue(s"SV2 scan has ingested the latest update") {
      eventually() {
        val updates = allUpdatesFromScanBackend(sv2ScanBackend)
        updates should not be empty
        containsCreateEvent(updates, latestAmuletCid) should be(true)
        updates
      }
    }

    clue(s"First few items in SV1s history do not exist in SV2s history") {
      // SV2 missed a few updates. Because some of these updates came from triggers that
      // have nothing to do with the test flow, this number is likely flaky.
      // The number 10 was picked after counting 19 updates in a successful test run.
      val N = 10
      val sv1times = sv1updatesBeforeBackfill.take(N).map(itemTime).toSet
      val sv2times = sv2updatesBeforeBackfill.map(itemTime).toSet
      sv1times.foreach(sv1time => sv2times should not contain sv1time)
    }

    clue(
      s"First update in SV2s history has a different projection than the same update in SV1s history"
    ) {
      // At the beginning of SV2s history, SV2 was not part of the DSO and thus they both see the same transaction differently
      val firstUpdateIdForSv2 = sv2updatesBeforeBackfill.headOption.value.update.update.updateId
      val sv1Tree = sv1updatesBeforeBackfill
        .find(_.update.update.updateId == firstUpdateIdForSv2)
        .value
        .transactionTree
      val sv2Tree = sv2updatesBeforeBackfill
        .find(_.update.update.updateId == firstUpdateIdForSv2)
        .value
        .transactionTree
      sv1Tree.getUpdateId should be(sv2Tree.getUpdateId)
      sv1Tree.getEventsById.keySet() should not be sv2Tree.getEventsById.keySet()
    }

    clue("SV2 scan HTTP API refuses to return history") {
      assertThrowsAndLogsCommandFailures(
        sv2ScanBackend.getUpdateHistory(1000, None, true),
        _.errorMessage should include("This scan instance has not yet replicated all data"),
      )
    }

    clue("Debug print history before backfilling") {
      env.scans.local.filter(_.is_initialized).foreach { scan =>
        logger.debug(
          s"${scan.name} history before backfilling: " + shortDebugDescription(
            allUpdatesFromScanBackend(scan).map(LosslessScanHttpEncodings.lapiToHttpUpdate)
          )
        )
      }
    }

    actAndCheck(
      "Start backfilling on all scans", {
        sv1BackfillTrigger.resume()
        sv2BackfillTrigger.resume()
      },
    )(
      "All backfilling is complete",
      _ => {
        sv1ScanBackend.appState.store.updateHistory
          .getBackfillingState()
          .futureValue
          .exists(_.complete) should be(true)
        sv2ScanBackend.appState.store.updateHistory
          .getBackfillingState()
          .futureValue
          .exists(_.complete) should be(true)
      },
    )

    clue("Debug print history after backfilling") {
      env.scans.local.filter(_.is_initialized).foreach { scan =>
        logger.debug(
          s"${scan.name} history after backfilling: " + shortDebugDescription(
            allUpdatesFromScanBackend(scan).map(LosslessScanHttpEncodings.lapiToHttpUpdate)
          )
        )
      }
    }

    clue("Compare scan histories with each other") {
      val sv2updatesAfterBackfill = allUpdatesFromScanBackend(sv2ScanBackend)

      // Again we can't compare using strict equality, as the items contain offsets which are participant-local.
      val sv1Times = sv1updatesBeforeBackfill.map(itemTime)
      val sv2Times = sv2updatesAfterBackfill.take(sv1Times.length).map(itemTime)
      sv1Times should contain theSameElementsInOrderAs sv2Times
    }

    clue("Compare scan histories with each other using the HTTP endpoint") {
      val sv1HttpUpdates = sv1ScanBackend.getUpdateHistory(1000, None, true)
      val sv2HttpUpdates = sv2ScanBackend.getUpdateHistory(1000, None, true)

      // Compare common prefix, as there might be concurrent activity
      val commonLength = sv1HttpUpdates.length min sv2HttpUpdates.length
      commonLength should be > 10
      val sv1ItemTimes = sv1HttpUpdates.take(commonLength).map(httpItemTime)
      val sv2ItemTimes = sv2HttpUpdates.take(commonLength).map(httpItemTime)
      sv1ItemTimes should contain theSameElementsInOrderAs sv2ItemTimes
    }

    clue("Compare scan history with participant update stream") {
      compareHistory(
        sv1Backend.participantClient,
        sv1ScanBackend.appState.store.updateHistory,
        ledgerBeginSv1,
      )
    }

    clue("Backfilling triggers are not doing any work after backfilling is complete") {
      sv1BackfillTrigger.retrieveTasks().futureValue should be(empty)
      sv2BackfillTrigger.retrieveTasks().futureValue should be(empty)
    }
  }

  private def sv1BackfillTrigger(implicit env: SpliceTestConsoleEnvironment) =
    sv1ScanBackend.automation.trigger[ScanHistoryBackfillingTrigger]
  private def sv2BackfillTrigger(implicit env: SpliceTestConsoleEnvironment) =
    sv2ScanBackend.automation.trigger[ScanHistoryBackfillingTrigger]

  private def allUpdatesFromScanBackend(scanBackend: ScanAppBackendReference) = {
    // Need to use the store directly, as the HTTP endpoint refuses to return data unless it's completely backfilled
    scanBackend.appState.store.updateHistory
      .getUpdates(None, false, PageLimit.tryCreate(1000))
      .futureValue
  }

  private def containsCreateEvent(updates: Seq[TreeUpdateWithMigrationId], cid: String): Boolean =
    updates.exists(_.update.update match {
      case TransactionTreeUpdate(tree) =>
        tree.getEventsById.asScala.values.exists(ev => ev.getContractId == cid)
      case _ => false
    })

  // We can't compare updates from different scans using strict equality, as the items contain offsets which are participant-local.
  // In this test we just compare the record times as they are sufficiently unique and easy to debug.
  private def httpItemTime(item: definitions.UpdateHistoryItem): CantonTimestamp = item match {
    case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(http) =>
      CantonTimestamp.assertFromInstant(java.time.Instant.parse(http.recordTime))
    case definitions.UpdateHistoryItem.members.UpdateHistoryReassignment(http) =>
      CantonTimestamp.assertFromInstant(java.time.Instant.parse(http.recordTime))
  }
  private def itemTime(t: TreeUpdateWithMigrationId): CantonTimestamp = t.update.update.recordTime

  implicit class TreeUpdateTestSyntax(update: TreeUpdateWithMigrationId) {
    def transactionTree(implicit pos: Position): TransactionTree = update.update.update match {
      case TransactionTreeUpdate(tree) => tree
      case _ => fail(s"Expected a TransactionTreeUpdate, got: ${update.update}")(pos)
    }
  }
}
