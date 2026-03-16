// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.http.v0.scanStream as v0
import org.lfdecentralizedtrust.splice.http.v0.scanStream.ScanStreamResource
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import scala.concurrent.{ExecutionContextExecutor, Future}
import io.opentelemetry.api.trace.Tracer

class HttpScanStreamHandler(
    s3Connection: Option[S3BucketConnection]
)(implicit ec: ExecutionContextExecutor, protected val tracer: Tracer)
    extends v0.ScanStreamHandler[TraceContext]
    with Spanning {

  protected val workflowId: String = this.getClass.getSimpleName

  override def bulkStorageDownload(respond: ScanStreamResource.BulkStorageDownloadResponse.type)(
      objectKey: String
  )(extracted: TraceContext): Future[ScanStreamResource.BulkStorageDownloadResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.bulkStorageDownload") { _ => _ =>
      s3Connection.fold(
        throw new UnsupportedOperationException(
          "s3 bucket connection not configured on this instance of Scan"
        )
      )(
        _.readObject(objectKey)
          .map(source =>
            ScanStreamResource.BulkStorageDownloadResponse
              .OK(HttpEntity(ContentTypes.`application/octet-stream`, source))
          )
          .transform(HttpErrorHandler.onGrpcNotFound(s"Object $objectKey not found"))
      )
    }
  }

}
