package com.daml.network.util

import com.daml.network.config.{GcpBucketConfig}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

import com.google.cloud.storage.{BlobId, BlobInfo, Storage, StorageOptions}

class GcpBucket(config: GcpBucketConfig, override val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {
  private val credentials = config.credentials.credentials

  private val storage: Storage = StorageOptions
    .newBuilder()
    .setProjectId(config.projectId)
    .setCredentials(credentials)
    .build()
    .getService()

  def dumpBytesToBucket(data: Array[Byte], fileName: String)(implicit
      traceContext: TraceContext
  ): Unit = {
    val blobId = BlobId.of(config.bucketName, fileName)
    val blobInfo = BlobInfo.newBuilder(blobId).build()
    storage.create(blobInfo, data)
    logger.info(s"Bytes dumped to GCP bucket: gs://${config.bucketName}/$fileName")
  }

  def readBytesFromBucket(fileName: String): Array[Byte] = {
    val blobId = BlobId.of(config.bucketName, fileName)
    val blob = storage.get(blobId)
    blob.getContent()
  }
}
