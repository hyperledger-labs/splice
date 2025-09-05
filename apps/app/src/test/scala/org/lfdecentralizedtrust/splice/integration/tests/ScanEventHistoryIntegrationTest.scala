package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{IntegrationTest}
import org.lfdecentralizedtrust.splice.scan.config.BftSequencerConfig

import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.util.*

import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.http.v0.definitions

import scala.concurrent.duration.*
import scala.util.Try

class ScanEventHistoryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        (updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        ) andThen
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          ))(config)
      )
      // Removed configurable groupWithin; using constant in ingestion
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(config =>
          config.copy(
            bftSequencers = Seq(
              BftSequencerConfig(
                config.domainMigrationId,
                config.sequencerAdminClient,
                "http://testUrl:8081",
              )
            ),
            parameters =
              config.parameters.copy(customTimeouts = config.parameters.customTimeouts.map {
                // guaranteeing a timeout for first test below
                case (key @ "getAcsSnapshot", _) =>
                  key -> NonNegativeFiniteDuration.ofMillis(1L)
                case other => other
              }),
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsEnabled

  "getEventHistory can provide new events with verdicts" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()

    def cursorOf(item: definitions.EventHistoryItem): Option[(Long, String)] = {
      val updateCursor: Option[(Long, String)] = item.update.flatMap {
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
          Some((tx.migrationId, tx.recordTime))
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryReassignment(r) =>
          r.event match {
            case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(ev) =>
              Some((ev.migrationId, r.recordTime))
            case definitions.UpdateHistoryReassignment.Event.members
                  .UpdateHistoryUnassignment(ev) =>
              Some((ev.migrationId, r.recordTime))
          }
      }
      updateCursor.orElse(item.verdict.map(v => (v.migrationId, v.recordTime)))
    }

    // Read and paginate all current events to obtain the last cursor
    val lastCursorBeforeTap = eventuallySucceeds() {
      @annotation.tailrec
      def go(
          after: Option[(Long, String)],
          acc: Option[(Long, String)],
      ): Option[(Long, String)] = {
        val page = sv1ScanBackend.getEventHistory(
          count = 100,
          after = after,
          encoding = definitions.DamlValueEncoding.CompactJson,
        )
        if (page.isEmpty) acc
        else {
          val next = page.lastOption.flatMap(cursorOf)
          val newAcc = next.orElse(acc)
          next match {
            case Some(c) => go(Some(c), newAcc)
            case None => newAcc
          }
        }
      }
      go(None, None).getOrElse((0L, ""))
    }

    // Before doing tap, using the lastCursorBeforeTap should be empty
    val preTapTailEvents = sv1ScanBackend.getEventHistory(
      count = 50,
      after = Some(lastCursorBeforeTap),
      encoding = definitions.DamlValueEncoding.CompactJson,
    )
    preTapTailEvents shouldBe empty

    aliceWalletClient.tap(1)

    // Verify that new events are visible after the cursor
    eventually() {
      val newEvents = sv1ScanBackend.getEventHistory(
        count = 100,
        after = Some(lastCursorBeforeTap),
        encoding = definitions.DamlValueEncoding.CompactJson,
      )

      newEvents.nonEmpty shouldBe true

      val txItems = newEvents.collect {
        case item if item.update.exists {
              case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(_) => true
              case _ => false
            } =>
          item
      }

      val allTxHaveVerdicts = txItems.forall { item =>
        item.update match {
          case Some(definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx)) =>
            item.verdict.exists(v => v.updateId == tx.updateId && v.recordTime == tx.recordTime)
          case _ => false
        }
      }

      allTxHaveVerdicts shouldBe true
    }
  }

  "getEventById returns valid event; 404 for invalid event" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()
    // Get the current top wallet transaction event id (if any)
    val topBeforeO = aliceWalletClient
      .listTransactions(beginAfterId = None, pageSize = 1)
      .headOption
      .map(_.eventId)

    // Create a new event (tap) and capture its update id from wallet transaction history
    aliceWalletClient.tap(4)

    val tapTxId = eventuallySucceeds() {
      val latest = aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 10)
      // Prefer the first new entry different from the previous top if available
      val candidateEventId = topBeforeO match {
        case Some(prev) =>
          latest.find(_.eventId != prev).map(_.eventId).orElse(latest.headOption.map(_.eventId))
        case None => latest.headOption.map(_.eventId)
      }
      val eventId =
        candidateEventId.getOrElse(fail("Expected at least one wallet transaction after tap"))
      EventId.updateIdFromEventId(eventId)
    }

    // Both update, and verdict should be returned
    eventually() {
      val eventById = sv1ScanBackend.getEventById(
        tapTxId,
        Some(definitions.DamlValueEncoding.CompactJson),
      )
      eventById.update shouldBe defined
      eventById.verdict shouldBe defined
    }

    // Missing id: expect 404 -> client raises an error
    val missingId = "does-not-exist-12345"
    val failure = Try {
      sv1ScanBackend.getEventById(
        missingId,
        Some(definitions.DamlValueEncoding.CompactJson),
      )
    }
    failure.isSuccess shouldBe false
  }

  "resume verdict ingestion after scan restart without duplicates" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()

    // Helper to compute the last cursor before generating new events
    def cursorOf(item: definitions.EventHistoryItem): Option[(Long, String)] = {
      val updateCursor: Option[(Long, String)] = item.update.flatMap {
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) =>
          Some((tx.migrationId, tx.recordTime))
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryReassignment(r) =>
          r.event match {
            case definitions.UpdateHistoryReassignment.Event.members.UpdateHistoryAssignment(ev) =>
              Some((ev.migrationId, r.recordTime))
            case definitions.UpdateHistoryReassignment.Event.members
                  .UpdateHistoryUnassignment(ev) =>
              Some((ev.migrationId, r.recordTime))
          }
      }
      updateCursor.orElse(item.verdict.map(v => (v.migrationId, v.recordTime)))
    }

    val lastCursorBefore = eventuallySucceeds() {
      @annotation.tailrec
      def go(
          after: Option[(Long, String)],
          acc: Option[(Long, String)],
      ): Option[(Long, String)] = {
        val page = sv1ScanBackend.getEventHistory(
          count = 100,
          after = after,
          encoding = definitions.DamlValueEncoding.CompactJson,
        )
        if (page.isEmpty) acc
        else {
          val next = page.lastOption.flatMap(cursorOf)
          val newAcc = next.orElse(acc)
          next match {
            case Some(c) => go(Some(c), newAcc)
            case None => newAcc
          }
        }
      }
      go(None, None).getOrElse((0L, ""))
    }

    // Also record wallet top eventId to derive updateIds for taps deterministically
    val topBeforeAll = aliceWalletClient
      .listTransactions(beginAfterId = None, pageSize = 1)
      .headOption
      .map(_.eventId)

    // Two taps while scan is running
    aliceWalletClient.tap(1)
    aliceWalletClient.tap(2)

    // Ensure those initial taps have verdicts attached after the baseline cursor
    eventually() {
      val events = sv1ScanBackend.getEventHistory(
        count = 100,
        after = Some(lastCursorBefore),
        encoding = definitions.DamlValueEncoding.CompactJson,
      )
      val txItems = events.collect {
        case item if item.update.exists {
              case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(_) => true
              case _ => false
            } =>
          item
      }
      txItems.size should be >= 2
      // Each tx should have a matching verdict
      val allTxHaveVerdicts = txItems.forall { item =>
        item.update match {
          case Some(definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx)) =>
            item.verdict.exists(v => v.updateId == tx.updateId && v.recordTime == tx.recordTime)
          case _ => false
        }
      }
      allTxHaveVerdicts shouldBe true
    }

    // Stop scan to pause ingestion, wait until fully stopped
    sv1ScanBackend.stop()
    eventuallySucceeds() { sv1ScanBackend.is_running shouldBe false }

    // Restart scan and wait until ready
    sv1ScanBackend.start()
    sv1ScanBackend.waitForInitialization(
      timeout = com.digitalasset.canton.config.NonNegativeDuration.tryFromDuration(120.seconds)
    )

    // Two more taps after scan is back up (avoid issuing while scan is down)
    aliceWalletClient.tap(3)
    aliceWalletClient.tap(4)

    // Derive the updateIds for the four taps from wallet history
    val expectedUpdateIds = eventuallySucceeds() {
      val latest = aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 20)
      val newSinceTop = topBeforeAll match {
        case Some(prev) => latest.takeWhile(_.eventId != prev)
        case None => latest
      }
      newSinceTop.size should be >= 4
      newSinceTop.take(4).map(e => EventId.updateIdFromEventId(e.eventId))
    }

    // Verify: contains all expected updateIds, no duplicates, and verdicts attached
    eventually() {
      val events = sv1ScanBackend.getEventHistory(
        count = 200,
        after = Some(lastCursorBefore),
        encoding = definitions.DamlValueEncoding.CompactJson,
      )
      val txItems = events.collect {
        case item if item.update.exists {
              case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(_) => true
              case _ => false
            } =>
          item
      }
      val ids = txItems.flatMap(_.update).collect {
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx) => tx.updateId
        case definitions.UpdateHistoryItemV2.members.UpdateHistoryReassignment(r) => r.updateId
      }
      expectedUpdateIds.toSet.subsetOf(ids.toSet) shouldBe true
      ids.distinct.size shouldBe ids.size // no duplicates
      val allTxHaveVerdicts = txItems.forall { item =>
        item.update match {
          case Some(definitions.UpdateHistoryItemV2.members.UpdateHistoryTransactionV2(tx)) =>
            item.verdict.exists(v => v.updateId == tx.updateId && v.recordTime == tx.recordTime)
          case _ => false
        }
      }
      allTxHaveVerdicts shouldBe true
    }
  }
}
