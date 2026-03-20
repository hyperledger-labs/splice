// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.config.S3Config
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.*
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

class S3BucketConnection(
    s3Config: S3Config,
    val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  val s3Client = S3AsyncClient
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
    .build()
  val bucketName = s3Config.bucketName

  def newAppendWriteObject(
      key: String
  )(implicit ec: ExecutionContext): AppendWriteObject = new AppendWriteObject(key)

  def listObjects: Future[ListObjectsResponse] =
    s3Client.listObjects(ListObjectsRequest.builder().bucket(bucketName).build()).asScala

  def readObject(
      key: String
  )(implicit ec: ExecutionContext): Future[Source[ByteString, NotUsed]] = {
    val request = GetObjectRequest.builder().bucket(bucketName).key(key).build()

    s3Client
      .getObject(request, AsyncResponseTransformer.toPublisher[GetObjectResponse]())
      .asScala
      .map { publisher =>
        org.apache.pekko.stream.scaladsl.Source.fromPublisher(publisher)
      }
      .map { _.map(ByteString.fromByteBuffer) }
  }

  /** Wrapper around multi-part upload that simplifies uploading parts in order
    */
  class AppendWriteObject protected[S3BucketConnection] (val key: String)(implicit
      ec: ExecutionContext
  ) {
    val createRequest = CreateMultipartUploadRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .checksumAlgorithm(ChecksumAlgorithm.SHA256)
      .build()

    private val uploadId = s3Client.createMultipartUpload(createRequest).asScala.map(_.uploadId())
    private val numParts = new AtomicInteger(0)
    private val parts = TrieMap.empty[Integer, CompletedPart]
    private val md = MessageDigest.getInstance("SHA-256")

    /** Call this once before uploading a new part.
      *  The content must already be provided for checksums, but will not be uploaded yet.
      */
    def prepareUploadNext(content: ByteBuffer): Int = {
      md.update(content.duplicate())
      numParts.incrementAndGet()
    }

    /** Thread safe, may be called in parallel.
      *       partNumber must be an index returned from prepareUploadNext()
      */
    def upload(partNumber: Int, content: ByteBuffer): Future[Unit] = {
      require(numParts.get() >= partNumber)
      val partMd = MessageDigest.getInstance("SHA-256")
      partMd.update(content.duplicate())
      val partDigest = partMd.digest()
      for {
        id <- uploadId
        partRequest = UploadPartRequest
          .builder()
          .bucket(bucketName)
          .key(key)
          .uploadId(id)
          .partNumber(partNumber)
          .checksumAlgorithm(ChecksumAlgorithm.SHA256)
          .checksumSHA256(Base64.getEncoder.encodeToString(partDigest))
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
              .checksumSHA256(response.checksumSHA256())
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
          .build()

        completeRequest = CompleteMultipartUploadRequest
          .builder()
          .bucket(bucketName)
          .key(key)
          .uploadId(id)
          .multipartUpload(completedMultipartUpload)
          .build()

        _ <- s3Client.completeMultipartUpload(completeRequest).asScala

        // Copy-in-place of the object to add the final checksum to its metadata
        metadata = Map("splice-checksum" -> Base64.getEncoder.encodeToString(md.digest()))

        copyReq = CopyObjectRequest
          .builder()
          .destinationBucket(bucketName)
          .destinationKey(key)
          .sourceBucket(bucketName)
          .sourceKey(key)
          // Tells S3/GCS to ignore old metadata and use the new map
          .metadataDirective(MetadataDirective.REPLACE)
          .metadata(metadata.asJava)
          .build()

        _ <- s3Client.copyObject(copyReq).asScala

      } yield {
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
      s3Config,
      loggerFactory,
    )
  }
}
