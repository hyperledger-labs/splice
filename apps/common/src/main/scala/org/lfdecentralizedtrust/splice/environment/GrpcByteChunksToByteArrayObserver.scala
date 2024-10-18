// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.util.ResourceUtil
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver

import java.io.ByteArrayOutputStream
import scala.concurrent.Promise
import scala.language.reflectiveCalls
import scala.util.{Failure, Success, Try}

class GrpcByteChunksToByteArrayObserver[
    T <: GrpcByteChunksToByteArrayObserver.ByteStringChunk
](
    requestComplete: Promise[ByteString]
) extends StreamObserver[T] {
  val bs = new ByteArrayOutputStream()
  override def onNext(value: T): Unit = {
    Try(bs.write(value.chunk.toByteArray)) match {
      case Failure(exception) =>
        ResourceUtil.closeAndAddSuppressed(Some(exception), bs)
        throw exception
      case Success(_) =>
    }
  }

  override def onError(t: Throwable): Unit = {
    requestComplete.tryFailure(t).discard
    ResourceUtil.closeAndAddSuppressed(None, bs)
  }

  override def onCompleted(): Unit = {
    requestComplete.trySuccess(ByteString.copyFrom(bs.toByteArray)).discard
    ResourceUtil.closeAndAddSuppressed(None, bs)
  }
}

object GrpcByteChunksToByteArrayObserver {
  type ByteStringChunk = { val chunk: ByteString }
}
