// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.lfdecentralizedtrust.splice.environment.ledger.api.TransactionTreeUpdate
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.{ScanKeyValueProvider, ScanKeyValueStore}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.store.*
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import software.amazon.awssdk.services.s3.model.ListObjectsRequest

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

class UpdateHistoryBulkStorageTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {
  val maxFileSize = 30000L
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 1,
    bulkAcsSnapshotPeriodHours = 2,
    bulkDbReadChunkSize = 1000,
    maxFileSize,
  )

  "UpdateHistoryBulkStorage" should {

    "successfully dump a single segment of updates to an s3 bucket" in {
      withS3Mock(loggerFactory) { (bucketConnection: S3BucketConnection) =>
        val initialStoreSize = 1500
        val segmentSize = 2200L
        val segmentFromTimestamp = 100L
        val mockStore =
          new MockUpdateHistoryStore(initialStoreSize, Instant.ofEpochMilli, _.toEpochMilli)
        val fromTimestamp =
          CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp))
        val toTimestamp =
          CantonTimestamp.tryFromInstant(Instant.ofEpochMilli(segmentFromTimestamp + segmentSize))

        clue("Wait for the store to be ready by getting the first update from it") {
          eventually(2.minutes) {
            mockStore.store
              .getUpdatesWithoutImportUpdates(None, HardLimit.tryCreate(1))
              .futureValue should not be Seq.empty
          }
        }

        val probe = UpdateHistorySegmentBulkStorage
          .asSource(
            bulkStorageTestConfig,
            mockStore.store,
            bucketConnection,
            UpdatesSegment(
              TimestampWithMigrationId(fromTimestamp, 0),
              TimestampWithMigrationId(toTimestamp, 0),
            ),
            loggerFactory,
          )
          .toMat(TestSink.probe[TimestampWithMigrationId])(Keep.right)
          .run()

        probe.request(2)

        clue(
          "Initially, 1000 updates will be ready, but the segment will not be complete, so no output is expected"
        ) {
          probe.expectNoMessage(20.seconds)
        }

        clue(
          "Ingest 1000 more events. Now the last timestamp will be beyond the segment, so the source will complete and emit the last timestamp"
        ) {
          mockStore.mockIngestion(1000)
          probe.expectNext(20.seconds) should be(TimestampWithMigrationId(toTimestamp, 0))
          probe.expectComplete()
        }

        clue("Check that the dumped content is correct") {
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

    "successfully dump all segments" in {
      withS3Mock(loggerFactory) { (bucketConnection: S3BucketConnection) =>
        val initialStoreSize = 2000
        val genesisDate = LocalDate.of(2001, 1, 23)
        val genesisInstant = genesisDate.atTime(2, 34).toInstant(ZoneOffset.UTC)
        logger.info(s"Genesis instant is: ${genesisInstant}")

        val mockStore = new MockUpdateHistoryStore(
          initialStoreSize,
          i => genesisInstant.plusSeconds(i * 10),
          t => ChronoUnit.SECONDS.between(genesisInstant, t.toInstant) / 10,
        )
        clue("Wait for the store to be ready by getting the first update from it") {
          eventually(2.minutes) {
            mockStore.store
              .getUpdatesWithoutImportUpdates(None, HardLimit.tryCreate(1))
              .futureValue should not be Seq.empty
          }
        }

        for {
          kvProvider <- mkProvider
        } yield {
          val (killSwitch, probe) = new UpdateHistoryBulkStorage(
            bulkStorageTestConfig,
            mockStore.store,
            kvProvider,
            0L,
            bucketConnection,
            loggerFactory,
          ).getSource()
            .toMat(TestSink.probe[TimestampWithMigrationId])(Keep.both)
            .run()

          probe.request(4)
          probe.expectNext(20.seconds) shouldBe TimestampWithMigrationId(
            CantonTimestamp.tryFromInstant(genesisDate.atTime(4, 0).toInstant(ZoneOffset.UTC)),
            0,
          )
          probe.expectNext(20.seconds) shouldBe TimestampWithMigrationId(
            CantonTimestamp.tryFromInstant(genesisDate.atTime(6, 0).toInstant(ZoneOffset.UTC)),
            0,
          )
          probe.expectNext(20.seconds) shouldBe TimestampWithMigrationId(
            CantonTimestamp.tryFromInstant(genesisDate.atTime(8, 0).toInstant(ZoneOffset.UTC)),
            0,
          )
          // First 2000 events end up 08:07:10, so the last full segment is the one up to 08:00
          probe.expectNoMessage(20.seconds)

          killSwitch.shutdown()
          succeed
        }
      }
    }
  }

  class MockUpdateHistoryStore(
      val initialStoreSize: Int,
      val idxToTimestamp: Long => Instant,
      val timestampToIdx: CantonTimestamp => Long,
  ) {

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
            val fromIdx =
              afterO.map { case (_, t) => math.max(timestampToIdx(t), 0) }.getOrElse(0L) + 1
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
                  idxToTimestamp(idx),
                  Seq(alicePartyId, bobPartyId),
                  dummyDomain,
                  "",
                  idxToTimestamp(idx),
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
