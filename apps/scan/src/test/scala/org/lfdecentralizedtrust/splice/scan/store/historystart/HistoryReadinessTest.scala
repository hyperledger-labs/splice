// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.historystart

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{BaseTest, HasExecutionContext}
import org.lfdecentralizedtrust.splice.data.CantonTimestampExtensions.*
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant

class HistoryReadinessTest extends AsyncWordSpec with BaseTest with HasExecutionContext {

  private val migrationId = 3L
  private val from =
    HistoryStart.From(CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T10:15:00Z")))

  "acsSnapshotsMayStart" should {
    "allow snapshots once the history is backfilled, without asking for the history start" in {
      new FakeHistoryReadiness(backfilled = true, start = fail("not asked"))
        .acsSnapshotsMayStart(migrationId)
        .map(_ shouldBe true)
    }

    "wait while the history start is not determined" in {
      new FakeHistoryReadiness(start = None, snapshotSeeded = true)
        .acsSnapshotsMayStart(migrationId)
        .map(_ shouldBe false)
    }

    "wait for the backfill when the history starts at genesis" in {
      new FakeHistoryReadiness(start = Some(HistoryStart.Genesis), snapshotSeeded = true)
        .acsSnapshotsMayStart(migrationId)
        .map(_ shouldBe false)
    }

    "wait for the snapshot seed when the history starts after genesis" in {
      new FakeHistoryReadiness(start = Some(from), snapshotSeeded = false)
        .acsSnapshotsMayStart(migrationId)
        .map(_ shouldBe false)
    }

    "allow snapshots once the snapshot seed is loaded when the history starts after genesis" in {
      new FakeHistoryReadiness(start = Some(from), snapshotSeeded = true)
        .acsSnapshotsMayStart(migrationId)
        .map(_ shouldBe true)
    }
  }
}
