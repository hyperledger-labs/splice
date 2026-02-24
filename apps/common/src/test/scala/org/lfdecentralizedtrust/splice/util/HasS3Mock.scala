package org.lfdecentralizedtrust.splice.util

import com.adobe.testing.s3mock.testcontainers.S3MockContainer
import com.digitalasset.canton.{BaseTest, FutureHelpers}
import coursierapi.shaded.zstd.ZstdInputStream
import io.grpc.netty.shaded.io.netty.buffer.{ByteBufInputStream, Unpooled}
import org.lfdecentralizedtrust.splice.config.S3Config
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.scalatest.EitherValues
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Object

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait HasS3Mock extends FutureHelpers with EitherValues with BaseTest {

  val s3ConfigMock = S3Config(
    "http://127.0.0.1:9090",
    "bucket",
    Region.US_EAST_1.toString,
    "mock_id",
    "mock_key",
  )

  // TODO(#3429): consider running s3Mock container as a service in GHA instead of starting it here.
  def withS3Mock[A](test: => Future[A])(implicit ec: ExecutionContext): Future[A] = {

    val container = new S3MockContainer("4.11.0")
      .withInitialBuckets("bucket")
      .withEnv(
        Map(
          "debug" -> "true"
        ).asJava
      )

    container.setPortBindings(Seq("9090:9090").asJava)
    container.start()

    container.followOutput { frame =>
      logger.debug(s"[s3Mock] ${frame.getUtf8String}")
    }

    test.andThen({ case _ =>
      container.stop()
    })
  }

  def withS3MockSync[A](test: => A): A = {

    val container = new S3MockContainer("4.11.0")
      .withInitialBuckets("bucket")
      .withEnv(
        Map(
          "debug" -> "true"
        ).asJava
      )

    // Fix port bindings, so that we can configure apps in the
    // integration test env before actually starting the container
    container.setPortBindings(Seq("9090:9090").asJava)

    container.start()

    container.followOutput { frame =>
      logger.debug(s"[s3Mock] ${frame.getUtf8String}")
    }

    try {
      test
    } finally {
      container.stop()
    }
  }

  def readUncompressAndDecode[T](
      s3BucketConnection: S3BucketConnection,
      decoder: String => Either[io.circe.Error, T],
  )(s3obj: S3Object)(implicit ec: ExecutionContext, tag: reflect.ClassTag[T]): Array[T] = {
    val compressed = s3BucketConnection.readFullObject(s3obj.key()).futureValue
    val zis = new ZstdInputStream(new ByteBufInputStream(Unpooled.wrappedBuffer(compressed)))
    val buffer = new Array[Byte](16384)
    val out = new ByteArrayOutputStream()

    @tailrec
    def readAll(): Unit = {
      val bytesRead = zis.read(buffer)
      if (bytesRead != -1) {
        out.write(buffer, 0, bytesRead)
        readAll()
      }
    }

    try {
      readAll()
      val uncompressed = ByteBuffer.wrap(out.toByteArray)
      val allContractsStr = StandardCharsets.UTF_8.newDecoder().decode(uncompressed).toString
      val allContracts = allContractsStr.split("\n")
      allContracts.map(decoder(_).value)
    } finally {
      zis.close()
    }
  }
}

//object CompactJsonScanHttpEncodingsWithFieldLabels {
//  def apply() = new CompactJsonScanHttpEncodings(identity, identity)
//}
