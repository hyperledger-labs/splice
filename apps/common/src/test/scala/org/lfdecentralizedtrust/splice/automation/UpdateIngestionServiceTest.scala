// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.OffsetCheckpoint
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.{
  NonNegativeFiniteDuration,
  PositiveFiniteDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.time.WallClock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.mockito.ArgumentMatchersSugar
import org.scalatest.wordspec.AnyWordSpec
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, IngestionConfig}
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  PackageVersionSupport,
  RetryProvider,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.ledger.api.TreeUpdateOrOffsetCheckpoint
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.IngestionSink.IngestionStart

import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class UpdateIngestionServiceTest
    extends AnyWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext
    with org.mockito.MockitoSugar
    with ArgumentMatchersSugar {

  private implicit val mat: Materializer = Materializer(actorSystem)

  private class RecordingSink extends MultiDomainAcsStore.IngestionSink {
    val ingestedOffsets = new ConcurrentLinkedQueue[Long]()
    override def ingestionFilter: MultiDomainAcsStore.IngestionFilter =
      MultiDomainAcsStore.IngestionFilter(
        PartyId.tryFromProtoPrimitive("p::dummy"),
        Seq.empty,
        Seq.empty,
      )
    override def initialize()(implicit traceContext: TraceContext) =
      Future.successful(IngestionStart.ResumeAtOffset(10L))
    override def ingestAcsStreamInBatches(
        source: Source[Seq[BaseLedgerConnection.ActiveContractsItem], NotUsed],
        offset: Long,
    )(implicit tc: TraceContext, mat: Materializer): Future[Unit] = Future.unit
    override def ingestUpdateBatch(batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint])(implicit
        traceContext: TraceContext
    ): Future[Unit] = {
      batch.toList.foreach(u => ingestedOffsets.add(u.offset))
      Future.unit
    }
  }

  "UpdateIngestionService" should {
    "restart a silently stalled update stream and resume ingesting" in {
      val sink = new RecordingSink
      val connection = mock[SpliceLedgerConnection]
      when(connection.ledgerEnd()(any[TraceContext])).thenReturn(Future.successful(20L))
      // First subscription never delivers anything and never fails: the stall that the retry loop cannot see.
      // The second subscription (after the watchdog restarted the stream) delivers a checkpoint.
      val stalled = Source.never[GetTreeUpdatesResponse]
      val healthy = Source
        .single(
          GetTreeUpdatesResponse(
            TreeUpdateOrOffsetCheckpoint.Checkpoint(
              new OffsetCheckpoint(20L, Collections.emptyList())
            )
          )
        )
        .concat(Source.never[GetTreeUpdatesResponse])
      when(
        connection.updates(any[Long], any[MultiDomainAcsStore.IngestionFilter])(any[TraceContext])
      )
        .thenReturn(stalled, healthy)

      val config = AutomationConfig(
        pollingInterval = NonNegativeFiniteDuration.ofMillis(200),
        ingestion = IngestionConfig(
          stallTimeout = PositiveFiniteDuration.ofSeconds(1),
          stallCheckInterval = NonNegativeFiniteDuration.ofMillis(200),
        ),
      )
      val retryProvider =
        RetryProvider(loggerFactory, ProcessingTimeout(), FutureSupervisor.Noop, NoOpMetricsFactory)
      val service = loggerFactory.suppressWarningsAndErrors {
        val s = new UpdateIngestionService(
          "test",
          sink,
          connection,
          config,
          new WallClock(timeouts, loggerFactory),
          backoffClock = new WallClock(timeouts, loggerFactory),
          retryProvider,
          mock[PackageVersionSupport],
          loggerFactory,
        )
        eventually(30.seconds) {
          sink.ingestedOffsets.asScala.toList should contain(20L)
        }
        s
      }
      val healthyAfterRestart = service.isHealthy
      // Shut down like the app does: closing the retry provider stops the running stream.
      loggerFactory.suppressWarningsAndErrors {
        retryProvider.close()
        service.close()
      }
      healthyAfterRestart shouldBe true
    }
  }

  private val timeout = 10.minutes

  "hasStalled" should {
    "report a stall when there was no progress for the timeout and the ledger moved on" in {
      UpdateIngestionService.hasStalled(
        timeout,
        timeout,
        ledgerEnd = 101L,
        lastIngestedOffset = 100L,
      ) shouldBe true
      UpdateIngestionService.hasStalled(
        1.hour,
        timeout,
        ledgerEnd = 101L,
        lastIngestedOffset = 100L,
      ) shouldBe true
    }

    "not report a stall before the timeout has elapsed" in {
      UpdateIngestionService.hasStalled(
        9.minutes,
        timeout,
        ledgerEnd = 500L,
        lastIngestedOffset = 100L,
      ) shouldBe false
    }

    "not report a stall on an idle ledger" in {
      UpdateIngestionService.hasStalled(
        1.hour,
        timeout,
        ledgerEnd = 100L,
        lastIngestedOffset = 100L,
      ) shouldBe false
    }

    "not report a stall if the store is ahead of the observed ledger end" in {
      UpdateIngestionService.hasStalled(
        1.hour,
        timeout,
        ledgerEnd = 99L,
        lastIngestedOffset = 100L,
      ) shouldBe false
    }
  }
}
