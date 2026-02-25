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
case class ZstdGroupedWeight(minSize: Long, override val loggerFactory: NamedLoggerFactory)
    extends GraphStage[FlowShape[ByteString, ByteStringWithTermination]] with NamedLogging
    {
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
      logger.debug(s"compressing ${input.size} bytes")(TraceContext.empty)
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
      logger.debug("zstdFinish")(TraceContext.empty)
      compressingStream.close()
      tmpNioBuffer.flip()
      val result = ByteString.fromByteBuffer(tmpNioBuffer)
      tmpNioBuffer.clear()
      result
    }

    override def close(): Unit = {
      logger.debug("close")(TraceContext.empty)
      compressingStream.close()
      val _ = tmpBuffer.release()
    }
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val zstd = new AtomicReference[ZSTD](new ZSTD(3))
      private val state: AtomicReference[State] = new AtomicReference[State](State.empty())

      override def postStop(): Unit = {
        super.postStop()
        if (zstd.get() != null) {
          zstd.get().close()
        }
      }

      private def reset(): Unit = {
        zstd.get().close()
        zstd.set(new ZSTD(3))
        state.set(State.empty())
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        logger.debug(s"Received ${elem.size} bytes")(TraceContext.empty)
        val compressed = zstd.get().compress(elem)
        state.set(state.get().append(compressed))
        if (state.get().left <= 0) {
          state.set(state.get().append(zstd.get().zstdFinish()))
          push(out, ByteStringWithTermination(state.get().bytes, false))
          reset()
        } else {
          pull(in)
        }
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        logger.debug("upstream finished")(TraceContext.empty)
        if (state.get().bytes.nonEmpty) {
          logger.debug("non empty state, finishing it")(TraceContext.empty)
          state.set(state.get().append(zstd.get().zstdFinish()))
          push(out, ByteStringWithTermination(state.get().bytes, true))
        }
        logger.debug("completing stage")(TraceContext.empty)
        completeStage()
      }

      setHandlers(in, out, this)
    }

}
