// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import scala.concurrent.ExecutionContext
import com.digitalasset.canton.config.TopologyConfig.NotUsed
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Sink, Source, SourceQueueWithComplete}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.admin.http.ProtobufJsonScanHttpEncodings
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit}

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.Future
import io.circe.syntax.*
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}
import software.amazon.awssdk.services.s3.model.PutObjectRequest

import java.net.URI

case class S3Config(
    endpoint: URI,
    bucketName: String,
    region: Region,
    credentials: AwsBasicCredentials,
)

case class BulkStorageConfig(
    dbReadChunkSize: Int,
    maxFileSize: Long,
)

sealed trait Position
case object Start extends Position
case object End extends Position
final case class Index(value: Long) extends Position

class AcsSnapshotBulkStorage(
    val acsSnapshotStore: AcsSnapshotStore,
    val s3Config: S3Config,
    override val loggerFactory: NamedLoggerFactory,
)(implicit actorSystem: ActorSystem, tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  val bulkStorageConfigV1 = BulkStorageConfig(
    1000,
//    (64 * 1024 * 1024).toLong,
    50000L,
  )

  val s3Client: S3Client = S3Client
    .builder()
    .endpointOverride(s3Config.endpoint)
    .region(s3Config.region)
    .credentialsProvider(StaticCredentialsProvider.create(s3Config.credentials))
    // TODO: mockS3 supports only path style access. Do we need to make this configurable?
    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
    .build()

  def getAcsSnapshotChunk(
      migrationId: Long,
      timestamp: CantonTimestamp,
      after: Option[Long],
  ): Future[(Position, ByteString)] = {
    for {
      snapshot <- acsSnapshotStore.queryAcsSnapshot(
        migrationId,
        snapshot = timestamp,
        after,
        limit = HardLimit.tryCreate(bulkStorageConfigV1.dbReadChunkSize),
        Seq.empty,
        Seq.empty,
      )
      // FIXME: double check if the http API returned by javaToHttpCreatedEvent fits, or we need something slightly different

    } yield {
      val encoded = snapshot.createdEventsInPage.map(event =>
        ProtobufJsonScanHttpEncodings.javaToHttpCreatedEvent(event.eventId, event.event)
      )
      val contractsStr = encoded.map(_.asJson.noSpacesSortKeys).mkString("\n")
      val contractsBytes = ByteString(contractsStr)
      logger.debug(
        s"Read ${encoded.length} contracts from ACS, to a bytestring of size ${contractsBytes.length} bytes"
      )
      (snapshot.afterToken.fold(End: Position)(Index(_)), contractsBytes)
    }

  }

  def dumpAcsSnapshot(migrationId: Long, timestamp: CantonTimestamp): Future[Unit] = {

    val idx = new AtomicLong()

    Source
      .unfoldAsync(Start: Position) {
        case Start => getAcsSnapshotChunk(migrationId, timestamp, None).map(Some(_))
        case Index(i) => getAcsSnapshotChunk(migrationId, timestamp, Some(i)).map(Some(_))
        case End => Future.successful(None)
      }
      .via(ZstdGroupedWeight(bulkStorageConfigV1.maxFileSize))
      // Add a buffer so that the next object continues accumulating while we write the previous one
      .buffer(
        1,
        OverflowStrategy.backpressure,
      )
      .mapAsync(1) { zstdObj =>
        val objectKey = s"snapshot_${idx.get()}.zstd"
        val putObj: PutObjectRequest = PutObjectRequest
          .builder()
          .bucket(s3Config.bucketName)
          .key(objectKey)
          .build()
        idx.set(idx.get() + 1)
        Future {
          s3Client.putObject(
            putObj,
            RequestBody.fromBytes(zstdObj.toArrayUnsafe()),
          )
        }
      }
      .runWith(Sink.ignore)

  }.map(_ => ())
}
