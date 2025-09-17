package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics.MetricsPrefix
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
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.metrics.MetricValue

class ScanEventHistoryIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with WalletTxLogTestUtil
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

    val countBefore = scanVerdictCountMetric()
    val lastRtBefore = scanVerdictLastRecordTimeUsMetric()
    val errorsBefore = scanVerdictErrorsMetric()

    // Before doing tap, using the cursor should be empty
    val eventHistoryAfterLastCursor = sv1ScanBackend.getEventHistory(
      count = pageLimit,
      after = Some(cursorBeforeTap),
      encoding = CompactJson,
    )
    eventHistoryAfterLastCursor shouldBe empty

    aliceWalletClient.tap(1)

    // Verify that new events are visible after the cursor
    val eventHistory = eventually() {
      val eh = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeTap))
      eh should not be empty
      eh
    }

    // DB metrics should be updated
    eventually() {
      val countAfter = scanVerdictCountMetric()
      val lastRtAfter = scanVerdictLastRecordTimeUsMetric()
      val errorsAfter = scanVerdictErrorsMetric()
      countAfter should be > countBefore
      lastRtAfter should be >= lastRtBefore
      errorsAfter shouldBe errorsBefore
    }

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
        case item
            if item.update.exists { case UpdateHistoryTransactionV2(_) => true; case _ => false } =>
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
        case item
            if item.update.exists { case UpdateHistoryTransactionV2(_) => true; case _ => false } =>
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

  "should resume verdict ingestion when mediator recovers" in { implicit env =>
    initDsoWithSv1Only()

    // Disable mediator admin connectivity via proxy before starting scan
    toxiproxy.disableConnectionViaProxy(UseToxiproxy.mediatorAdminApi("sv1"))

    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val _ = onboardAliceAndBob()

    // There may be some data already in scan's DB even with disabled proxy
    // Get the last available event in DB
    val cursorBefore = eventuallySucceeds() { lastCursor() }

    // Also record wallet top event to derive updateIds for taps
    val topBeforeO = withoutDevNetTopups(
      aliceWalletClient
        .listTransactions(beginAfterId = None, pageSize = 1)
    ).headOption.map(_.eventId)

    // Generate new updates while mediator ingestion is unavailable
    aliceWalletClient.tap(5)
    aliceWalletClient.tap(6)

    // While mediator ingestion is down, history after cursor should be empty due to capping
    val historyWhileDown = sv1ScanBackend.getEventHistory(
      count = pageLimit,
      after = Some(cursorBefore),
      encoding = CompactJson,
    )
    historyWhileDown shouldBe empty

    // Fetch the updateIds for the taps from wallet history
    val expectedUpdateIds = eventuallySucceeds() {
      val latest =
        withoutDevNetTopups(aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 10))
      val newSinceTop = topBeforeO match {
        case Some(prev) => latest.takeWhile(_.eventId != prev)
        case None => latest
      }
      newSinceTop.size should be >= 2
      newSinceTop.take(2).map(e => EventId.updateIdFromEventId(e.eventId)).toVector
    }

    // getEventById should cause 404 while the verdict store has not synced
    expectedUpdateIds.foreach { id =>
      val res = sv1ScanBackend.getEventById(
        id,
        Some(CompactJson),
      )
      res shouldBe None
    }

    // Re-enable mediator connectivity and expect ingestion to resume
    toxiproxy.enableConnectionViaProxy(UseToxiproxy.mediatorAdminApi("sv1"))

    eventually() {
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBefore))
      eventHistory should not be empty
    }
    expectedUpdateIds.foreach { id =>
      val eventByIdO = sv1ScanBackend.getEventById(
        id,
        Some(CompactJson),
      )
      eventByIdO match {
        case Some(eventById) =>
          eventById.update shouldBe defined
          eventById.verdict shouldBe defined
        case None => fail("Expected event for update id but got None")
      }
    }
  }

  "should return event for valid updateId and 404 for missing updateId" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val _ = onboardAliceAndBob()
    // Get the current top wallet transaction event id (if any)
    val topBeforeO = withoutDevNetTopups(
      aliceWalletClient
        .listTransactions(beginAfterId = None, pageSize = 1)
    ).headOption
      .map(_.eventId)

    // Create a new event (tap) and capture its update id from wallet transaction history
    aliceWalletClient.tap(4)

    val updateIdFromTap = eventuallySucceeds() {
      val latest =
        withoutDevNetTopups(aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 10))
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
    val eventById = eventuallySucceeds() {
      sv1ScanBackend
        .getEventById(
          updateIdFromTap,
          Some(CompactJson),
        )
        .getOrElse(fail("Expected event for valid update id"))
    }

    eventById.update shouldBe defined
    eventById.verdict shouldBe defined

    // Missing id: expect 404 -> client returns None
    val missingId = "does-not-exist-12345"
    val res = sv1ScanBackend.getEventById(
      missingId,
      Some(CompactJson),
    )
    res shouldBe None
  }

  "should resume verdict ingestion after scan restart without duplicates" in { implicit env =>
    initDsoWithSv1Only()
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val _ = onboardAliceAndBob()

    val cursorBeforeTaps = eventuallySucceeds() { lastCursor() }

    // Also record wallet top eventId to derive updateIds for taps deterministically
    val topBeforeAll = withoutDevNetTopups(
      aliceWalletClient
        .listTransactions(beginAfterId = None, pageSize = 1)
    ).headOption.map(_.eventId)

    // Two taps while scan is running
    aliceWalletClient.tap(1)
    aliceWalletClient.tap(2)

    // Obtain updateIds for the two initial taps from wallet history
    val expectedFirstUpdateIds = eventuallySucceeds() {
      val latest =
        withoutDevNetTopups(aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 10))
      val newSinceTop = topBeforeAll match {
        case Some(prev) => latest.takeWhile(_.eventId != prev)
        case None => latest
      }
      newSinceTop.size should be >= 2
      newSinceTop.take(2).map(e => EventId.updateIdFromEventId(e.eventId))
    }

    // Ensure those initial taps are present with verdicts after the baseline cursor
    eventually() {
      val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeTaps))
      val ids = eventHistory
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
      expectedFirstUpdateIds.toSet.subsetOf(ids.toSet) shouldBe true
    }

    val cursorBeforeRestart = eventuallySucceeds() { lastCursor() }

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
      val latest =
        withoutDevNetTopups(aliceWalletClient.listTransactions(beginAfterId = None, pageSize = 20))
      val newSinceTop = topBeforeAll match {
        case Some(prev) => latest.takeWhile(_.eventId != prev)
        case None => latest
      }
      newSinceTop.size should be >= 4
      newSinceTop.take(4).map(e => EventId.updateIdFromEventId(e.eventId))
    }

    // Wait for scan backend to begin doing ingestion
    eventually() {
      val eh = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeRestart))
      eh should not be empty
    }

    // Wait some more to ensure we have synced both stores
    Threading.sleep(1000)

    // Verify events contain all updateIds, no duplicates, and verdicts are present for each update
    val eventHistory = getEventHistoryAndCheckTxVerdicts(after = Some(cursorBeforeTaps))
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
    val expectedSet = expectedUpdateIds.toSet
    val presentSet = ids.toSet
    val missing = expectedSet.diff(presentSet)

    withClue(s"Missing expected updateIds: ${missing
        .mkString(",")} | expected=${expectedSet.size}, present=${presentSet.size}") {

      missing shouldBe empty
    }

    withClue("ids should not have duplicates") {
      ids.distinct.size shouldBe ids.size
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

    silentClue("Update events with missing verdict: " + missing.mkString(",")) {
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

  private def getLongMetricOr0(name: String)(implicit env: SpliceTestConsoleEnvironment): Long =
    sv1ScanBackend.metrics.list(name).get(name) match {
      case None => 0L
      case Some(_) =>
        sv1ScanBackend.metrics
          .get(name)
          .select[MetricValue.LongPoint]
          .value
          .value
    }

  private def scanVerdictCountMetric()(implicit env: SpliceTestConsoleEnvironment): Long =
    getLongMetricOr0(s"$MetricsPrefix.scan.verdict_ingestion.count")

  private def scanVerdictLastRecordTimeUsMetric()(implicit
      env: SpliceTestConsoleEnvironment
  ): Long =
    getLongMetricOr0(s"$MetricsPrefix.scan.verdict_ingestion.last_record_time_us")

  private def scanVerdictErrorsMetric()(implicit env: SpliceTestConsoleEnvironment): Long =
    getLongMetricOr0(s"$MetricsPrefix.scan.verdict_ingestion.errors")

}
