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

/** A Pekko GraphStage that zstd-compresses a stream of bytestrings, and splits the output into zstd objects of size (minWeight + delta).
  * Somewhat similar to Pekko's built-in GroupedWeight, but outputs valid zstd compressed objects.
  */
case class ZstdGroupedWeight(
    compressionLevel: Int,
    minSize: Long,
    loggerFactory: NamedLoggerFactory,
)(implicit tc: TraceContext)
    extends GraphStage[FlowShape[ByteString, ByteString]]
    with NamedLogging {
  require(minSize > 0, "minSize must be greater than 0")

  val zstdTmpBufferSize = 10 * 1024 * 1024; // TODO(#3429): make configurable?

  val in = Inlet[ByteString]("ZstdGroupedWeight.in")
  val out = Outlet[ByteString]("ZstdGroupedWeight.out")
  override val shape: FlowShape[ByteString, ByteString] = FlowShape(in, out)

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
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      private var zstd = new ZSTD(compressionLevel)
      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      private var state = State.empty()

      override def postStop(): Unit = {
        super.postStop()
        if (zstd != null) {
          zstd.close()
        }
      }

      private def reset(): Unit = {
        zstd.close()
        zstd = new ZSTD(compressionLevel)
        state = State.empty()
      }

      private def finishAndPush(): Unit = {
        state = state.append(zstd.zstdFinish())
        logger.debug(s"Finished compressing object of size ${state.bytes.size}, pushing downstream")
        push(out, state.bytes)
        reset()
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        logger.trace(s"Received element of size ${elem.size}, compressing and adding to current object (currently has ${state.bytes.size} bytes, ${state.left} bytes left until target size of ${minSize} bytes)")
        val compressed = zstd.compress(elem)
        state = state.append(compressed)
        if (state.left <= 0) {
          logger.trace(
            s"Current object has reached target size of ${minSize} bytes (actual size ${state.bytes.size}), finishing and pushing downstream"
          )
          finishAndPush()
        } else {
          logger.trace(
            s"Current object has ${state.left} bytes left until target size of ${minSize} bytes, pulling more input"
          )
          pull(in)
        }
      }

      override def onPull(): Unit = {
        logger.trace(
          s"Downstream pulled, current object has ${state.left} bytes left until target size of ${minSize} bytes, pulling input"
        )
        pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        logger.trace(
          s"Upstream finished, current object has ${state.bytes.length} bytes, finishing and pushing downstream if not empty, then completing stage"
        )
        if (state.bytes.nonEmpty) {
          logger.trace(
            s"Current object has ${state.bytes.length} bytes, finishing and pushing downstream"
          )
          finishAndPush()
        }
        logger.trace("Upstream finished, no more data to process, completing stage")
        completeStage()
      }

      setHandlers(in, out, this)
    }

}
