package com.daml.network.scan.automation

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.network.automation.{TriggerContext, TriggerEnabledSynchronization}
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import com.daml.network.environment.ledger.api.{TransactionTreeUpdate, TreeUpdate}
import com.daml.network.scan.store.AcsSnapshotStore
import com.daml.network.scan.store.AcsSnapshotStore.AcsSnapshot
import com.daml.network.store.{PageLimit, TreeUpdateWithMigrationId, UpdateHistory}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import scala.concurrent.Future

class AcsSnapshotTriggerTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  "AcsSnapshotTrigger" should {

    "when there's a previous snapshot" should {

      "do nothing if the next task is not yet due" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        previousSnapshot(now.minusSeconds(60L))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "do nothing if there might still be updates pending" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        // The snapshot was taken more than an hour ago, so the next snapshot is due
        val lastSnapshotTime = now.minusSeconds(3700L)
        previousSnapshot(lastSnapshotTime)

        // but there's still updates pending
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, lastSnapshotTime.plusSeconds(3600L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "return the new task when due and no updates pending" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        // The snapshot was taken more than an hour ago, so the next snapshot is due
        val lastSnapshotTime = now.minusSeconds(3700L)
        val lastSnapshot = previousSnapshot(lastSnapshotTime)

        // there are no pending updates
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, lastSnapshotTime.plusSeconds(3600L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(
                  treeUpdate(lastSnapshotTime.plusSeconds(3700L)),
                  dummyDomain,
                ),
                1L,
              )
            )
          )
        )

        trigger.retrieveTasks().futureValue should be(
          Seq(AcsSnapshotTrigger.Task(lastSnapshotTime.plusSeconds(3600L), Some(lastSnapshot)))
        )
      }

    }

    "when there's no previous snapshot" should {
      "do nothing if only an ACS import is present" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        noPreviousSnapshot()

        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, CantonTimestamp.MinValue.plusSeconds(1L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "do nothing if there might still be updates pending" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, CantonTimestamp.MinValue.plusSeconds(1L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )
        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:00:00.00Z"))

        // but there's still updates pending
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(Future.successful(Seq.empty))

        trigger.retrieveTasks().futureValue should be(Seq.empty)
      }

      "return the first task when due and no updates pending" in new AcsSnapshotTriggerTestScope {
        def snapshotPeriodHours = 1

        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, CantonTimestamp.MinValue.plusSeconds(1L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(treeUpdate(now.minusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:00:00.00Z"))

        // no updates pending
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(treeUpdate(now.plusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        trigger.retrieveTasks().futureValue should be(
          Seq(AcsSnapshotTrigger.Task(firstSnapshotTime, None))
        )
      }

      "return the first task when due and no updates pending between 23:00 and 00:00" in new AcsSnapshotTriggerTestScope {
        override def now =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T23:15:30.00Z"))
        def snapshotPeriodHours = 1

        noPreviousSnapshot()

        // data after ACS
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, CantonTimestamp.MinValue.plusSeconds(1L)))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(treeUpdate(now.minusSeconds(1L)), dummyDomain),
                1L,
              )
            )
          )
        )

        val firstSnapshotTime =
          CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-04T00:00:00.00Z"))

        // no updates pending
        when(
          updateHistory.getUpdates(
            eqTo(Some((migrationId, firstSnapshotTime))),
            eqTo(PageLimit.tryCreate(1)),
          )(any[TraceContext])
        ).thenReturn(
          Future.successful(
            Seq(
              TreeUpdateWithMigrationId(
                GetTreeUpdatesResponse(treeUpdate(now.plusSeconds(1800L)), dummyDomain),
                1L,
              )
            )
          )
        )

        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
          trigger.retrieveTasks().futureValue should be(Seq.empty),
          lines => {
            forExactly(1, lines) { line =>
              line.message should be(
                s"Still not time to take a snapshot. Now: $now. Next snapshot time: $firstSnapshotTime."
              )
            }
          },
        )
      }
    }

  }

  trait AcsSnapshotTriggerTestScope {
    def snapshotPeriodHours: Int

    val clock = new SimClock(loggerFactory = loggerFactory)

    def now = CantonTimestamp.assertFromInstant(java.time.Instant.parse("2007-12-03T10:15:30.00Z"))
    clock.advanceTo(now)

    val dummyDomain = DomainId.tryFromString("dummy::domain")
    def treeUpdate(recordTime: CantonTimestamp): TreeUpdate = {
      TransactionTreeUpdate(
        new TransactionTree(
          "updateId",
          "commandId",
          "workflowId",
          recordTime.toInstant,
          "offset",
          java.util.Map.of(),
          java.util.List.of(),
          dummyDomain.toProtoPrimitive,
          TraceContextOuterClass.TraceContext.getDefaultInstance,
          recordTime.toInstant,
        )
      )
    }

    val triggerContext: TriggerContext = TriggerContext(
      AutomationConfig(),
      clock,
      clock,
      TriggerEnabledSynchronization.Noop,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
      loggerFactory,
      NoOpMetricsFactory,
    )
    val store: AcsSnapshotStore = mock[AcsSnapshotStore]
    val migrationId: Long = 0L
    val historyId: Long = 1L
    when(store.migrationId).thenReturn(migrationId)
    val updateHistory: UpdateHistory = mock[UpdateHistory]
    when(updateHistory.isReady).thenReturn(true)
    val trigger = new AcsSnapshotTrigger(store, updateHistory, snapshotPeriodHours, triggerContext)

    def noPreviousSnapshot(): Unit = {
      when(
        store.lookupSnapshotBefore(eqTo(migrationId), eqTo(CantonTimestamp.MaxValue))(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(None)
        )
    }

    def previousSnapshot(time: CantonTimestamp): AcsSnapshot = {
      val lastSnapshot = AcsSnapshot(time, migrationId, historyId, 0, 100)
      when(
        store.lookupSnapshotBefore(eqTo(migrationId), eqTo(CantonTimestamp.MaxValue))(
          any[TraceContext]
        )
      )
        .thenReturn(
          Future.successful(Some(lastSnapshot))
        )
      lastSnapshot
    }
  }

}
