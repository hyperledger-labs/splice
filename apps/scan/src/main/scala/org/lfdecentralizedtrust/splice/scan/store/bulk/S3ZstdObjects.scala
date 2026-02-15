package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfig

import scala.concurrent.{ExecutionContext, Future}

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
    s3Connection: S3BucketConnection,
    override val loggerFactory: NamedLoggerFactory
)(implicit tc: TraceContext, ec: ExecutionContext) extends NamedLogging {

  private def getFlow(
      config: ScanStorageConfig,
      getObjectKey: Int => String,
  ): Flow[ByteString, S3ZstdObjects.Output, NotUsed] =
    Flow[ByteString]
      .via(ZstdGroupedWeight(config.bulkZstdFrameSize))
      .statefulMap(() =>
        State(
          s3Connection.newAppendWriteObject(
            getObjectKey(0)
          ),
          0,
          0,
        )
      )(
        {
          case (state, chunk) if state.s3ObjSize + chunk.bytes.length > config.bulkMaxFileSize =>
            logger.debug(
              s"Adding a chunk of ${chunk.bytes.length} bytes. The object size so far has been: ${state.s3ObjSize}, together they cross the threshold of ${config.bulkMaxFileSize}, so this is the last chunk for the object"
            )
            (
              State(
                s3Connection.newAppendWriteObject(
                  getObjectKey(state.s3ObjIdx + 1)
                ),
                state.s3ObjIdx + 1,
                0,
              ),
              ObjectChunk(
                chunk.bytes,
                state.obj,
                true,
                chunk.isLast,
                state.obj.prepareUploadNext(),
              ),
            )
          case (state, chunk) =>
            logger.debug(
              s"Adding a chunk of ${chunk.bytes.length} bytes. The object size so far has been: ${state.s3ObjSize}, together they are not yet at the threshold of ${config.bulkMaxFileSize}"
            )
            (
              State(
                state.obj,
                state.s3ObjIdx,
                state.s3ObjSize + chunk.bytes.length,
              ),
              ObjectChunk(
                chunk.bytes,
                state.obj,
                false,
                chunk.isLast,
                state.obj.prepareUploadNext(),
              ),
            )
        },
        onComplete = state => {
          Some(
            ObjectChunk(ByteString.empty, state.obj, true, true, -1)
          )
        },
      )
      .mapAsync(4) {  // TODO(#3429): make the parallelism (4) configurable
        case chunk: ObjectChunk if chunk.partNumber >= 0 =>
          logger.debug(
            s"Uploading a chunk of size ${chunk.bytes.toArrayUnsafe().length} as partNumber ${chunk.partNumber} of ${chunk.obj.key}"
          )
          chunk.obj.upload(chunk.partNumber, chunk.bytes.asByteBuffer).map(_ => chunk)
        case chunk => Future.successful(chunk)
      }
      .mapAsync(1) {
        case chunk: ObjectChunk if chunk.isLastChunkInObject =>
          logger.debug(
            s"Finished uploading part ${chunk.partNumber}, which is the last one for the object ${chunk.obj.key}, finishing the upload"
          )
          chunk.obj
            .finish()
            .map(_ =>
              Some(
                S3ZstdObjects
                  .Output(chunk.obj.key, chunk.isLastObject)
              )
            )
        case chunk => {
          logger.debug(s"Finished uploading part ${chunk.partNumber} to object ${chunk.obj.key}")
          Future.successful(None)
        }
      }
      .collect { case Some(out) => out }

  private case class State(
      obj: s3Connection.AppendWriteObject,
      s3ObjIdx: Int,
      s3ObjSize: Int,
  )
  private case class ObjectChunk(
      bytes: ByteString,
      obj: s3Connection.AppendWriteObject,
      isLastChunkInObject: Boolean,
      isLastObject: Boolean,
      partNumber: Int,
  )

}

object S3ZstdObjects {
  case class Output(
      objectKey: String,
      isLastObject: Boolean,
  )

  def apply(
      config: ScanStorageConfig,
      s3Connection: S3BucketConnection,
      getObjectKey: Int => String,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): Flow[ByteString, Output, NotUsed] =
    new S3ZstdObjects(s3Connection, loggerFactory).getFlow(config, getObjectKey)
}
