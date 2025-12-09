// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.config.GcpBucketConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.google.cloud.storage.{Blob, BlobId, BlobInfo, Storage, StorageOptions}

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

class GcpBucket(config: GcpBucketConfig, override val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {
  private val credentials = config.credentials.credentials

  private val storage: Storage = StorageOptions
    .newBuilder()
    .setProjectId(config.projectId)
    .setCredentials(credentials)
    .build()
    .getService()

  def dumpStringToBucket(data: String, fileName: Path)(implicit
      traceContext: TraceContext
  ): Unit =
    dumpBytesToBucket(data.getBytes(StandardCharsets.UTF_8), fileName.toString)

  def readStringFromBucket(fileName: Path): String =
    new String(readBytesFromBucket(fileName.toString), StandardCharsets.UTF_8)

  def dumpBytesToBucket(data: Array[Byte], fileName: String)(implicit
      traceContext: TraceContext
  ): Unit = {
    val blobId = BlobId.of(config.bucketName, fileName)
    val blobInfo = BlobInfo.newBuilder(blobId).build()
    storage.create(blobInfo, data)
    logger.info(s"Bytes dumped to GCP bucket: gs://${config.bucketName}/$fileName")
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  def listBlobsByOffset(startOffset: String, endOffset: String): Seq[Blob] = {
    val blobs = Seq.newBuilder[Blob]
    var page = storage.list(
      config.bucketName,
      Storage.BlobListOption.startOffset(startOffset),
      Storage.BlobListOption.endOffset(endOffset),
    )
    blobs ++= page.getValues().asScala
    while (page.hasNextPage) {
      page = page.getNextPage
      blobs ++= page.getValues().asScala
    }
    blobs.result()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  def listBlobsByPrefix(prefix: String): Seq[Blob] = {
    val blobs = Seq.newBuilder[Blob]
    var page = storage.list(
      config.bucketName,
      Storage.BlobListOption.prefix(prefix),
    )
    blobs ++= page.getValues().asScala
    while (page.hasNextPage) {
      page = page.getNextPage
      blobs ++= page.getValues().asScala
    }
    blobs.result()
  }

  def readBytesFromBucket(fileName: String): Array[Byte] = {
    val blobId = BlobId.of(config.bucketName, fileName)
    val blob = storage.get(blobId)
    blob.getContent()
  }

  def fileExists(fileName: String): Boolean = {
    val blobId = BlobId.of(config.bucketName, fileName)
    storage.get(blobId) match {
      case blob: Blob => blob.exists()
      case _ => false
    }
  }
}
