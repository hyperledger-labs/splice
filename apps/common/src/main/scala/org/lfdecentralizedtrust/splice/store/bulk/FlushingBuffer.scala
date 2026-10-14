// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.bulk

import com.github.luben.zstd.ZstdDirectBufferCompressingStreamNoFinalizer
import io.grpc.netty.shaded.io.netty.buffer.{ByteBuf, PooledByteBufAllocator}

import java.nio.ByteBuffer
import scala.util.control.NonFatal
import org.apache.pekko.util.ByteString

class FlushingBuffer(
    directBuffer: ByteBuf,
    tmpBuffer: ByteBuffer,
    compressionLevel: Int,
) extends ZstdDirectBufferCompressingStreamNoFinalizer(tmpBuffer, compressionLevel) {

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var drained = ByteString.empty

  override protected def flushBuffer(toFlush: ByteBuffer): ByteBuffer = {
    toFlush.flip()
    drained = drained ++ ByteString.fromByteBuffer(toFlush)
    toFlush.clear()
    toFlush
  }

  def read(): ByteString = {
    val _ = flushBuffer(tmpBuffer)
    val result = drained
    drained = ByteString.empty
    result
  }

  def release(): Unit = {
    val _ = directBuffer.release()
  }
}

object FlushingBuffer {

  def apply(compressionLevel: Int, tmpBufferSize: Int): FlushingBuffer = {
    val bufferAllocator = PooledByteBufAllocator.DEFAULT
    val tmpBuffer = bufferAllocator.directBuffer(tmpBufferSize)
    try {
      val nioBuffer = tmpBuffer.nioBuffer(0, tmpBuffer.capacity())
      new FlushingBuffer(tmpBuffer, nioBuffer, compressionLevel)
    } catch {
      // a failed construction never reaches close(), so release the buffer here
      case NonFatal(e) =>
        val _ = tmpBuffer.release()
        throw e
    }
  }
}
