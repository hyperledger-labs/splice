// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.*
import org.scalatest.concurrent.PatienceConfiguration
import software.amazon.awssdk.services.s3.model.ListObjectsRequest

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

class UpdateHistoryBulkStorageTest
    extends StoreTest
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock {
  val maxFileSize = 30000L
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24,
    bulkDbReadChunkSize = 1000,
    maxFileSize,
  )

  "UpdateHistoryBulkStorage" should {
    "work" in {
      withS3Mock {
        val initialStoreSize = 1500
        val segmentSize = 2200L
        val segmentFromTimestamp = 100L
        val mockStore = new MockUpdateHistoryStore(initialStoreSize)
        val bucketConnection = getS3BucketConnection(loggerFactory)
        val fromTimestamp =
          CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp))
        val toTimestamp =
          CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp + segmentSize))
        val segment = new UpdateHistorySegmentBulkStorage(
          bulkStorageTestConfig,
          mockStore.store,
          bucketConnection,
          0,
          fromTimestamp,
          0,
          toTimestamp,
          loggerFactory,
        )
        clue(
          "Start reading updates, should push 1000 updates, and not be ready for the next 1000"
        ) {
          segment
            .next()
            .futureValue(timeout =
              PatienceConfiguration.Timeout(FiniteDuration(120, "seconds"))
            ) shouldBe Result.NotDone
          segment.next().futureValue shouldBe Result.NotReady
        }
        clue(
          "Ingest 1000 more events, and continue reading. Should push 1000 more, then 200 more and be done with the segment"
        ) {
          mockStore.mockIngestion(1000)
          segment.next().futureValue shouldBe Result.NotDone
          segment.next().futureValue shouldBe Result.Done
        }
        segment.watchCompletion().futureValue
        for {
          s3Objects <- bucketConnection.s3Client
            .listObjects(
              ListObjectsRequest.builder().bucket("bucket").build()
            )
            .asScala
          allUpdates <- mockStore.store.getUpdatesWithoutImportUpdates(
            None,
            HardLimit.tryCreate(segmentSize.toInt * 2, segmentSize.toInt * 2),
          )
          segmentUpdates = allUpdates.filter(update =>
            update.update.update.recordTime > fromTimestamp &&
              update.update.update.recordTime <= toTimestamp
          )
        } yield {
          val objectKeys = s3Objects.contents.asScala.sortBy(_.key())
          objectKeys should have length 2
          s3Objects.contents().get(0).size().toInt should be >= maxFileSize.toInt
          val allUpdatesFromS3 = objectKeys.flatMap(
            readUncompressAndDecode(bucketConnection, io.circe.parser.decode[UpdateHistoryItemV2])
          )
          allUpdatesFromS3.length shouldBe segmentUpdates.length
          allUpdatesFromS3
            .map(
              CompactJsonScanHttpEncodingsWithFieldLabels().httpToLapiUpdate
            ) should contain theSameElementsInOrderAs segmentUpdates
        }
      }
    }
  }

  class MockUpdateHistoryStore(val initialStoreSize: Int) {

    private var storeSize = initialStoreSize
    val store = mockUpdateHistoryStore()

    def mockIngestion(extraUpdates: Int) = { storeSize = storeSize + extraUpdates }

    def mockUpdateHistoryStore(): UpdateHistory = {
      val store = mock[UpdateHistory]
      val alicePartyId = mkPartyId("alice")
      val bobPartyId = mkPartyId("bob")
      val charliePartyId = mkPartyId("charlie")
      when(
        store.getUpdatesWithoutImportUpdates(
          any[Option[(Long, CantonTimestamp)]],
          any[Limit],
        )(any[TraceContext])
      ).thenAnswer {
        (
            afterO: Option[(Long, CantonTimestamp)],
            limit: Limit,
        ) =>
          Future {
            val fromIdx = afterO.map { case (_, t) => t.toEpochMilli }.getOrElse(0L) + 1
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
                  1, // not used in updates v2 (TODO(#3429): double-check what the actual value in the updateHistory is. The parser in read (httpToLapiTransaction) sets this to 1, so for now we use 1 here too.)
                  Seq(contract),
                  Instant.ofEpochMilli(idx),
                  Seq(alicePartyId, bobPartyId),
                  dummyDomain,
                  "",
                  Instant.ofEpochMilli(idx),
                  Seq(charliePartyId),
                  updateId = idx.toString,
                )
                new TreeUpdateWithMigrationId(
                  UpdateHistoryResponse(TransactionTreeUpdate(tx), dummyDomain),
                  0,
                )
              })
          }
      }
      store
    }

  }
}
