// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}

class SeqAccumulatingObserver[T] extends io.grpc.stub.StreamObserver[T] {
  private val promise: Promise[List[T]] = Promise[List[T]]()
  private val buffer: ListBuffer[T] = ListBuffer.empty[T]

  def resultFuture: Future[Seq[T]] = promise.future

  override def onNext(value: T): Unit = {
    buffer.append(value)
  }

  override def onError(t: Throwable): Unit = {
    promise.failure(t)
  }

  override def onCompleted(): Unit = {
    promise.success(buffer.toList)
  }
}
