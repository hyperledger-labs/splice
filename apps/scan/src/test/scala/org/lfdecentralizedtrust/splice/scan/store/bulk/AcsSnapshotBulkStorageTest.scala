// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.stream.scaladsl.{Keep, Sink}
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig
import org.lfdecentralizedtrust.splice.scan.store.{
  AcsSnapshotStore,
  ScanKeyValueProvider,
  ScanKeyValueStore,
}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.store.db.SplicePostgresTest
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, StoreTest}
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import software.amazon.awssdk.services.s3.model.ListObjectsRequest

import java.nio.ByteBuffer
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.concurrent.duration.*

class AcsSnapshotBulkStorageTest
    extends StoreTest
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock
    with SplicePostgresTest {

  val acsSnapshotSize = 48500
  val bulkStorageTestConfig = ScanStorageConfig(
    dbAcsSnapshotPeriodHours = 3,
    bulkAcsSnapshotPeriodHours = 24, // FIXME: improve the test, to test this
    bulkDbReadChunkSize = 1000,
    bulkMaxFileSize = 50000L,
  )

  "AcsSnapshotBulkStorage" should {
    "successfully dump a single ACS snapshot" in {
      withS3Mock {
        val ts = CantonTimestamp.now()
        val store = new MockAcsSnapshotStore(ts).store
        val s3BucketConnection = getS3BucketConnectionWithInjectedErrors(loggerFactory)
        for {
          _ <- SingleAcsSnapshotBulkStorage
            .asSource(
              0,
              ts,
              bulkStorageTestConfig,
              store,
              s3BucketConnection,
              loggerFactory,
            )
            .runWith(Sink.ignore)

          s3Objects <- s3BucketConnection.s3Client
            .listObjects(
              ListObjectsRequest.builder().bucket("bucket").build()
            )
            .asScala
          allContracts <- store
            .queryAcsSnapshot(
              0,
              ts,
              None,
              HardLimit.tryCreate(acsSnapshotSize, acsSnapshotSize),
              Seq.empty,
              Seq.empty,
            )
            .map(_.createdEventsInPage)
        } yield {
          val objectKeys = s3Objects.contents.asScala.sortBy(_.key())
          objectKeys should have length 6
          objectKeys.take(objectKeys.size - 1).forall {
            !_.key().endsWith("_last.zstd")
          }
          objectKeys.last.key() should endWith("_last.zstd")

          val allContractsFromS3 = objectKeys.flatMap(
            readUncompressAndDecode(
              s3BucketConnection,
              io.circe.parser.decode[httpApi.CreatedEvent],
            )
          )
          allContractsFromS3.map(
            CompactJsonScanHttpEncodingsWithFieldLabels().httpToJavaCreatedEvent
          ) should contain theSameElementsInOrderAs allContracts.map(_.event)
        }
      }
    }

    "correctly process multiple ACS snapshots" in {
      withS3Mock {
        val ts1 = CantonTimestamp.tryFromInstant(Instant.now().truncatedTo(ChronoUnit.DAYS))
        val ts2 = ts1.add(3.hours)
        val ts3 = ts1.add(24.hours)
        val store = new MockAcsSnapshotStore(ts1)
        val s3BucketConnection = getS3BucketConnectionWithInjectedErrors(loggerFactory)
        for {
          kvProvider <- mkProvider
          (killSwitch, probe) = new AcsSnapshotBulkStorage(
            bulkStorageTestConfig,
            store.store,
            s3BucketConnection,
            kvProvider,
            loggerFactory,
          ).getSource()
            .toMat(TestSink.probe[(Long, CantonTimestamp)])(Keep.both)
            .run()

          _ = clue("Initially, a single snapshot is dumped") {
            probe.request(2)
            probe.expectNext(2.minutes) shouldBe (0, ts1)
            probe.expectNoMessage(10.seconds)
          }
          persistedTs1 <- kvProvider.getLatestAcsSnapshotInBulkStorage().value
          _ = persistedTs1.value shouldBe (0, ts1)

          _ = clue(
            "Add another snapshot to the store, which is not yet dumped because of the longer period on bulk storage"
          ) {
            store.addSnapshot(ts2)
            probe.expectNoMessage(10.seconds)
          }

          _ = clue("Add one more snapshot to the store, at the end of the period") {
            store.addSnapshot(ts3)
            val next = probe.expectNext(2.minutes)
            next shouldBe (0, ts3)
            probe.expectNoMessage(10.seconds)
          }
          persistedTs3 <- kvProvider.getLatestAcsSnapshotInBulkStorage().value
        } yield {
          persistedTs3.value shouldBe (0, ts3)
          killSwitch.shutdown()
          succeed
        }
      }
    }
  }

  class MockAcsSnapshotStore(val initialSnapshotTimestamp: CantonTimestamp) {
    private var snapshots = Seq(initialSnapshotTimestamp)
    val store = mockAcsSnapshotStore(acsSnapshotSize)

    def addSnapshot(timestamp: CantonTimestamp) = { snapshots = snapshots :+ timestamp }

    def mockAcsSnapshotStore(snapshotSize: Int): AcsSnapshotStore = {
      val store = mock[AcsSnapshotStore]
      val partyId = mkPartyId("alice")
      when(
        store.queryAcsSnapshot(
          anyLong,
          any[CantonTimestamp],
          any[Option[Long]],
          any[Limit],
          any[Seq[PartyId]],
          any[Seq[PackageQualifiedName]],
        )(any[TraceContext])
      ).thenAnswer {
        (
            migration: Long,
            timestamp: CantonTimestamp,
            after: Option[Long],
            limit: Limit,
            _: Seq[PartyId],
            _: Seq[PackageQualifiedName],
        ) =>
          if (snapshots.contains(timestamp)) {
            Future {
              val remaining = snapshotSize - after.getOrElse(0L)
              val numElems = math.min(limit.limit.toLong, remaining)
              val result = QueryAcsSnapshotResult(
                migration,
                timestamp,
                Vector
                  .range(0, numElems)
                  .map(i => {
                    val idx = i + after.getOrElse(0L)
                    val amt = amulet(
                      partyId,
                      BigDecimal(idx),
                      0L,
                      BigDecimal(0.1),
                      contractId = LfContractId.assertFromString("00" + f"$idx%064x").coid,
                    )
                    SpliceCreatedEvent(s"#event_id_$idx:1", toCreatedEvent(amt))
                  }),
                if (numElems < remaining) Some(after.getOrElse(0L) + numElems) else None,
              )
              result
            }
          } else {
            Future.failed(
              new RuntimeException(
                s"Unexpected timestamp $timestamp. Known snapshots are: $snapshots"
              )
            )
          }
      }

      when(
        store.lookupSnapshotAfter(
          anyLong,
          any[CantonTimestamp],
        )(any[TraceContext])
      ).thenAnswer {
        (
            _: Long,
            timestamp: CantonTimestamp,
        ) =>
          Future.successful {
            snapshots
              .filter(_ > timestamp)
              .sorted
              .headOption
              .map(next =>
                AcsSnapshotStore.AcsSnapshot(
                  // only record time and migration ID are used, everything else is ignored
                  snapshotRecordTime = next,
                  migrationId = 0L,
                  0L,
                  0L,
                  0L,
                  None,
                  None,
                )
              )
          }

      }

      store
    }
  }

  def getS3BucketConnectionWithInjectedErrors(
      loggerFactory: NamedLoggerFactory
  ): S3BucketConnection = {
    val s3BucketConnection: S3BucketConnection = getS3BucketConnection(loggerFactory)
    val s3BucketConnectionWithErrors = Mockito.spy(s3BucketConnection)
    var failureCount = 0
    val _ = doAnswer { (invocation: InvocationOnMock) =>
      val args = invocation.getArguments
      args.toList match {
        case (key: String) :: _ if key.endsWith("2.zstd") =>
          if (failureCount < 2) {
            failureCount += 1
            Future.failed(new RuntimeException("Simulated S3 write error"))
          } else {
            failureCount = 0
            invocation.callRealMethod().asInstanceOf[Future[Unit]]
          }
        case _ =>
          invocation.callRealMethod().asInstanceOf[Future[Unit]]
      }
    }.when(s3BucketConnectionWithErrors)
      .writeFullObject(anyString(), any[ByteBuffer])(any[TraceContext], any[ExecutionContext])
    s3BucketConnectionWithErrors
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
