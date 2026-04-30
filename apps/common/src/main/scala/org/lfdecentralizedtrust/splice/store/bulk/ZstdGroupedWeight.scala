// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.bulk

import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer
import io.grpc.netty.shaded.io.netty.buffer.PooledByteBufAllocator
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.util.ByteString

/** A Pekko GraphStage that zstd-compresses a stream of bytestrings, and splits the output into zstd objects of size (minWeight + delta).
  * Somewhat similar to Pekko's built-in GroupedWeight, but outputs valid zstd compressed objects.
  */
case class ZstdGroupedWeight(
    compressionLevel: Int,
    minSize: Long,
) extends GraphStage[FlowShape[ByteString, ByteString]] {
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
        push(out, state.bytes)
        reset()
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        val compressed = zstd.compress(elem)
        state = state.append(compressed)
        if (state.left <= 0) {
          finishAndPush()
        } else {
          pull(in)
        }
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        if (state.bytes.nonEmpty) {
          finishAndPush()
        }
        completeStage()
      }

      setHandlers(in, out, this)
    }

}
