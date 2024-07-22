package com.daml.network.store

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.network.environment.RetryProvider
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.PositiveFiniteDuration
import com.digitalasset.canton.time.SimClock
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Duration
import scala.concurrent.Future

class DomainTimeStoreTest extends AsyncWordSpec with BaseTest {

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

  "DomainTimeStoreTest" should {

    "ingest domain time updates and blocks waitForDomainTimeSync for large delays" in {
      val clock = new SimClock(loggerFactory = loggerFactory)
      val store = new DomainTimeStore(
        clock,
        PositiveFiniteDuration.ofMinutes(2),
        retryProvider,
        loggerFactory,
      )
      val start = clock.now
      for {
        _ <- Future.unit
        // no time ingested, returns instantly
        _ <- store.waitForDomainTimeSync()
        _ = store.ingestDomainTime(start)
        // current time ingested, returns instantly
        _ <- store.waitForDomainTimeSync()
        _ = clock.advance(Duration.ofSeconds(30))
        // delay=30s, returns instantly
        _ <- store.waitForDomainTimeSync()
        _ = clock.advance(Duration.ofMinutes(4))
        // delay=4min30s, blocks
        firstBlock = store.waitForDomainTimeSync()
        _ = firstBlock.isCompleted shouldBe false
        _ = store.ingestDomainTime(start.add(Duration.ofSeconds(30)))
        // delay=4min, still blocks
        secondBlock = store.waitForDomainTimeSync()
        _ = firstBlock.isCompleted shouldBe false
        _ = secondBlock.isCompleted shouldBe false
        _ = store.ingestDomainTime(start.add(Duration.ofSeconds(180)))
        // delay=2min, unblocked
        _ <- firstBlock
        _ <- secondBlock
        // returns instantly
        _ <- store.waitForDomainTimeSync()
      } yield {
        succeed
      }
    }
  }
}
