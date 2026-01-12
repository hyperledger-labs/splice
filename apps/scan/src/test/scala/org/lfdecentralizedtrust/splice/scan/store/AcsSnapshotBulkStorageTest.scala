// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStorage,
  BulkStorageConfig,
  S3BucketConnection,
}
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit, StoreTest}
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.util.PackageQualifiedName
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import software.amazon.awssdk.services.s3.model.ListObjectsRequest

import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*
import scala.jdk.CollectionConverters.*

class AcsSnapshotBulkStorageTest
    extends StoreTest
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock {

  val acsSnapshotSize = 48500
  val bulkStorageTestConfig = BulkStorageConfig(
    1000,
    50000L,
  )

  "AcsSnapshotBulkStorage" should {
    "work" in {
      withS3Mock {
        val store = mockAcsSnapshotStore(acsSnapshotSize)
        val timestamp = CantonTimestamp.now()
        val s3BucketConnection = getS3BucketConnectionWithInjectedErrors(loggerFactory)
        for {
          _ <- new AcsSnapshotBulkStorage(
            bulkStorageTestConfig,
            store,
            s3BucketConnection,
            loggerFactory,
          ).dumpAcsSnapshot(0, timestamp)

          s3Objects <- s3BucketConnection.s3Client
            .listObjects(
              ListObjectsRequest.builder().bucket("bucket").build()
            )
            .asScala
          allContracts <- store
            .queryAcsSnapshot(
              0,
              timestamp,
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
            CompactJsonScanHttpEncodingsWithFieldLabels.httpToJavaCreatedEvent
          ) should contain theSameElementsInOrderAs allContracts.map(_.event)
        }
      }
    }
  }

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
    }
    store
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
            invocation.callRealMethod().asInstanceOf[Future[Unit]]
          }
        case _ =>
          invocation.callRealMethod().asInstanceOf[Future[Unit]]
      }
    }.when(s3BucketConnectionWithErrors)
      .writeFullObject(anyString(), any[ByteBuffer])(any[TraceContext], any[ExecutionContext])
    s3BucketConnectionWithErrors
  }

}
