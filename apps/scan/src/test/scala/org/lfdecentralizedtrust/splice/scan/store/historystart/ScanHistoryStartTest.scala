// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import io.circe.syntax.*
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

class ScanHistoryStartTest extends AsyncWordSpec with BaseTest with HasExecutionContext {

  private val storageConfig = ScanStorageConfigs.scanStorageConfigV1

  private def ts(iso: String): CantonTimestamp =
    CantonTimestamp.tryFromInstant(Instant.parse(iso))

  private class InMemoryStore extends HistoryStartStore {
    val value = new AtomicReference[Option[HistoryStart]](None)
    override def read(implicit tc: TraceContext) = Future.successful(value.get())
    override def recordOnce(start: HistoryStart)(implicit tc: TraceContext) =
      Future.successful(value.updateAndGet(_.orElse(Some(start))).getOrElse(start))
    override def reset()(implicit tc: TraceContext) = Future.successful(value.set(None))
  }

  private class FakeSources(
      override val isFoundingSv: Boolean = false,
      override val historyBackfillEnabled: Boolean = false,
      backfilled: => Option[Boolean] = Some(false),
      hostedSince: => Option[CantonTimestamp] = None,
  ) extends HistoryStartSources {
    override def historyBackfilledFromGenesis(implicit tc: TraceContext) =
      Future.successful(backfilled)
    override def dsoPartyHostedSince(implicit tc: TraceContext) =
      Future.successful(hostedSince)
  }

  private def historyStart(store: HistoryStartStore, sources: HistoryStartSources) =
    new ScanHistoryStart(store, sources, loggerFactory)

  "ScanHistoryStart" should {
    "determine the history start again after forgetting a recorded one" in {
      val store = new InMemoryStore
      store.value.set(Some(HistoryStart.From(ts("2026-01-02T10:15:00Z"))))
      val start = historyStart(store, new FakeSources(backfilled = Some(true)))
      for {
        before <- start.get
        _ <- loggerFactory.assertLogs(
          start.forgetRecorded(),
          _.warningMessage should include("Forgot the recorded history start"),
        )
        after <- start.get
      } yield {
        before shouldBe Some(HistoryStart.From(ts("2026-01-02T10:15:00Z")))
        after shouldBe Some(HistoryStart.Genesis)
        store.value.get() shouldBe Some(HistoryStart.Genesis)
      }
    }

    "record genesis on the founding SV without asking anything else" in {
      val store = new InMemoryStore
      historyStart(
        store,
        new FakeSources(
          isFoundingSv = true,
          backfilled = fail("not asked"),
          hostedSince = fail("not asked"),
        ),
      ).get.map { start =>
        start shouldBe Some(HistoryStart.Genesis)
        store.value.get() shouldBe Some(HistoryStart.Genesis)
      }
    }

    "record genesis on an SV whose update history is backfilled from genesis" in {
      val store = new InMemoryStore
      historyStart(store, new FakeSources(backfilled = Some(true))).get.map {
        _ shouldBe Some(HistoryStart.Genesis)
      }
    }

    "record nothing while the update history backfill is enabled and not finished, then genesis" in {
      val store = new InMemoryStore
      val hosted = ts("2026-01-02T10:15:00Z")
      val backfilled = new AtomicReference[Option[Boolean]](Some(false))
      val sources = new FakeSources(
        historyBackfillEnabled = true,
        backfilled = backfilled.get(),
        hostedSince = Some(hosted),
      )
      for {
        whileBackfilling <- historyStart(store, sources).get
        _ = backfilled.set(Some(true))
        afterBackfill <- historyStart(store, sources).get
      } yield {
        whileBackfilling shouldBe None
        afterBackfill shouldBe Some(HistoryStart.Genesis)
        store.value.get() shouldBe Some(HistoryStart.Genesis)
      }
    }

    "record the time the DSO party became hosted on a joining SV" in {
      val store = new InMemoryStore
      val hosted = ts("2026-01-02T10:15:00Z")
      historyStart(store, new FakeSources(hostedSince = Some(hosted))).get.map { start =>
        start shouldBe Some(HistoryStart.From(hosted))
        store.value.get() shouldBe Some(HistoryStart.From(hosted))
      }
    }

    "record nothing while the update history is not ready or the DSO party is not hosted yet" in {
      val store = new InMemoryStore
      for {
        notReady <- historyStart(store, new FakeSources(backfilled = None)).get
        notHosted <- historyStart(store, new FakeSources()).get
      } yield {
        notReady shouldBe None
        notHosted shouldBe None
        store.value.get() shouldBe None
      }
    }

    "keep the recorded value when the sources later say otherwise" in {
      val store = new InMemoryStore
      val hosted = ts("2026-01-02T10:15:00Z")
      val backfilled = new AtomicReference[Option[Boolean]](Some(false))
      val sources = new FakeSources(backfilled = backfilled.get(), hostedSince = Some(hosted))
      for {
        first <- historyStart(store, sources).get
        _ = backfilled.set(Some(true))
        second <- historyStart(store, sources).get
      } yield {
        first shouldBe Some(HistoryStart.From(hosted))
        second shouldBe Some(HistoryStart.From(hosted))
      }
    }
  }

  "HistoryStart" should {
    "put the first own segment of a joining SV at the next snapshot time after its start" in {
      val start = ts("2026-01-02T10:15:00Z")
      HistoryStart.From(start).firstOwnSegmentStart(storageConfig) shouldBe
        storageConfig.computeBulkSnapshotTimeAfter(start)
      HistoryStart.From(start).firstOwnSegmentStart(storageConfig) should be > start
    }

    "put the first own segment of a Scan with complete history at genesis" in {
      HistoryStart.Genesis.firstOwnSegmentStart(storageConfig) shouldBe CantonTimestamp.MinValue
    }

    "round-trip through its stored encoding" in {
      import KvHistoryStartStore.historyStartCodec
      val values: Seq[HistoryStart] =
        Seq[HistoryStart](HistoryStart.Genesis, HistoryStart.From(ts("2026-01-02T10:15:00Z")))
      values.map(v => v.asJson.as[HistoryStart]) shouldBe values.map(Right(_))
    }
  }
}
