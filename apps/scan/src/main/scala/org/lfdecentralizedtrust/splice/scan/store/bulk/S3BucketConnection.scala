// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.{AsyncRequestBody, AsyncResponseTransformer}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}
import software.amazon.awssdk.services.s3.model.{
  GetObjectRequest,
  GetObjectResponse,
  PutObjectRequest,
}

import scala.jdk.FutureConverters.*
import java.net.URI
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}

case class S3Config(
    endpoint: URI,
    bucketName: String,
    region: Region,
    credentials: AwsBasicCredentials,
)

class S3BucketConnection(
    val s3Client: S3AsyncClient,
    val bucketName: String,
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
}

object S3BucketConnection {
  def apply(
      s3Config: S3Config,
      bucketName: String,
      loggerFactory: NamedLoggerFactory,
  ): S3BucketConnection = {
    new S3BucketConnection(
      S3AsyncClient
        .builder()
        .endpointOverride(s3Config.endpoint)
        .region(s3Config.region)
        .credentialsProvider(StaticCredentialsProvider.create(s3Config.credentials))
        // TODO(#3429): mockS3 and GCS support only path style access. Do we need to make this configurable?
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build(),
      bucketName,
      loggerFactory,
    )
  }
}
