// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Sink
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkStorageWritersGate.Decision
import org.lfdecentralizedtrust.splice.scan.store.historystart.{
  FakeHistoryReadiness,
  HistoryReadiness,
  HistoryStart,
}
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.Future
import scala.concurrent.duration.*

class BulkStorageWritersGateTest
    extends AnyWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private val migrationId = 3L
  private val from =
    HistoryStart.From(CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T10:15:00Z")))

  private def peerCopy(complete: => Boolean): Option[TraceContext => Future[Boolean]] =
    Some(_ => Future.successful(complete))

  private def gate(
      readiness: HistoryReadiness,
      peerCopyComplete: Option[TraceContext => Future[Boolean]],
  ) =
    new BulkStorageWritersGate(readiness, migrationId, peerCopyComplete, 10.millis, loggerFactory)

  private def decisions(gate: BulkStorageWritersGate): Seq[Decision] =
    Seq(gate.updatesMayStart().futureValue, gate.snapshotsMayStart().futureValue)

  "BulkStorageWritersGate" should {
    "keep both shut while the history start is not determined" in {
      decisions(gate(new FakeHistoryReadiness(start = None), peerCopy(true))) shouldBe Seq(
        Decision.Waiting("the history start of this Scan to be determined"),
        Decision.Waiting("the history start of this Scan to be determined"),
      )
    }

    "keep both shut while the history from genesis is not backfilled" in {
      decisions(
        gate(new FakeHistoryReadiness(start = Some(HistoryStart.Genesis)), peerCopy(true))
      ) shouldBe Seq(
        Decision.Waiting("update history backfilling"),
        Decision.Waiting("update history backfilling"),
      )
    }

    "keep both shut while the backfilled history waits for the copy from peers" in {
      decisions(gate(new FakeHistoryReadiness(backfilled = true), peerCopy(false))) shouldBe Seq(
        Decision.Waiting("bulk storage backfilling from peers"),
        Decision.Waiting("bulk storage backfilling from peers"),
      )
    }

    "open both once the history is backfilled and the copy from peers is complete" in {
      decisions(gate(new FakeHistoryReadiness(backfilled = true), peerCopy(true))) shouldBe Seq(
        Decision.Open,
        Decision.Open,
      )
    }

    "open both once the history is backfilled when the copy from peers is disabled" in {
      decisions(gate(new FakeHistoryReadiness(backfilled = true), None)) shouldBe Seq(
        Decision.Open,
        Decision.Open,
      )
    }

    "open updates and keep snapshots shut until the snapshot seed when the history starts after genesis" in {
      decisions(gate(new FakeHistoryReadiness(start = Some(from)), peerCopy(true))) shouldBe Seq(
        Decision.Open,
        Decision.Waiting("the ACS snapshot at the first own segment start to be seeded"),
      )
      decisions(
        gate(new FakeHistoryReadiness(start = Some(from), snapshotSeeded = true), peerCopy(true))
      ) shouldBe Seq(Decision.Open, Decision.Open)
    }

    "keep both shut while the copy from peers is incomplete when the history starts after genesis" in {
      decisions(gate(new FakeHistoryReadiness(start = Some(from)), peerCopy(false))) shouldBe Seq(
        Decision.Waiting("bulk storage backfilling from peers"),
        Decision.Waiting("bulk storage backfilling from peers"),
      )
    }

    "stay shut when the history starts after genesis and the copy from peers is disabled" in {
      val shut = decisions(gate(new FakeHistoryReadiness(start = Some(from)), None))
      shut.head shouldBe a[Decision.Misconfigured]
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
        gate(new FakeHistoryReadiness(start = Some(from)), None)
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
