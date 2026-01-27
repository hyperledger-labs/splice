package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.{BaseTest, FutureHelpers}
import com.github.luben.zstd.ZstdDirectBufferDecompressingStream
import io.netty.buffer.PooledByteBufAllocator
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.scalatest.EitherValues
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Object

import java.net.URI
import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.*
import scala.util.Using

trait HasS3Mock extends NamedLogging with FutureHelpers with EitherValues with BaseTest {

  // TODO(#3429): consider running s3Mock container as a service in GHA instead of starting it here. Alternatively, use TestContainers to manage the container
  def withS3Mock[A](test: => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    // start s3Mock in a contaner
    "docker run -p 9090:9090 -e COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=bucket -e debug=true -d --rm --name s3mock adobe/s3mock".!

    eventually() {
      // wait for S3Mock to be ready
      "curl --output /dev/null --silent --head --fail -H \"Accept: application/json\" http://localhost:9090/actuator/health".! shouldBe 0
    }

    // Redirect s3Mock container logs into our log
    val loggerProcess = "docker logs s3mock -f".run(
      ProcessLogger(
        line => logger.debug(s"[s3Mock] $line"),
        line => logger.warn(s"[s3Mock] $line"),
      )
    )

    test.andThen({ case _ =>
      Seq("docker", "stop", "s3mock").!
      loggerProcess.destroy()
    })
  }

  def getS3BucketConnection(loggerFactory: NamedLoggerFactory): S3BucketConnection = {
    val s3Config = S3Config(
      URI.create("http://localhost:9090"),
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
