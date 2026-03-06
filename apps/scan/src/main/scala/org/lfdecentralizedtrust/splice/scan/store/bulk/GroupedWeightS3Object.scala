// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.lfdecentralizedtrust.splice.store.S3BucketConnection

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import GroupedWeightS3Object.Output
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext

case class GroupedWeightS3Object(
    s3Connection: S3BucketConnection,
    getObjectKey: Int => String,
    maxObjectSize: Long,
    maxParallelPartUploads: Int,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tc: TraceContext)
    extends GraphStage[FlowShape[ByteStringWithTermination, Output]]
    with NamedLogging {

  val in = Inlet[ByteStringWithTermination]("GroupedWeightS3Object.in")
  val out = Outlet[Output]("GroupedWeightS3Object.out")
  override val shape: FlowShape[ByteStringWithTermination, Output] = FlowShape(in, out)

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
    def initial() = State(1, s3Connection.newAppendWriteObject(getObjectKey(0)), 0, 0)
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {

    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val state: AtomicReference[State] = new AtomicReference[State](State.initial())
      private val upstreamFinished: AtomicBoolean = new AtomicBoolean(false)

      private val uploadCallback = getAsyncCallback[Unit] { _ =>
        state.set(state.get().completePart())
        logger.debug(
          s"Part upload completed, waiting for ${state.get().numPendingPartUploads} more"
        )
        if (state.get().numPendingPartUploads == 0) {
          if (upstreamFinished.get() || state.get().currentObjectSize > maxObjectSize) {
            logger.debug(
              s"This part was last in the stream, or the object reached its size cutoff, finishing upload of ${state.get().currentObject.key}"
            )
            finishCurrentObject()
          }
        }

      }

      private val finishCallback = getAsyncCallback[Unit] { _ =>
        logger.debug(s"Finished uploading and finalizing object ${state.get().currentObject.key}")
        push(
          out,
          Output(
            objectKey = state.get().currentObject.key,
            isLastObject = upstreamFinished.get(),
          ),
        )
        if (upstreamFinished.get()) {
          logger.debug("This was the last input in the stream, completing.")
          completeStage()
        } else {
          state.set(state.get().nextObject())
          logger.debug(s"Ready for object ${state.get().currentObject.key}")
        }
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        val curState = state.get()
        val partNumber = curState.currentObject.prepareUploadNext()
        state.set(curState.addPart(elem.bytes.length))
        if (elem.isLast) {
          upstreamFinished.set(true)
        }
        logger.debug(
          s"Received ${elem.bytes.length} bytes (isLast=${elem.isLast}, uploading as part $partNumber of object ${curState.currentObject.key}"
        )
        curState.currentObject.upload(partNumber, elem.bytes.asByteBuffer).onComplete {
          case Success(_) => uploadCallback.invoke(())
          case Failure(ex) => failStage(ex)
        }
        if (!elem.isLast && curState.currentObjectSize + elem.bytes.length <= maxObjectSize) {
          logger.debug(
            s"New object size for ${curState.currentObject.key} is ${curState.currentObjectSize + elem.bytes.length}, less than the size cutoff"
          )
          // TODO: limit the parallelism
          pull(in)
        } else {
          logger.debug(
            s"New object size for ${curState.currentObject.key} is ${curState.currentObjectSize + elem.bytes.length}, reached the size cutoff"
          )
        }
      }

      override def onPull(): Unit = {
        pull(in)
      }

      private def finishCurrentObject(): Unit =
        state.get().currentObject.finish().onComplete {
          case Success(_) => finishCallback.invoke(())
          case Failure(ex) => failStage(ex)
        }

      override def onUpstreamFinish(): Unit = {
        if (!upstreamFinished.get()) {
          logger.warn("upstream finished unexpectedly, without omitting a final object first")
        }
        // Note that we're not finishing the stage here, we're doing that above when the last object finishes uploading
      }

      setHandlers(in, out, this)
    }
  }
}

object GroupedWeightS3Object {
  case class Output(
      objectKey: String,
      isLastObject: Boolean,
  )

}
