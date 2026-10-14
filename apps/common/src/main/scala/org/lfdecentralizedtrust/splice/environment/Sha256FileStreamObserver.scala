// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.grpc.OutputFileStreamObserver
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver

import java.security.MessageDigest
import scala.concurrent.{Future, Promise}
import scala.util.Try

final class Sha256FileStreamObserver[T] private[environment] (
    converter: T => ByteString,
    createFileObserver: (T => ByteString) => StreamObserver[T],
) extends StreamObserver[T] {
  private val sha256 = MessageDigest.getInstance("SHA-256")
  private val fileObserver = createFileObserver { value =>
    val bytes = converter(value)
    sha256.update(bytes.asReadOnlyByteBuffer())
    bytes
  }
  private val promise = Promise[ByteString]()

  def result: Future[ByteString] = promise.future

  override def onNext(value: T): Unit = fileObserver.onNext(value)

  override def onError(t: Throwable): Unit = {
    Try(fileObserver.onError(t)).discard
    promise.tryFailure(t).discard
  }

  override def onCompleted(): Unit =
    promise
      .tryComplete(Try(fileObserver.onCompleted()).map(_ => ByteString.copyFrom(sha256.digest())))
      .discard
}

object Sha256FileStreamObserver {
  def apply[T](file: File, converter: T => ByteString): Sha256FileStreamObserver[T] =
    new Sha256FileStreamObserver[T](converter, new OutputFileStreamObserver[T](file, _))
}
