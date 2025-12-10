package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.util.ByteString

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** A Pekko GraphStage that zstd-compresses a stream of bytestrings, and splits the output into zstd objects of size (minWeight + delta).
  * Somewhat similar to Pekko's built-in GroupedWeight, but outputs valid zstd compressed objects.
  */
case class ZstdGroupedWeight(minSize: Long) extends GraphStage[FlowShape[ByteString, ByteString]] {
  require(minSize > 0, "minSize must be greater than 0")

  val zstdTmpBufferSize = 10 * 1024 * 1024; // TODO: make configurable?

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
      val tmpBufferSize: Int,
      val compressionLevel: Int = 3,
  ) {

    val tmpBuffer = ByteBuffer.allocateDirect(tmpBufferSize)
    val compressingStream =
      new ZstdDirectBufferCompressingStreamNoFinalizer(tmpBuffer, compressionLevel)

    def compress(input: ByteString): ByteString = {
      val inputBB = ByteBuffer.allocateDirect(input.size)
      inputBB.put(input.toArrayUnsafe())
      inputBB.flip()
      compressingStream.compress(inputBB)
      compressingStream.flush()
      tmpBuffer.flip()
      val result = ByteString.fromByteBuffer(tmpBuffer)
      tmpBuffer.clear()
      result
    }

    def zstdFinish(): ByteString = {
      compressingStream.close()
      tmpBuffer.flip()
      val result = ByteString.fromByteBuffer(tmpBuffer)
      tmpBuffer.clear()
      compressingStream.close()
      result
    }
  }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler {
      private val zstd = new AtomicReference[ZSTD](new ZSTD(zstdTmpBufferSize, 3))
      private val state: AtomicReference[State] = new AtomicReference[State](State.empty())

      private def reset(): Unit = {
        zstd.set(new ZSTD(zstdTmpBufferSize, 3))
        state.set(State.empty())
      }

      override def onPush(): Unit = {
        val elem = grab(in)
        val compressed = zstd.get().compress(elem)
        state.set(state.get().append(compressed))
        if (state.get().left <= 0) {
          state.set(state.get().append(zstd.get().zstdFinish()))
          push(out, state.get().bytes)
          reset()
        } else {
          pull(in)
        }
      }

      override def onPull(): Unit = pull(in)

      override def onUpstreamFinish(): Unit = {
        if (state.get().bytes.nonEmpty) {
          state.set(state.get().append(zstd.get().zstdFinish()))
          push(out, state.get().bytes)
        }
        completeStage()
      }

      setHandlers(in, out, this)
    }

}
