// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import cats.data.NonEmptyList
import com.daml.ledger.javaapi.data.OffsetCheckpoint
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
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
import org.mockito.{ArgumentMatchersSugar, Mockito}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
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
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Future, Promise}
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

  private def checkpointAt(offset: Long) =
    GetTreeUpdatesResponse(
      TreeUpdateOrOffsetCheckpoint.Checkpoint(new OffsetCheckpoint(offset, Collections.emptyList()))
    )

  private def startService(sink: RecordingSink, connection: SpliceLedgerConnection) = {
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
      new UpdateIngestionService(
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
    }
    (service, retryProvider)
  }

  // Shut down like the app does: closing the retry provider stops the running stream.
  private def stopService(service: UpdateIngestionService, retryProvider: RetryProvider): Unit =
    loggerFactory.suppressWarningsAndErrors {
      retryProvider.close()
      service.close()
    }

  "UpdateIngestionService" should {
    "restart a silently stalled update stream and resume ingesting" in {
      val sink = new RecordingSink
      val connection = mock[SpliceLedgerConnection]
      when(connection.ledgerEnd()(any[TraceContext])).thenReturn(Future.successful(20L))
      // First subscription never delivers anything and never fails: the stall that the retry loop cannot see.
      // The second subscription (after the watchdog restarted the stream) delivers a checkpoint.
      val stalled = Source.never[GetTreeUpdatesResponse]
      val healthy = Source.single(checkpointAt(20L)).concat(Source.never[GetTreeUpdatesResponse])
      when(
        connection.updates(any[Long], any[MultiDomainAcsStore.IngestionFilter])(any[TraceContext])
      )
        .thenReturn(stalled, healthy)

      val (service, retryProvider) = startService(sink, connection)
      loggerFactory.suppressWarningsAndErrors {
        eventually(30.seconds) {
          sink.ingestedOffsets.asScala.toList should contain(20L)
        }
      }
      val healthyAfterRestart = service.isHealthy
      stopService(service, retryProvider)
      healthyAfterRestart shouldBe true
    }

    "keep checking after an unexpected failure of a stall check" in {
      val sink = new RecordingSink
      val connection = mock[SpliceLedgerConnection]
      // The first check blows up synchronously. The watchdog must survive that and restart the stalled stream.
      val calls = new AtomicInteger(0)
      Mockito
        .when(connection.ledgerEnd()(any[TraceContext]))
        .thenAnswer(new Answer[Future[Long]] {
          override def answer(invocation: InvocationOnMock): Future[Long] =
            if (calls.incrementAndGet() == 1) sys.error("boom") else Future.successful(20L)
        })
      val healthy = Source.single(checkpointAt(20L)).concat(Source.never[GetTreeUpdatesResponse])
      when(
        connection.updates(any[Long], any[MultiDomainAcsStore.IngestionFilter])(any[TraceContext])
      )
        .thenReturn(Source.never[GetTreeUpdatesResponse], healthy)

      val (service, retryProvider) = startService(sink, connection)
      loggerFactory.suppressWarningsAndErrors {
        eventually(30.seconds) {
          sink.ingestedOffsets.asScala.toList should contain(20L)
        }
      }
      stopService(service, retryProvider)
      succeed
    }

    "not restart a stream that recovers while the ledger end query is in flight" in {
      val sink = new RecordingSink
      val connection = mock[SpliceLedgerConnection]
      // The first ledger end query stays pending until we complete it. Later queries answer immediately.
      val firstLedgerEnd = Promise[Long]()
      val ledgerEndCalls = new AtomicInteger(0)
      // Count calls ourselves: `verify` is not safe to poll while the watchdog thread calls the mock.
      Mockito
        .when(connection.ledgerEnd()(any[TraceContext]))
        .thenAnswer(new Answer[Future[Long]] {
          override def answer(invocation: InvocationOnMock): Future[Long] =
            if (ledgerEndCalls.incrementAndGet() == 1) firstLedgerEnd.future
            else Future.successful(25L)
        })
      val (queue, stream) = Source.queue[GetTreeUpdatesResponse](10).preMaterialize()
      // A second subscription would mean that the stream was restarted.
      when(
        connection.updates(any[Long], any[MultiDomainAcsStore.IngestionFilter])(any[TraceContext])
      )
        .thenReturn(stream, Source.never[GetTreeUpdatesResponse])

      val (service, retryProvider) = startService(sink, connection)
      loggerFactory.suppressWarningsAndErrors {
        // The stream is silent, so the watchdog asks for the ledger end.
        eventually(30.seconds) {
          ledgerEndCalls.get() should be >= 1
        }
        // While that query is in flight the stream recovers and ingests a checkpoint.
        queue.offer(checkpointAt(20L))
        eventually(30.seconds) {
          sink.ingestedOffsets.asScala.toList should contain(20L)
        }
        // The ledger end is still ahead of the ingested offset, but the answer is stale.
        firstLedgerEnd.success(25L)
        Threading.sleep(500)
      }
      val healthyAfterStaleAnswer = service.isHealthy
      stopService(service, retryProvider)
      healthyAfterStaleAnswer shouldBe true
      verify(connection, times(1)).updates(
        any[Long],
        any[MultiDomainAcsStore.IngestionFilter],
      )(any[TraceContext])
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
