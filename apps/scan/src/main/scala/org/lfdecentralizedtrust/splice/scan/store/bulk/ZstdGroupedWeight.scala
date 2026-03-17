// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer
import io.grpc.netty.shaded.io.netty.buffer.PooledByteBufAllocator
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.util.concurrent.atomic.AtomicReference

case class ByteStringWithTermination(
    bytes: ByteString,
    isLast: Boolean,
)

/** A Pekko GraphStage that zstd-compresses a stream of bytestrings, and splits the output into zstd objects of size (minWeight + delta).
  * Somewhat similar to Pekko's built-in GroupedWeight, but outputs valid zstd compressed objects.
  */
case class ZstdGroupedWeight(
    compressionLevel: Int,
    minSize: Long,
    loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext)
    extends GraphStage[FlowShape[ByteString, ByteStringWithTermination]]
    with NamedLogging {
  require(minSize > 0, "minSize must be greater than 0")

  val zstdTmpBufferSize = 10 * 1024 * 1024; // TODO(#3429): make configurable?

  val in = Inlet[ByteString]("ZstdGroupedWeight.in")
  val out = Outlet[ByteStringWithTermination]("ZstdGroupedWeight.out")
  override val shape: FlowShape[ByteString, ByteStringWithTermination] = FlowShape(in, out)

  override def initialAttributes: Attributes = Attributes.name("ZstdGroupedWeight")

  private case class State(
      bytes: ByteString,
      left: Long,
  ) {
    def append(bs: ByteString): State =
      State(
        bytes ++ bs,
        left - bs.size,
      )

  }
  private object State {
    def empty(): State = State(ByteString.empty, minSize)
  }

  class ZSTD(
      val compressionLevel: Int = 3
  ) extends AutoCloseable {

    val bufferAllocator = PooledByteBufAllocator.DEFAULT
    val tmpBuffer = bufferAllocator.directBuffer(zstdTmpBufferSize)
    val tmpNioBuffer = tmpBuffer.nioBuffer(0, tmpBuffer.capacity())
    val compressingStream =
      new ZstdDirectBufferCompressingStreamNoFinalizer(tmpNioBuffer, compressionLevel)

    def compress(input: ByteString): ByteString = {
      val inputBB = bufferAllocator.directBuffer(input.size)
      inputBB.writeBytes(input.toArrayUnsafe())
      compressingStream.compress(inputBB.nioBuffer())
      inputBB.release()
      compressingStream.flush()
      tmpNioBuffer.flip()
      val result = ByteString.fromByteBuffer(tmpNioBuffer)
      tmpNioBuffer.clear()
      result
    }

    def zstdFinish(): ByteString = {
      compressingStream.close()
      tmpNioBuffer.flip()
      val result = ByteString.fromByteBuffer(tmpNioBuffer)
      tmpNioBuffer.clear()
      result
    }

    override def close(): Unit = {
      compressingStream.close()
      val _ = tmpBuffer.release()
    }
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val zstd = new AtomicReference[ZSTD](new ZSTD(compressionLevel))
      private val state: AtomicReference[State] = new AtomicReference[State](State.empty())
      // Tracks whether any data has been compressed into the current zstd frame
      // since the last reset (i.e., since the last complete chunk was pushed).
      // This is needed to distinguish "upstream finished with pending compressed data"
      // from "upstream finished right after we just flushed a complete chunk".
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      private var hasPendingData: Boolean = false

      override def postStop(): Unit = {
        super.postStop()
        if (zstd.get() != null) {
          zstd.get().close()
        }
      }

      private def reset(): Unit = {
        zstd.get().close()
        zstd.set(new ZSTD(compressionLevel))
        state.set(State.empty())
        hasPendingData = false
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        val compressed = zstd.get().compress(elem)
        state.set(state.get().append(compressed))
        hasPendingData = true
        logger.debug(
          s"Received input of size ${elem.size}, compressed to ${compressed.size}, current state size is ${state.get().bytes.size}, left is ${state.get().left}"
        )
        if (state.get().left <= 0) {
          state.set(state.get().append(zstd.get().zstdFinish()))
          logger.debug(
            s"Emitting output of size ${state.get().bytes.size} (isLast=false), resetting state"
          )
          push(out, ByteStringWithTermination(state.get().bytes, false))
          reset()
        } else {
          pull(in)
        }
      }

      override def onPull(): Unit = {
        if (isClosed(in)) {
          logger.debug(
            s"Upstream is closed in onPull(), emitting remaining data (${state.get().bytes.length} bytes) and completing"
          )
          push(out, ByteStringWithTermination(state.get().bytes, true))
          completeStage()
        } else {
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit = {
        logger.debug(s"Upstream finished, hasPendingData=$hasPendingData")
        if (hasPendingData) {
          // There is compressed data in the current zstd frame that hasn't been
          // pushed yet. Finalize the compressor and try to push.
          state.set(state.get().append(zstd.get().zstdFinish()))
          if (isAvailable(out)) {
            logger.debug(
              s"Emitting final output of size ${state.get().bytes.size} (isLast=true) and completing stage"
            )
            push(out, ByteStringWithTermination(state.get().bytes, true))
            completeStage()
          } else {
            logger.debug(
              s"Output is not available, deferring final output of size ${state.get().bytes.size} (isLast=true) to onPull"
            )
            // Don't completeStage() here — onPull will see isClosed(in), push the
            // buffered data with isLast=true, and then complete.
          }
        } else {
          // No data has been compressed since the last chunk was pushed (or ever).
          // Nothing to emit — just complete the stage.
          logger.debug("No pending data, completing stage")
          completeStage()
        }
      }

      setHandlers(in, out, this)
    }

}
