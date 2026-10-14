// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import better.files.File
import com.digitalasset.canton.BaseTest
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.security.MessageDigest

class Sha256FileStreamObserverTest extends AnyWordSpec with BaseTest {

  private def withObserver(test: (File, Sha256FileStreamObserver[String]) => Unit): Unit =
    File.usingTemporaryFile() { file =>
      test(file, Sha256FileStreamObserver[String](file, ByteString.copyFromUtf8))
    }

  private def sha256(bytes: Array[Byte]) =
    ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes))

  "Sha256FileStreamObserver" should {

    "write all chunks to the file and return their SHA-256" in withObserver { (file, observer) =>
      Seq("first chunk,", "", " second chunk").foreach(observer.onNext)
      observer.onCompleted()

      val expected = "first chunk, second chunk".getBytes
      observer.result.futureValue shouldBe sha256(expected)
      file.byteArray shouldBe expected
    }

    "return the SHA-256 of an empty file for an empty stream" in withObserver { (file, observer) =>
      observer.onCompleted()

      observer.result.futureValue shouldBe sha256(Array.emptyByteArray)
      file.size shouldBe 0L
    }

    "fail with the stream error" in withObserver { (_, observer) =>
      observer.onNext("partial")
      val error = new RuntimeException("stream failed")
      observer.onError(error)

      observer.result.failed.futureValue shouldBe error
    }

    "fail instead of hanging when closing the file fails" in {
      val closeFailure = new IOException("close failed")
      def withFailingClose() =
        new Sha256FileStreamObserver[String](
          ByteString.copyFromUtf8,
          _ =>
            new StreamObserver[String] {
              override def onNext(value: String): Unit = ()
              override def onError(t: Throwable): Unit = throw closeFailure
              override def onCompleted(): Unit = throw closeFailure
            },
        )

      val completed = withFailingClose()
      completed.onCompleted()
      completed.result.failed.futureValue shouldBe closeFailure

      val failed = withFailingClose()
      val error = new RuntimeException("stream failed")
      failed.onError(error)
      failed.result.failed.futureValue shouldBe error
    }
  }
}
