// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.daml.ledger.javaapi.data as javaapi
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.github.luben.zstd.ZstdDirectBufferDecompressingStream
import com.google.protobuf.ByteString
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotResult
import org.lfdecentralizedtrust.splice.scan.store.bulk.{
  AcsSnapshotBulkStorage,
  BulkStorageConfig,
  S3BucketConnection,
  S3Config,
}
import org.lfdecentralizedtrust.splice.store.{Limit, StoreTest, HardLimit}
import org.lfdecentralizedtrust.splice.store.events.SpliceCreatedEvent
import org.lfdecentralizedtrust.splice.util.{EventId, PackageQualifiedName, ValueJsonCodecCodegen}
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{ListObjectsRequest, S3Object}

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.Future
import scala.jdk.FutureConverters.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.sys.process.*
import scala.util.Using

class AcsSnapshotBulkStorageTest extends StoreTest with HasExecutionContext with HasActorSystem {

  val acsSnapshotSize = 48500
  val bulkStorageTestConfig = BulkStorageConfig(
    1000,
    50000L,
  )

  "AcsSnapshotSourceTest" should {
    "work" in {
      withS3Mock({
        val store = mockAcsSnapshotStore(acsSnapshotSize)
        val timestamp = CantonTimestamp.now()
        val s3Config = S3Config(
          URI.create("http://localhost:9090"),
          "bucket",
          Region.US_EAST_1,
          AwsBasicCredentials.create("mock_id", "mock_key"),
        )
        val s3BucketConnection = S3BucketConnection(s3Config, "bucket", loggerFactory)
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
          _ = resetCIdCounter()
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
          val allContractsFromS3 = s3Objects.contents.asScala
            .map(readUncompressAndDecode(s3BucketConnection))
            .flatten

          allContractsFromS3.map(
            reconstructFromS3
          ) should contain theSameElementsInOrderAs allContracts.map(_.event)
        }
      })
    }
  }

  // TODO(#3429): consider running s3Mock container as a service in GHA instead of starting it here
  def withS3Mock[A](test: => Future[A]): Future[A] = {
    Seq(
      "docker",
      "run",
      "-p",
      "9090:9090",
      "-e",
      "COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=bucket",
      "-d",
      "--rm",
      "--name",
      "s3mock",
      "adobe/s3mock",
    ).!
    test.andThen({ case _ => Seq("docker", "stop", "s3mock").! })
  }

  def readUncompressAndDecode(
      s3BucketConnection: S3BucketConnection
  )(s3obj: S3Object): Array[httpApi.CreatedEvent] = {
    val compressed = s3BucketConnection.readFullObject(s3obj.key()).futureValue
    val compressedDirect = ByteBuffer.allocateDirect(compressed.capacity())
    val uncompressed = ByteBuffer.allocateDirect(compressed.capacity() * 200)
    compressedDirect.put(compressed)
    compressedDirect.flip()
    Using(new ZstdDirectBufferDecompressingStream(compressedDirect)) { _.read(uncompressed) }
    uncompressed.flip()
    val allContractsStr = StandardCharsets.UTF_8.newDecoder().decode(uncompressed).toString
    val allContracts = allContractsStr.split("\n")
    allContracts.map(io.circe.parser.decode[httpApi.CreatedEvent](_).value)
  }

  // Similar to CompactJsonScanHttpEncodings.httpToJavaCreatedEvent, but does preserve field names, as we want to assert on their equality.
  def reconstructFromS3(createdEvent: httpApi.CreatedEvent): javaapi.CreatedEvent = {
    val templateId = CompactJsonScanHttpEncodings.parseTemplateId(createdEvent.templateId)
    new javaapi.CreatedEvent(
      /*witnessParties = */ java.util.Collections.emptyList(),
      /*offset = */ 0, // not populated
      /*nodeId = */ EventId.nodeIdFromEventId(createdEvent.eventId),
      templateId,
      createdEvent.packageName,
      createdEvent.contractId,
      ValueJsonCodecCodegen
        .deserializableContractPayload(templateId, createdEvent.createArguments.noSpaces)
        .value,
      /*createdEventBlob = */ ByteString.EMPTY,
      /*interfaceViews = */ java.util.Collections.emptyMap(),
      /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
      /* contractKey = */ None.toJava,
      createdEvent.signatories.asJava,
      createdEvent.observers.asJava,
      createdEvent.createdAt.toInstant,
      /* acsDelta = */ false,
      /* representativePackageId = */ templateId.getPackageId,
    )

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
                val amt = amulet(partyId, BigDecimal(idx), 0L, BigDecimal(0.1))
                SpliceCreatedEvent(s"#event_id_$idx:1", toCreatedEvent(amt))
              }),
            if (numElems < remaining) Some(after.getOrElse(0L) + numElems) else None,
          )
          result
        }
    }
    store
  }
}
