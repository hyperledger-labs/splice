package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.{BaseTest, FutureHelpers}
import com.github.luben.zstd.ZstdDirectBufferDecompressingStream
import io.grpc.netty.shaded.io.netty.buffer.PooledByteBufAllocator
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.scalatest.EitherValues
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Object
import com.adobe.testing.s3mock.testcontainers.S3MockContainer

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
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

  private def getS3BucketConnection(
      loggerFactory: NamedLoggerFactory,
      container: S3MockContainer,
  ): S3BucketConnection = {
    val s3Config = S3Config(
      URI.create(container.getHttpEndpoint),
      "bucket",
      Region.US_EAST_1,
      AwsBasicCredentials.create("mock_id", "mock_key"),
    )
    S3BucketConnection(s3Config, "bucket", loggerFactory)
  }

  def readUncompressAndDecode[T](
      s3BucketConnection: S3BucketConnection,
      decoder: String => Either[io.circe.Error, T],
  )(s3obj: S3Object)(implicit ec: ExecutionContext, tag: reflect.ClassTag[T]): Array[T] = {
    val bufferAllocator = PooledByteBufAllocator.DEFAULT
    val compressed = s3BucketConnection.readFullObject(s3obj.key()).futureValue
    val compressedDirect = bufferAllocator.directBuffer(compressed.capacity())
    // Empirically, our data is compressed by a factor of at most 200 (and is deterministic, so this is not expected to flake)
    val uncompressedDirect = bufferAllocator.directBuffer(compressed.capacity() * 200)
    try {
      val uncompressedNio = uncompressedDirect.nioBuffer(0, uncompressedDirect.capacity())
      compressedDirect.writeBytes(compressed)
      Using(new ZstdDirectBufferDecompressingStream(compressedDirect.nioBuffer())) {
        _.read(uncompressedNio)
      }
      uncompressedNio.flip()
      val allContractsStr = StandardCharsets.UTF_8.newDecoder().decode(uncompressedNio).toString
      val allContracts = allContractsStr.split("\n")
      allContracts.map(decoder(_).value)
    } finally {
      compressedDirect.release()
      uncompressedDirect.release()
    }
  }
}

object CompactJsonScanHttpEncodingsWithFieldLabels {
  def apply() = new CompactJsonScanHttpEncodings(identity, identity)
}
