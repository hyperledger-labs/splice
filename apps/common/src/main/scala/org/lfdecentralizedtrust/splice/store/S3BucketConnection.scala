// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import com.digitalasset.canton.util.FutureUtil
import org.lfdecentralizedtrust.splice.config.S3Config
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
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
) extends NamedLogging
    with LimitHelpers {

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

  /*
    Iterates through all pages of an S3 listObjectsV2 request, applying the provided function to
    extract the relevant names from each page (e.g. object keys, or folder prefixes), and returns the
    top `limit` names sorted alphabetically. The function is used for both listing objects and listing folders.
    It is safe to use on long lists, memory-wise, as it always keeps in memory only the top `limit` names seen so far.
   */
  private def firstNamesFromS3List(
      listRequest: ListObjectsV2Request,
      namesFromPage: ListObjectsV2Response => Iterable[String],
      limit: Limit,
  )(implicit tc: TraceContext, as: ActorSystem): Future[Seq[String]] = {
    Source
      .fromPublisher(
        s3Client.listObjectsV2Paginator(listRequest)
      )
      .fold(Seq.empty[String])((acc, page) => {
        val content = namesFromPage(page)
        applyLimit(
          "listObjects",
          limit,
          (acc ++ content).sorted,
        )
      })
      .mapConcat(_.toList)
      .runWith(Sink.seq[String])
  }

  def listObjects(
      prefix: String,
      keyFilter: String => Boolean,
      limit: Limit,
  )(implicit tc: TraceContext, as: ActorSystem): Future[Seq[String]] =
    firstNamesFromS3List(
      ListObjectsV2Request
        .builder()
        .bucket(bucketName)
        .prefix(prefix)
        .maxKeys(limit.limit)
        .build(),
      _.contents().asScala.map(_.key()).filter(keyFilter),
      limit,
    )

  def listFolders(
      folderFilter: String => Boolean,
      limit: Limit,
  )(implicit tc: TraceContext, as: ActorSystem): Future[Seq[String]] =
    firstNamesFromS3List(
      ListObjectsV2Request
        .builder()
        .bucket(bucketName)
        .delimiter("/")
        .maxKeys(limit.limit)
        .build(),
      _.commonPrefixes().asScala.map(_.prefix()).filter(folderFilter),
      limit,
    )

  def getChecksums(
      objectKeys: Seq[String]
  )(implicit
      ec: ExecutionContext,
      as: ActorSystem,
      tc: TraceContext,
  ): Future[Seq[ObjectKeyAndChecksum]] = {
    Source(objectKeys.toList)
      .mapAsync(4) { key => // TODO(#3429): make this parallelism configurable
        readChecksum(key)
          .map(checksum => checksum.map(ObjectKeyAndChecksum(key, _)))
      }
      .collect { case Some(obj) => obj }
      .runWith(Sink.seq[ObjectKeyAndChecksum])
  }

  private def readChecksum(
      key: String
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[Option[String]] = {
    val headRequest = HeadObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()
    for {
      head <- s3Client.headObject(headRequest).asScala.map(Some(_)).recover { case e =>
        // TODO(#3429): distinguish between "object not found" and other errors, probably want to catch only NoSuchKeyException, and throw everything else
        logger
          .debug(s"Failed to read checksum for object $key, object may not exist: ${e.getMessage}")
        None
      }
      checksum = head.map(
        _.metadata().asScala
          .getOrElse("splice-checksum", throw new RuntimeException("Missing checksum metadata"))
      )
    } yield checksum
  }

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

  def doesObjectExist(
      key: String
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    val request = HeadObjectRequest.builder().bucket(bucketName).key(key).build()
    FutureUtil
      .unwrapCompletionException(
        s3Client.headObject(request).asScala
      )
      .map(_ => true)
      .recover { case _: NoSuchKeyException =>
        false
      }
  }

  // Should be called on the bucket connection of the destination bucket. Assumes that that service account has permissions to read the source object.
  def copyObject(sourceBucket: String, key: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    logger.trace(
      s"Copying object $key from bucket $sourceBucket to bucket $bucketName"
    )
    val copyReq = CopyObjectRequest
      .builder()
      .destinationBucket(bucketName)
      .destinationKey(key)
      .sourceBucket(sourceBucket)
      .sourceKey(key)
      .build()

    s3Client.copyObject(copyReq).asScala.map(_ => ())
  }

  def deleteObject(key: String)(implicit ec: ExecutionContext): Future[Unit] = {
    val request = DeleteObjectRequest.builder().bucket(bucketName).key(key).build()
    s3Client.deleteObject(request).asScala.map(_ => ())
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

    /** The checksum of the whole object. Computing it via a `lazy val` to support idempotent `finish()` calls.
      */
    private lazy val objectChecksum: String = Base64.getEncoder.encodeToString(md.digest())

    /** `lazy val` to ensure that multi-part upload is completed at most once.
      */
    private lazy val finishResult: Future[Unit] = doFinish()

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

    /** Completes the multi-part upload and stores the object checksum in the object's metadata.
      * Idempotent, safe to call more than once (will just return the Future from the first call again).
      */
    def finish(): Future[Unit] = finishResult

    /** Aborts the multi-part upload, discarding all uploaded parts. The object never becomes visible.
      */
    def abort(): Future[Unit] =
      for {
        id <- uploadId
        _ <- s3Client
          .abortMultipartUpload(
            AbortMultipartUploadRequest
              .builder()
              .bucket(bucketName)
              .key(key)
              .uploadId(id)
              .build()
          )
          .asScala
      } yield ()

    private def doFinish(): Future[Unit] = {
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
        metadata = Map("splice-checksum" -> objectChecksum)

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

  case class ObjectKeyAndChecksum(
      key: String,
      checksum: String,
  )

}
