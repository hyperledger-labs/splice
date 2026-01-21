// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.StoreTest
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

class ScanKeyValueProviderTest
    extends StoreTest
    with Matchers
    with HasExecutionContext
    with SplicePostgresTest {
  "ScanKeyValueProvider" should {
    "set and get acs snapshots timestamps" in {
      val ts = CantonTimestamp.now()
      val migrationId = 7L
      for {
        provider <- mkProvider
        _ <- provider.setLatestAcsSnapshotsInBulkStorage(ts, migrationId)
        readBack <- provider.getLatestAcsSnapshotInBulkStorage().value
      } yield {
        readBack.value shouldBe (ts, migrationId)
      }
    }
  }

  def mkProvider: Future[ScanKeyValueProvider] = {
    ScanKeyValueStore(
      dsoParty = dsoParty,
      participantId = mkParticipantId("participant"),
      storage,
      loggerFactory,
    ).map(new ScanKeyValueProvider(_, loggerFactory))
  }

  override protected def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[?] = resetAllAppTables(storage)
}
