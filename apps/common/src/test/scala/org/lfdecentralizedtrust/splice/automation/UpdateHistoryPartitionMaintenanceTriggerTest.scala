package org.lfdecentralizedtrust.splice.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.SimClock
import org.postgresql.util.PSQLException
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.{PageLimit, UpdateHistory, UpdateHistoryTestBase}

import java.time.Duration

class UpdateHistoryPartitionMaintenanceTriggerTest extends UpdateHistoryTestBase {

  private def daysAfter(days: Long) = CantonTimestamp.Epoch.plus(Duration.ofDays(days))

  private def mkTrigger(store: UpdateHistory, now: CantonTimestamp): UpdateHistoryPartitionMaintenanceTrigger = {
    val clock = new SimClock(loggerFactory = loggerFactory)
    clock.advanceTo(now)
    new UpdateHistoryPartitionMaintenanceTrigger(
      store,
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
  }

  "PartitionMaintenanceTrigger" should {

    "create a task for each missing day" in {
      val store = mkStore()
      val trigger = mkTrigger(store, daysAfter(20))
      for {
        workDoneBeforeReady <- trigger.performWorkIfAvailable()
        _ <- initStore(store, timeStart = time(0), partitionRunwayDays = 1)
        _ <- create(domain1, cid1, offset1, party1, store, time(10))
        workDone <- trigger.performWorkIfAvailable()
        workDoneAgain <- trigger.performWorkIfAvailable()
        stillMissing <- store.missingPartitionDays(daysAfter(20))
      } yield {
        workDoneBeforeReady shouldBe false
        workDone shouldBe true
        workDoneAgain shouldBe false
        stillMissing shouldBe empty
      }
    }

    "fail ingestion until trigger creates the partition" in {
      val store = mkStore()
      val trigger = mkTrigger(store, daysAfter(1))
      for {
        _ <- initStore(store, timeStart = time(0), partitionRunwayDays = 1)
        _ <- create(domain1, cid1, offset1, party1, store, time(10))
        failure <- recoverToExceptionIf[PSQLException](
          create(domain1, cid2, offset2, party1, store, daysAfter(8))
        )
        updatesAfterFailure <- store.getAllUpdates(None, PageLimit.tryCreate(1000))
        workDone <- trigger.performWorkIfAvailable()
        _ <- create(domain1, cid2, offset2, party1, store, daysAfter(8))
        updatesAfterRetry <- store.getAllUpdates(None, PageLimit.tryCreate(1000))
      } yield {
        failure.getMessage should include("no partition of relation")
        updatesAfterFailure should have size 1
        workDone shouldBe true
        updatesAfterRetry should have size 2
      }
    }
  }
}
