package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.stream.scaladsl.Source
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  TransactionTreeUpdate,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.environment.{BaseLedgerConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionFilter
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory, UpdateHistoryTestBase}

import java.time.{Duration, LocalDate, ZoneOffset}
import scala.concurrent.Future

class PartitionMaintenanceTriggerTest extends UpdateHistoryTestBase {

  private val day0 = LocalDate.ofInstant(CantonTimestamp.Epoch.toInstant, ZoneOffset.UTC)
  private def daysAfter(days: Long) = CantonTimestamp.Epoch.plus(Duration.ofDays(days))

  private def mkConnection(
      updates: Seq[LedgerClient.GetTreeUpdatesResponse] = Seq.empty
  ): BaseLedgerConnection = {
    val connection = mock[BaseLedgerConnection]
    when(
      connection.updates(eqTo(0L), any[IngestionFilter])(
        any[TraceContext]
      )
    ).thenReturn(Source(updates))
    connection
  }

  private def createMissingPartitions(
      store: UpdateHistory,
      now: CantonTimestamp,
      connection: BaseLedgerConnection = mkConnection(),
  ): Future[Seq[LocalDate]] =
    PartitionMaintenanceTrigger.createMissingPartitions(
      store,
      connection,
      runwayDays = 7,
      now,
      logger,
    )

  "PartitionMaintenanceTrigger.createMissingPartitions" should {

    "create partitions from the first update to ingest if nothing was ingested" in {
      val store = mkStore()
      val firstUpdate = LedgerClient.GetTreeUpdatesResponse(
        TreeUpdateOrOffsetCheckpoint.Update(
          TransactionTreeUpdate(
            mkCreateTx(
              offset = 11L,
              createRequests = Seq(appRewardCoupon(0, party1, contractId = cid1)),
              effectiveAt = daysAfter(3).toInstant,
              createdEventSignatories = Seq(party1),
              synchronizerId = domain1,
              workflowId = "",
              recordTime = daysAfter(3).toInstant,
            )
          ),
          domain1,
        )
      )
      for {
        _ <- store.ingestionSink.initialize()
        created <- createMissingPartitions(
          store,
          daysAfter(20),
          mkConnection(Seq(firstUpdate)),
        )
      } yield {
        created shouldBe (3L to 27L).map(day0.plusDays)
      }
    }

    "extend the runway from the last ingested record time after a downtime" in {
      val store = mkStore()
      for {
        _ <- initStore(store, timeStart = time(0), partitionRunwayDays = 1)
        _ <- create(domain1, cid1, offset1, party1, store, time(10))
        created <- createMissingPartitions(store, daysAfter(20))
        _ <- create(domain1, cid2, offset2, party1, store, daysAfter(15))
        updates <- store.getAllUpdates(None, PageLimit.tryCreate(1000))
      } yield {
        created shouldBe (1L to 27L).map(day0.plusDays)
        updates should have size 2
      }
    }

  }

  "PartitionMaintenanceTrigger" should {

    "create a task for each missing day" in {
      val store = mkStore()
      val clock = new SimClock(loggerFactory = loggerFactory)
      clock.advanceTo(daysAfter(20))
      val trigger = new PartitionMaintenanceTrigger(
        store,
        7,
        TriggerContext(
          AutomationConfig(),
          clock,
          clock,
          TriggerEnabledSynchronization.Noop,
          RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
          loggerFactory,
          NoOpMetricsFactory,
        ),
      )
      for {
        workDoneBeforeReady <- trigger.performWorkIfAvailable()
        _ <- initStore(store, timeStart = time(0), partitionRunwayDays = 1)
        _ <- create(domain1, cid1, offset1, party1, store, time(10))
        workDone <- trigger.performWorkIfAvailable()
        workDoneAgain <- trigger.performWorkIfAvailable()
        stillMissing <- createMissingPartitions(store, daysAfter(20))
      } yield {
        workDoneBeforeReady shouldBe false
        workDone shouldBe true
        workDoneAgain shouldBe false
        stillMissing shouldBe empty
      }
    }
  }
}
