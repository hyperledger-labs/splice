// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.{S3Client, S3Configuration}

import java.net.URI
import java.nio.ByteBuffer

case class S3Config(
    endpoint: URI,
    bucketName: String,
    region: Region,
    credentials: AwsBasicCredentials,
)

class S3BucketConnection(
    val s3Client: S3Client,
    val bucketName: String,
    val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  // Reads the full content of an s3 object into a ByteBuffer.
  // Use only for testing, when the object size is known to be small
  def readFullObject(key: String): ByteBuffer = {
    val obj = s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build())
    val bytes = obj.readAllBytes()
    val ret = ByteBuffer.allocateDirect(bytes.length)
    ret.put(bytes)
  }

  // Writes a full object from memory into an s3 object
  def writeFullObject(key: String, content: ByteBuffer)(implicit tc: TraceContext) = {
    logger.debug(s"Writing ${content.array().length} bytes to S3 object $key")
    val putObj: PutObjectRequest = PutObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()
    s3Client.putObject(
      putObj,
      RequestBody.fromBytes(content.array()),
    )
  }
}

object S3BucketConnection {
  def apply(s3Config: S3Config, bucketName: String, loggerFactory: NamedLoggerFactory) = {
    new S3BucketConnection(
      S3Client
        .builder()
        .endpointOverride(s3Config.endpoint)
        .region(s3Config.region)
        .credentialsProvider(StaticCredentialsProvider.create(s3Config.credentials))
        // TODO: mockS3 supports only path style access. Do we need to make this configurable?
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build(),
      bucketName,
      loggerFactory,
    )
  }
}
