package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.plugins.toxiproxy.UseToxiproxy
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{IntegrationTest}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment

import org.lfdecentralizedtrust.splice.util.*

import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.{CompactJson, ProtobufJson}
import definitions.EventHistoryItem
import definitions.UpdateHistoryItemV2.members.{
  UpdateHistoryReassignment,
  UpdateHistoryTransactionV2,
}
import definitions.UpdateHistoryReassignment.Event.members.{
  UpdateHistoryAssignment,
  UpdateHistoryUnassignment,
}

import scala.concurrent.duration.*
import scala.util.Try
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.Port

class ScanEventHistoryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((_, scanConfig) =>
          scanConfig.copy(
            mediatorVerdictIngestion = scanConfig.mediatorVerdictIngestion.copy(
              restartDelay = NonNegativeFiniteDuration.ofMillis(500)
            ),
            // Route mediator admin client via toxiproxy
            mediatorAdminClient = scanConfig.mediatorAdminClient.copy(
              port = Port.tryCreate(scanConfig.mediatorAdminClient.port.unwrap + 20000)
            ),
          )
        )(config)
      )

  private val toxiproxy = UseToxiproxy(createMediatorProxies = true)
  registerPlugin(toxiproxy)

  private val pageLimit = 1000

  "should provide new events with verdicts" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()

    val cursorBeforeTap = eventuallySucceeds() { lastCursor() }

    // Before doing tap, using the cursor should be empty
    val eventHistoryAfterLastCursor = sv1ScanBackend.getEventHistory(
      count = pageLimit,
      after = Some(cursorBeforeTap),
      encoding = CompactJson,
    )
    eventHistoryAfterLastCursor shouldBe empty

    aliceWalletClient.tap(1)

    // Verify that new events are visible after the cursor
    eventually() {
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeTap))
      eventHistory.nonEmpty shouldBe true

      // Basic checks for page limit and encoding
      val smallerLimit = eventHistory.size - 1
      val withCompactEncoding = sv1ScanBackend.getEventHistory(
        count = smallerLimit,
        after = Some(cursorBeforeTap),
        encoding = CompactJson,
      )

      withCompactEncoding.size shouldBe smallerLimit

      val withProtobufEncoding = sv1ScanBackend.getEventHistory(
        count = smallerLimit,
        after = Some(cursorBeforeTap),
        encoding = ProtobufJson,
      )

      withProtobufEncoding.size shouldBe smallerLimit

      val txIdsCompact = withCompactEncoding
        .collect {
          case item if item.update.exists {
                case UpdateHistoryTransactionV2(_) => true; case _ => false
              } =>
            item
        }
        .flatMap(_.update)
        .collect {
          case UpdateHistoryTransactionV2(tx) => tx.updateId
          case UpdateHistoryReassignment(r) => r.updateId
        }
        .toSet

      val txIdsProtobuf = withProtobufEncoding
        .collect {
          case item if item.update.exists {
                case UpdateHistoryTransactionV2(_) => true; case _ => false
              } =>
            item
        }
        .flatMap(_.update)
        .collect {
          case UpdateHistoryTransactionV2(tx) => tx.updateId
          case UpdateHistoryReassignment(r) => r.updateId
        }
        .toSet

      withClue("Mismatch between CompactJson and ProtobufJson update ids") {
        txIdsProtobuf shouldBe txIdsCompact
      }

    }
  }

  "should resume verdict ingestion when mediator recovers" in { implicit env =>
    initDsoWithSv1Only()

    // Disable mediator admin connectivity via proxy before starting scan
    toxiproxy.disableConnectionViaProxy(UseToxiproxy.mediatorAdminApi("sv1"))

    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()

    val cursorBefore = eventuallySucceeds() { lastCursor() }

    // Also record wallet top event to derive updateIds for taps
    val topBeforeO = aliceWalletClient
      .listTransactions(beginAfterId = None, pageSize = 1)
      .headOption
      .map(_.eventId)

    // Generate new updates while mediator ingestion is unavailable
    aliceWalletClient.tap(5)
    aliceWalletClient.tap(6)

    // While mediator ingestion is down, history after cursor should be empty due to capping
    eventually() {
      val eventHistory = sv1ScanBackend.getEventHistory(
        count = pageLimit,
        after = Some(cursorBefore),
        encoding = CompactJson,
      )
      eventHistory shouldBe empty
    }

    // Fetch the updateIds for the taps from wallet history
    val expectedUpdateIds = eventuallySucceeds() {
      val latest = aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 10)
      val newSinceTop = topBeforeO match {
        case Some(prev) => latest.takeWhile(_.eventId != prev)
        case None => latest
      }
      newSinceTop.size should be >= 2
      newSinceTop.take(2).map(e => EventId.updateIdFromEventId(e.eventId)).toVector
    }

    // getEventById should return the update, but no verdicts while mediator is unavailable
    expectedUpdateIds.foreach { id =>
      val ev = sv1ScanBackend.getEventById(
        id,
        Some(CompactJson),
      )
      ev.update shouldBe defined
      ev.verdict shouldBe empty
    }

    // Re-enable mediator connectivity and expect ingestion to resume
    toxiproxy.enableConnectionViaProxy(UseToxiproxy.mediatorAdminApi("sv1"))

    eventually() {
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBefore))
      eventHistory.nonEmpty shouldBe true
    }
  }

  "should return event for valid updateId and 404 for missing updateId" in { implicit env =>
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

    val updateIdFromTap = eventuallySucceeds() {
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
        updateIdFromTap,
        Some(CompactJson),
      )
      eventById.update shouldBe defined
      eventById.verdict shouldBe defined
    }

    // Missing id: expect 404 -> client raises an error
    val missingId = "does-not-exist-12345"
    val failure = Try {
      sv1ScanBackend.getEventById(
        missingId,
        Some(CompactJson),
      )
    }
    failure.isSuccess shouldBe false
  }

  "should resume verdict ingestion after scan restart without duplicates" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val (aliceParty, _) = onboardAliceAndBob()

    val cursorBeforeRestart = eventuallySucceeds() { lastCursor() }

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
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeRestart))
      val txItems = eventHistory.collect {
        case item if item.update.exists {
              case UpdateHistoryTransactionV2(_) => true; case _ => false
            } =>
          item
      }
      txItems.size should be >= 2
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
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeRestart))
      val txItems = eventHistory.collect {
        case item if item.update.exists {
              case UpdateHistoryTransactionV2(_) => true; case _ => false
            } =>
          item
      }
      val ids = txItems.flatMap(_.update).collect {
        case UpdateHistoryTransactionV2(tx) => tx.updateId
        case UpdateHistoryReassignment(r) => r.updateId
      }
      expectedUpdateIds.toSet.subsetOf(ids.toSet) shouldBe true
      ids.distinct.size shouldBe ids.size // no duplicates
    }
  }

  // Fetch events and assert every tx update has a matching verdict
  private def getEventHistoryAndCheckTxVerdicts(after: Option[(Long, String)])(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val eventHistory = sv1ScanBackend.getEventHistory(
      count = pageLimit,
      after = after,
      encoding = CompactJson,
    )

    val txItems = eventHistory.collect {
      case item if item.update.exists {
            case UpdateHistoryTransactionV2(_) => true; case _ => false
          } =>
        item
    }

    val missing = txItems.flatMap { item =>
      item.update match {
        case Some(UpdateHistoryTransactionV2(tx))
            if !item.verdict
              .exists(v => v.updateId == tx.updateId && v.recordTime == tx.recordTime) =>
          Some(tx.updateId)
        case _ => None
      }
    }

    withClue("Update events with missing verdict: " + missing.mkString(",")) {
      missing shouldBe empty
    }

    eventHistory
  }

  // Obtain last (migrationId, recordTime) provided by the event stream
  private def lastCursor(startAfter: Option[(Long, String)] = None)(implicit
      env: SpliceTestConsoleEnvironment
  ): (Long, String) = {
    @annotation.tailrec
    def go(after: Option[(Long, String)], acc: Option[(Long, String)]): Option[(Long, String)] = {
      val page = sv1ScanBackend.getEventHistory(
        count = pageLimit,
        after = after,
        encoding = CompactJson,
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
    go(startAfter, None).getOrElse((0L, ""))
  }

  // (migrationId, recordTime) for an event item
  private def cursorOf(item: EventHistoryItem): Option[(Long, String)] = {
    val updateCursor: Option[(Long, String)] = item.update.flatMap {
      case UpdateHistoryTransactionV2(tx) =>
        Some((tx.migrationId, tx.recordTime))
      case UpdateHistoryReassignment(r) =>
        r.event match {
          case UpdateHistoryAssignment(ev) =>
            Some((ev.migrationId, r.recordTime))
          case UpdateHistoryUnassignment(ev) =>
            Some((ev.migrationId, r.recordTime))
        }
    }
    updateCursor.orElse(item.verdict.map(v => (v.migrationId, v.recordTime)))
  }
}
