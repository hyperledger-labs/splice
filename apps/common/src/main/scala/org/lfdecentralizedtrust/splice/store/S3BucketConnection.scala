// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.S3Config
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

class S3BucketConnection(
    s3Client: S3AsyncClient,
    bucketName: String,
    val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  // Reads the full content of an s3 object into a ByteBuffer.
  // Use only for testing, when the object size is known to be small
  def readFullObject(key: String)(implicit ec: ExecutionContext): Future[ByteBuffer] = {
    val request = GetObjectRequest.builder().bucket(bucketName).key(key).build()
    s3Client
      .getObject(request, AsyncResponseTransformer.toBytes[GetObjectResponse])
      .asScala
      .map(_.asByteBuffer())
  }

  // Writes a full object from memory into an s3 object
  def writeFullObject(key: String, content: ByteBuffer)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Future[Unit] = {
    logger.debug(s"Writing ${content.array().length} bytes to S3 object $key")
    val putObj: PutObjectRequest = PutObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()
    s3Client
      .putObject(
        putObj,
        AsyncRequestBody.fromBytes(content.array()),
      )
      .asScala
      // TODO(#3429): consider checking the checksum from the response
      .map(_ => ())
  }

  def newAppendWriteObject(
      key: String
  )(implicit ec: ExecutionContext): AppendWriteObject = new AppendWriteObject(key)

  def listObjects: Future[ListObjectsResponse] =
    s3Client.listObjects(ListObjectsRequest.builder().bucket(bucketName).build()).asScala

  /** Wrapper around multi-part upload that simplifies uploading parts in order
    */
  class AppendWriteObject protected[S3BucketConnection] (val key: String)(implicit
      ec: ExecutionContext
  ) {
    val createRequest = CreateMultipartUploadRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build();

    private val uploadId = s3Client.createMultipartUpload(createRequest).asScala.map(_.uploadId())
    private val numParts = new AtomicInteger(0)
    private val parts = TrieMap.empty[Integer, CompletedPart]

    /** Call this once before uploading a new part.
      */
    def prepareUploadNext(): Int = numParts.incrementAndGet()

    /** Thread safe, may be called in parallel.
      *       partNumber must be an index returned from prepareUploadNext()
      */
    def upload(partNumber: Int, content: ByteBuffer): Future[Unit] = {
      require(numParts.get() >= partNumber)
      for {
        id <- uploadId
        partRequest = UploadPartRequest
          .builder()
          .bucket(bucketName)
          .key(key)
          .uploadId(id)
          .partNumber(partNumber)
          .build()
        response <- s3Client
          .uploadPart(partRequest, AsyncRequestBody.fromByteBuffer(content))
          .asScala
        res <- parts
          .put(
            partNumber,
            CompletedPart
              .builder()
              .partNumber(partNumber)
              .eTag(response.eTag())
              .build(),
          )
          .fold(
            Future.successful(())
          )(_ =>
            Future.failed(new RuntimeException(s"Part number $partNumber uploaded more than once"))
          )

      } yield {
        res
      }
    }

    def finish(): Future[Unit] = {
      require(numParts.get() > 0)
      require(
        parts.size == numParts.get(),
        "finish may not be called before all parts have finished uploading",
      )
      for {
        id <- uploadId
        completedMultipartUpload = CompletedMultipartUpload
          .builder()
          .parts(parts.toSeq.sortBy(_._1).map(_._2).asJava)
          .build();

        completeRequest = CompleteMultipartUploadRequest
          .builder()
          .bucket(bucketName)
          .key(key)
          .uploadId(id)
          .multipartUpload(completedMultipartUpload)
          .build()

        _ <- s3Client.completeMultipartUpload(completeRequest).asScala
      } yield {
        // TODO(#3429): consider checking the checksum from the response
        ()
      }
    }
  }
}

object S3BucketConnection {
  def apply(
      s3Config: S3Config,
      loggerFactory: NamedLoggerFactory,
  ): S3BucketConnection = {
    new S3BucketConnection(
      S3AsyncClient
        .builder()
        .endpointOverride(URI.create(s3Config.endpoint))
        .region(
          Region.of(s3Config.region)
        ) // TODO(#3429): support global regions? The constructor with global=true seems to be private..
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)
          )
        )
        // TODO(#3429): mockS3 and GCS support only path style access. Do we need to make this configurable?
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build(),
      s3Config.bucketName,
      loggerFactory,
    )
  }
}
