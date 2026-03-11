// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
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

  def readChecksum(key: String)(implicit ec: ExecutionContext): Future[String] = {
    val checksumRequest = GetObjectTaggingRequest.builder().bucket(bucketName).key(key).build()
    for {
      checksumResponse <- s3Client
        .getObjectTagging(checksumRequest)
        .asScala
      checksum = checksumResponse
        .tagSet()
        .asScala
        .find(_.key() == "splice-checksum")
        .map(_.value())
        .getOrElse(throw new RuntimeException("Missing checksum tag"))
    } yield checksum
  }

  def newAppendWriteObject(
      key: String
  )(implicit ec: ExecutionContext): AppendWriteObject = new AppendWriteObject(key)

  // If the result (aggregated over S3 pagination) exceeds `limit` entries, will throw an exception.
  // It is the caller's responsibility to use narrow enough prefixes (or large enough limits) to avoid that.
  // The reason we want to assert on it as that we do not want to rely on all S3 providers to return results
  // with the same pagination (i.e. sorted in the same order), so we cannot rely on partial responses.
  def listObjects(
      prefix: Option[String] = None,
      limit: HardLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
  )(implicit ec: ExecutionContext): Future[Seq[S3Object]] = {

    def recFetch(token: Option[String], acc: Seq[S3Object]): Future[Seq[S3Object]] =
      for {
        response <- s3Client
          .listObjectsV2(
            ListObjectsV2Request
              .builder()
              .bucket(bucketName)
              .prefix(prefix.getOrElse(""))
              .continuationToken(token.getOrElse(""))
              .build()
          )
          .asScala
        withResponse = acc ++ response.contents().asScala
        _ = if (withResponse.size >= limit.limit) {
          io.grpc.Status.INVALID_ARGUMENT.withDescription(s"S3 list had more than ${limit.limit} objects").asRuntimeException()
        }
        res <-
          if (response.isTruncated) {
            recFetch(Some(response.nextContinuationToken()), withResponse)
          } else {
            Future.successful(withResponse)
          }
      } yield {
        res
      }

    recFetch(None, Seq.empty).map(_.sortBy(_.key))
  }

  def listObjectsWithChecksums(
      prefix: Option[String] = None,
      limit: HardLimit = HardLimit.tryCreate(Limit.DefaultMaxPageSize),
  )(implicit ec: ExecutionContext): Future[Seq[S3BucketConnection.ObjectKeyAndChecksum]] = {
    for {
      objects <- listObjects(prefix, limit)
      // TODO: limit parallelism of the checksum reading.
      //  Something like: Future.traverse(objects.grouped(10)) { batch => Future.sequence(batch.map(object => readChecksum(object))) } ;
      keysWithChecksums <- Future.sequence(objects.map { obj =>
        readChecksum(obj.key).map(S3BucketConnection.ObjectKeyAndChecksum(obj.key, _))
      })
    } yield {
      keysWithChecksums
    }
  }

  def readObject(key: String)(implicit ec: ExecutionContext): Source[ByteBuffer, NotUsed] = {
    // Use lazySource, so the API calls start only once the stream is actually materialized
    Source.lazySource { () =>
      val request = GetObjectRequest.builder().bucket(bucketName).key(key).build()

      Source.futureSource {
        s3Client.getObject(request, AsyncResponseTransformer.toPublisher[GetObjectResponse]())
          .asScala
          .map { publisher =>
            org.apache.pekko.stream.scaladsl.Source.fromPublisher(publisher)
          }
      }
    }.mapMaterializedValue(_ => NotUsed)
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

        taggingRequest = PutObjectTaggingRequest
          .builder()
          .bucket(bucketName)
          .key(key)
          .tagging(
            Tagging
              .builder()
              .tagSet(
                Tag
                  .builder()
                  .key("splice-checksum")
                  .value(Base64.getEncoder.encodeToString(md.digest()))
                  .build()
              )
              .build()
          )
          .build()

        _ <- s3Client.putObjectTagging(taggingRequest).asScala

      } yield {
        ()
      }
    }
  }
}

object S3BucketConnection {

  case class ObjectKeyAndChecksum(
      key: String,
      checksum: String,
  )

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
