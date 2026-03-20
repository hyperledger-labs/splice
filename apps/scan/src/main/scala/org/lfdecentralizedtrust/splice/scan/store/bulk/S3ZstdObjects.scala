// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageConfig, ScanStorageConfig}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import scala.concurrent.ExecutionContext

/** Pekko source for compressing data and dumping it to S3 objects.
  * The data is compressed into chunks of size >=config.bulkZstdFrameSize. Each chunk is a frame
  * in zstd terms (i.e. a complete zstd object). The chunks are written into
  * s3 objects of size >=config.bulkMaxFileSize (as multi-frame zstd objects, which
  * are simply a concatenation of zstd objects), using multi-part upload (where
  * each chunk/frame is a part in the upload).
  * Whenever an S3 object is fully written, the flow emits an Output object
  * with the name of the object just written (useful for monitoring
  * progress and testing), and a flag of whether this is the last object and the source has been
  * completed (useful when streaming a sequence of segments or ACS snapshots, so that we can easily
  * know when each segment/snapshot is complete).
  */

class S3ZstdObjects(
    storageConfig: ScanStorageConfig,
    appConfig: BulkStorageConfig,
    s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext, ec: ExecutionContext)
    extends NamedLogging {

  private def getFlow(
      getObjectKey: Int => String
  ): Flow[ByteString, String, NotUsed] =
    Flow[ByteString]
      .via(ZstdGroupedWeight(appConfig.zstdCompressionLevel, storageConfig.bulkZstdFrameSize))
      .via(
        GroupedWeightS3ObjectFlow(
          s3Connection,
          getObjectKey,
          storageConfig.bulkMaxFileSize,
          appConfig.maxParallelPartUploads,
          loggerFactory,
        )
      )
}

object S3ZstdObjects {
  def apply(
      config: ScanStorageConfig,
      appConfig: BulkStorageConfig,
      s3Connection: S3BucketConnection,
      getObjectKey: Int => String,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Flow[ByteString, String, NotUsed] =
    new S3ZstdObjects(config, appConfig, s3Connection, loggerFactory).getFlow(getObjectKey)
}
