// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.data.CantonTimestampExtensions.*
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageWritersGate.GateState
import org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling.BackfillingProgress
import org.lfdecentralizedtrust.splice.scan.store.historystart.{
  FakeHistoryReadiness,
  HistoryReadiness,
  HistoryStart,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

import BulkStorageWritersGateTest.PeerCopy

class BulkStorageWritersGateTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private val migrationId = 3L
  private val from =
    HistoryStart.From(CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T10:15:00Z")))

  private def peerCopy(complete: => Boolean): PeerCopy = {
    val progress = mock[BackfillingProgress]
    when(progress.isComplete(any[TraceContext], any[ExecutionContext]))
      .thenAnswer((_: TraceContext, _: ExecutionContext) => Future.successful(complete))
    PeerCopy(enabled = true, progress)
  }

  private def noPeerCopy: PeerCopy = PeerCopy(enabled = false, mock[BackfillingProgress])

  private def gate(readiness: HistoryReadiness, peerCopy: PeerCopy) =
    new BulkStorageWritersGate(
      readiness,
      migrationId,
      peerBackfillingEnabled = peerCopy.enabled,
      peerBackfillingProgress = peerCopy.progress,
      pollingInterval = 10.millis,
      loggerFactory = loggerFactory,
    )

  private def states(gate: BulkStorageWritersGate): Seq[GateState] =
    Seq(gate.updatesMayStart().futureValue, gate.snapshotsMayStart().futureValue)

  "BulkStorageWritersGate" should {
    "keep both shut while the history start is not determined" in {
      states(gate(new FakeHistoryReadiness(start = None), peerCopy(true))) shouldBe Seq(
        GateState.Closed("the history start of this Scan to be determined"),
        GateState.Closed("the history start of this Scan to be determined"),
      )
    }

    "keep both shut while the history from genesis is not backfilled" in {
      states(
        gate(new FakeHistoryReadiness(start = Some(HistoryStart.Genesis)), peerCopy(true))
      ) shouldBe Seq(
        GateState.Closed("update history backfilling"),
        GateState.Closed("update history backfilling"),
      )
    }

    "keep both shut while the backfilled history waits for the copy from peers" in {
      states(gate(new FakeHistoryReadiness(backfilled = true), peerCopy(false))) shouldBe Seq(
        GateState.Closed("bulk storage backfilling from peers"),
        GateState.Closed("bulk storage backfilling from peers"),
      )
    }

    "open both once the history is backfilled and the copy from peers is complete" in {
      states(gate(new FakeHistoryReadiness(backfilled = true), peerCopy(true))) shouldBe Seq(
        GateState.Open,
        GateState.Open,
      )
    }

    "open both once the history is backfilled when the copy from peers is disabled" in {
      states(gate(new FakeHistoryReadiness(backfilled = true), noPeerCopy)) shouldBe Seq(
        GateState.Open,
        GateState.Open,
      )
    }

    "open updates and keep snapshots shut until the snapshot seed when the history starts after genesis" in {
      states(gate(new FakeHistoryReadiness(start = Some(from)), peerCopy(true))) shouldBe Seq(
        GateState.Open,
        GateState.Closed("the ACS snapshot at the first own segment start to be seeded"),
      )
      states(
        gate(new FakeHistoryReadiness(start = Some(from), snapshotSeeded = true), peerCopy(true))
      ) shouldBe Seq(GateState.Open, GateState.Open)
    }

    "keep both shut while the copy from peers is incomplete when the history starts after genesis" in {
      states(gate(new FakeHistoryReadiness(start = Some(from)), peerCopy(false))) shouldBe Seq(
        GateState.Closed("bulk storage backfilling from peers"),
        GateState.Closed("bulk storage backfilling from peers"),
      )
    }

    "stay shut when the history starts after genesis and the copy from peers is disabled" in {
      val shut = states(gate(new FakeHistoryReadiness(start = Some(from)), noPeerCopy))
      shut.head shouldBe a[GateState.Misconfigured]
      shut.distinct should have size 1
    }

    "emit once when it opens, logging at INFO only when what it waits on changes" in {
      val polls = new AtomicInteger(0)
      val copied = new AtomicBoolean(false)
      val readiness = new FakeHistoryReadiness(
        backfilled = {
          if (polls.incrementAndGet() >= 5) copied.set(true)
          true
        }
      )
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
        gate(readiness, peerCopy(copied.get()))
          .whenUpdatesMayStart("TestWriter")
          .runWith(Sink.seq)
          .futureValue shouldBe Seq(true),
        entries =>
          entries.map(_.message).filter(_.startsWith("TestWriter")) shouldBe Seq(
            "TestWriter waits for bulk storage backfilling from peers",
            "TestWriter starts",
          ),
      )
      polls.get() should be >= 5
    }

    "log a misconfiguration once at ERROR and never open" in {
      loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
        gate(new FakeHistoryReadiness(start = Some(from)), noPeerCopy)
          .whenUpdatesMayStart("TestWriter")
          .takeWithin(200.millis)
          .runWith(Sink.seq)
          .futureValue shouldBe empty,
        entries =>
          entries.map(_.message) should matchPattern {
            case Seq(message: String)
                if message.startsWith("TestWriter cannot start") &&
                  message.contains("bulkStorage.backfilling.enabled") =>
          },
      )
    }
  }
}

object BulkStorageWritersGateTest {
  final case class PeerCopy(enabled: Boolean, progress: BackfillingProgress)
}
