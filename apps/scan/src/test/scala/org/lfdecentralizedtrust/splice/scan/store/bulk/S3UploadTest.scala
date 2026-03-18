// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, StoreTestBase}

import scala.util.Random
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import java.nio.ByteBuffer

class S3UploadTest extends StoreTestBase with HasS3Mock {

  "S3 multipart uploads" should {
    "work" in {

      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)
      val o = bucketConnection.newAppendWriteObject("test")
      val part1 = ByteBuffer.wrap("hello".getBytes("UTF-8"))
      val part2 = ByteBuffer.wrap("world".getBytes("UTF-8"))

      o.prepareUploadNext(part1)
      o.prepareUploadNext(part2)
      for {
        _ <- o.upload(1, part1)
        _ <- o.upload(2, part2)
        _ <- o.finish()
        content <- bucketConnection.readFullObject("test")
      } yield {
        new String(content.toArray, "UTF-8") shouldBe "helloworld"
      }
    }
  }

  "GroupedWeightS3Object" should {

    "just work" in {

      val data = ByteString(Random.nextBytes(100))
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(
          GroupedWeightS3ObjectFlow(
            bucketConnection,
            getObjectKey = i => s"test_$i",
            maxObjectSize = 10L,
            maxParallelPartUploads = 2,
            loggerFactory,
          )
        )
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      val it = data.iterator
      def sendBytes(n: Int) =
        pub.sendNext(it.getByteString(n))

      val inputSizes =
        Seq.fill(9)(3) :+ // 9 inputs of size 3, to test the basic functionality
          7 :+ // add 7 to exactly hit the edge of the object size (10)
          25 :+ // add an input that does not fit
          1 // finish with a tiny input
      inputSizes.foreach(sendBytes)
      pub.sendComplete()

      sub.request(5)
      val expectedObjectSizes = Seq(12, 12, 10, 25, 1)
      expectedObjectSizes.indices.foreach(i =>
        sub.expectNext(20.seconds) shouldBe s"test_$i"
      )
      sub.expectComplete()

      val s3Objects = bucketConnection.listObjects.futureValue
      val s3ObjKeys = s3Objects.contents.asScala.sortBy(_.key())
      val s3ObjData = s3ObjKeys.map { obj =>
        bucketConnection.readFullObject(obj.key()).futureValue
      }.toSeq
      s3ObjData.map(_.length) shouldBe expectedObjectSizes
      val dataFromS3 = s3ObjData.foldLeft(ByteString.empty) { (acc, buf) => acc ++ ByteString(buf) }
      dataFromS3 shouldBe data.take(expectedObjectSizes.sum)
    }

    "handle errors correctly" in {

      val data = ByteString(Random.nextBytes(100))
      val bucketConnection = new S3BucketConnectionForUnitTests(s3ConfigMock, loggerFactory)

      val (pub, sub) = TestSource
        .probe[ByteString]
        .via(
          GroupedWeightS3ObjectFlow(
            bucketConnection,
            getObjectKey = i => s"test_$i",
            maxObjectSize = 10L,
            maxParallelPartUploads = 2,
            loggerFactory,
          )
        )
        .toMat(TestSink.probe[String])(Keep.both)
        .run()

      val it = data.iterator
      def sendBytes(n: Int) =
        pub.sendNext(it.getByteString(n))

      val inputSizes = Seq(6,6,3)
      inputSizes.foreach(sendBytes)
      pub.sendError(new RuntimeException("Injected error"))
      sub.request(1)
      sub.expectError()
      succeed
    }
  }
}
