// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.util.ByteString

/** A Pekko Flow GraphStage that takes a stream of bytestrings, slices them into objects such that every object is slightly
  * larger than maxObjectSize (i.e. the cut is at the end of the byteString that passes that threshold), and uploads them
  * to an S3 compatible bucket. Multiple input ByteStrings may be uploaded in parallel using multi-part upload, up to `maxParallelPartUploads`
  * in parallel. Whenever an object is finished, this GraphStage emits the key of that object, and a flag of whether this is
  * the last object before closing the stream.
  * On upstream errors, any partially-uploaded object is discarded.
  */

case class GroupedWeightS3ObjectFlow(
    s3Connection: S3BucketConnection,
    getObjectKey: Int => String,
    maxObjectSize: Long,
    maxParallelPartUploads: Int,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends GraphStage[FlowShape[ByteString, String]]
    with NamedLogging {

  val in = Inlet[ByteString]("GroupedWeightS3Object.in")
  val out = Outlet[String]("GroupedWeightS3Object.out")
  override val shape: FlowShape[ByteString, String] = FlowShape(in, out)

  override def initialAttributes: Attributes = Attributes.name("GroupedWeightS3Object")

  private case class State(
      nextObjectIndex: Int,
      currentObject: s3Connection.AppendWriteObject,
      currentObjectSize: Long, // includes all ongoing pending part uploads
      numPendingPartUploads: Int,
  ) {

    def addPart(size: Int) = State(
      nextObjectIndex,
      currentObject,
      currentObjectSize + size,
      numPendingPartUploads + 1,
    )

    def completePart() = State(
      nextObjectIndex,
      currentObject,
      currentObjectSize,
      numPendingPartUploads - 1,
    )

    def nextObject()(implicit ec: ExecutionContext) = State(
      nextObjectIndex + 1,
      s3Connection.newAppendWriteObject(getObjectKey(nextObjectIndex)),
      0,
      0,
    )

  }

  private object State {
    def initial() = State(
      1,
      s3Connection.newAppendWriteObject(getObjectKey(0)),
      0,
      0,
    )
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {

    new GraphStageLogic(shape) with InHandler with OutHandler {
      // The usage of callbacks makes this thread safe, so we use vars here and not Atomic's
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      @volatile
      private var state = State.initial()

      private def objectDone = state.currentObjectSize >= maxObjectSize || isClosed(in)

      private val uploadCallback = getAsyncCallback[Unit] { _ =>
        state = state.completePart()
        logger.debug(
          s"Part upload completed, waiting for ${state.numPendingPartUploads} more"
        )
        if (state.numPendingPartUploads == maxParallelPartUploads - 1) {
          if (!objectDone) {
            logger.trace(
              "More parallel upload capacity freed up, and we're not done yet, pull more input"
            )
            pull(in)
          }
        }
        if (state.numPendingPartUploads == 0) {
          if (objectDone) {
            logger.trace(
              s"Finishing upload of ${state.currentObject.key}"
            )
            finishCurrentObject()
          }
        }

      }

      private val finishCallback = getAsyncCallback[Unit] { _ =>
        logger.debug(s"Finished uploading and finalizing object ${state.currentObject.key}")
        push(out, state.currentObject.key)
        if (isClosed(in)) {
          logger.trace("Upstream completed, completing too.")
          completeStage()
        } else {
          state = state.nextObject()
          logger.trace(s"Ready for object ${state.currentObject.key}")
        }
      }

      private val failCallback = getAsyncCallback[Throwable] { ex =>
        failStage(ex)
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        val curState = state
        state = curState.addPart(elem.length)
        val partNumber = curState.currentObject.prepareUploadNext(elem.asByteBuffer)
        logger.debug(
          s"Received ${elem.length} bytes. Uploading as part $partNumber of object ${curState.currentObject.key}"
        )
        curState.currentObject.upload(partNumber, elem.asByteBuffer).onComplete {
          case Success(_) => uploadCallback.invoke(())
          case Failure(ex) => failCallback.invoke(ex)
        }
        if (objectDone) {
          logger.trace(
            s"New object size for ${curState.currentObject.key} is ${curState.currentObjectSize + elem.length}, done with this object"
          )
        } else {
          logger.trace(
            s"New object size for ${curState.currentObject.key} is ${curState.currentObjectSize + elem.length}, not done with it yet"
          )
          if (state.numPendingPartUploads < maxParallelPartUploads) {
            pull(in)
          }
        }
      }

      override def onPull(): Unit = {
        pull(in)
      }

      private def finishCurrentObject(): Unit =
        state.currentObject.finish().onComplete {
          case Success(_) => finishCallback.invoke(())
          case Failure(ex) => failCallback.invoke(ex)
        }

      override def onUpstreamFinish(): Unit = {
        if (state.numPendingPartUploads == 0) {
          if (state.currentObjectSize > 0) {
            logger.debug(
              s"Upstream finished, finishing current object ${state.currentObject.key} with size ${state.currentObjectSize}"
            )
            finishCurrentObject()
          } else {
            logger.debug(
              "Upstream finished, no pending uploads and current object is empty, completing stage"
            )
            completeStage()
          }
        } else {
          logger.debug(
            s"Upstream finished, waiting for ${state.numPendingPartUploads} pending uploads to finish before completing stage"
          )
        }
      }

      setHandlers(in, out, this)
    }
  }
}
