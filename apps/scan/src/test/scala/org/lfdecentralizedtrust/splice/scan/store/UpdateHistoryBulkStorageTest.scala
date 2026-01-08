// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.scan.store.bulk.{BulkStorageConfig, UpdateHistoryBulkStorage}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.{Limit, StoreTest, TreeUpdateWithMigrationId, UpdateHistory}
import org.scalatest.concurrent.PatienceConfiguration

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class UpdateHistoryBulkStorageTest extends StoreTest with HasExecutionContext with HasActorSystem with HasS3Mock {
  val bulkStorageTestConfig = BulkStorageConfig(
    10,
    50000L,
  )

  "UpdateHistoryBulkStorage" should {
    "work" in {
      withS3Mock {
        val store = mockUpdateHistoryStore(15)
//        store.getUpdatesWithoutImportUpdates(None, HardLimit(10).value).map(println(_)).futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(60, "seconds")))
        val bucketConnection = getS3BucketConnection(loggerFactory)
        val bulkStorage = new UpdateHistoryBulkStorage(bulkStorageTestConfig, store, bucketConnection, loggerFactory)
        val segment = bulkStorage.UpdateHistorySegmentBulkStorage(0, CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(0)), 0, CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(18)))
        segment.next().futureValue(timeout = PatienceConfiguration.Timeout(FiniteDuration(60, "seconds"))) shouldBe segment.NotDone
        segment.next().futureValue shouldBe segment.NotReady
        succeed
      }
    }
  }

  def mockUpdateHistoryStore(storeSize: Int): UpdateHistory = {
    val store = mock[UpdateHistory]
    val alicePartyId = mkPartyId("alice")
    val bobPartyId = mkPartyId("bob")
    val charliePartyId = mkPartyId("charlie")
    when(
      store.getUpdatesWithoutImportUpdates(
        any[Option[(Long, CantonTimestamp)]],
        any[Limit],
        anyBoolean,
      )(any[TraceContext])
    ).thenAnswer {
        (
          afterO: Option[(Long, CantonTimestamp)],
          limit: Limit,
          afterIsInclusive: Boolean,
        ) => Future {
          val afterIdx = afterO.map { case (_, t) => t.toEpochMilli }.getOrElse(0L)
          val fromIdx = if (afterIsInclusive) afterIdx else afterIdx + 1
          val remaining = storeSize - fromIdx
          val numElems = math.min(limit.limit.toLong, remaining)
          Seq
            .range(0, numElems)
            .map(i => {
              val idx = i + fromIdx
              val contract = amulet(
                alicePartyId,
                BigDecimal(idx),
                0L,
                BigDecimal(0.1),
                contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
              )
              val tx = mkCreateTx(
                idx,
                Seq(contract),
                Instant.ofEpochMilli(idx),
                Seq(alicePartyId, bobPartyId),
                dummyDomain,
                "",
                Instant.ofEpochMilli(idx),
                Seq(charliePartyId)
              )
              new TreeUpdateWithMigrationId(UpdateHistoryResponse(TransactionTreeUpdate(tx), dummyDomain), 0)
            })
        }
      }
    store
  }
}
