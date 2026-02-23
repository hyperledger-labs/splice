package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.{BaseTest, FutureHelpers}
import com.github.luben.zstd.ZstdInputStream
import io.grpc.netty.shaded.io.netty.buffer.{ByteBufInputStream, Unpooled}
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.scalatest.EitherValues
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Object
import com.adobe.testing.s3mock.testcontainers.S3MockContainer
import org.lfdecentralizedtrust.splice.scan.config.S3Config

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait HasS3Mock extends NamedLogging with FutureHelpers with EitherValues with BaseTest {

  // TODO(#3429): consider running s3Mock container as a service in GHA instead of starting it here.
  def withS3Mock[A](
      loggerFactory: NamedLoggerFactory
  )(test: S3BucketConnection => Future[A])(implicit ec: ExecutionContext): Future[A] = {

    val container = new S3MockContainer("4.11.0")
      .withInitialBuckets("bucket")
      .withEnv(
        Map(
          "debug" -> "true"
        ).asJava
      )

    container.start()

    container.followOutput { frame =>
      logger.debug(s"[s3Mock] ${frame.getUtf8String}")
    }

    test(getS3BucketConnection(loggerFactory, container)).andThen({ case _ =>
      container.stop()
    })
  }

  def withS3MockSync[A](
      loggerFactory: NamedLoggerFactory
  )(test: S3BucketConnection => A)(implicit ec: ExecutionContext): A = {

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
      test(getS3BucketConnection(loggerFactory, container))
    } finally {
      container.stop()
    }
  }

  private def getS3BucketConnection(
      loggerFactory: NamedLoggerFactory,
      container: S3MockContainer,
  ): S3BucketConnection = {
    val s3Config = S3Config(
      container.getHttpEndpoint,
      "bucket",
      Region.US_EAST_1.toString,
      "mock_id",
      "mock_key",
    )
    S3BucketConnectionForUnitTests(s3Config, "bucket", loggerFactory)
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

object CompactJsonScanHttpEncodingsWithFieldLabels {
  def apply() = new CompactJsonScanHttpEncodings(identity, identity)
}
