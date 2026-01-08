package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.FutureHelpers
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import com.github.luben.zstd.ZstdDirectBufferDecompressingStream
import io.netty.buffer.PooledByteBufAllocator
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodingsWithOrWithoutFieldLabels
import org.lfdecentralizedtrust.splice.scan.store.bulk.{S3BucketConnection, S3Config}
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.invocation.InvocationOnMock
import org.scalatest.EitherValues
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.S3Object

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.*
import scala.util.Using

trait HasS3Mock extends FutureHelpers with EitherValues {

  // TODO(#3429): consider running s3Mock container as a service in GHA instead of starting it here
  def withS3Mock[A](test: => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    Seq(
      "docker",
      "run",
      "-p",
      "9090:9090",
      "-e",
      "COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS=bucket",
      "-d",
      "--rm",
      "--name",
      "s3mock",
      "adobe/s3mock",
    ).!
    test.andThen({ case _ => Seq("docker", "stop", "s3mock").! })
  }

  def getS3BucketConnectionWithInjectedErrors(
      loggerFactory: NamedLoggerFactory
  ): S3BucketConnection = {
    val s3BucketConnection: S3BucketConnection = getS3BucketConnection(loggerFactory)
    val s3BucketConnectionWithErrors = Mockito.spy(s3BucketConnection)
    var failureCount = 0
    val _ = doAnswer { (invocation: InvocationOnMock) =>
      val args = invocation.getArguments
      args.toList match {
        case (key: String) :: _ if key.endsWith("2.zstd") =>
          if (failureCount < 2) {
            failureCount += 1
            Future.failed(new RuntimeException("Simulated S3 write error"))
          } else {
            invocation.callRealMethod().asInstanceOf[Future[Unit]]
          }
        case _ =>
          invocation.callRealMethod().asInstanceOf[Future[Unit]]
      }
    }.when(s3BucketConnectionWithErrors)
      .writeFullObject(anyString(), any[ByteBuffer])(any[TraceContext], any[ExecutionContext])
    s3BucketConnectionWithErrors
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
    val uncompressedDirect = bufferAllocator.directBuffer(compressed.capacity() * 200)
    val uncompressedNio = uncompressedDirect.nioBuffer(0, uncompressedDirect.capacity())
    compressedDirect.writeBytes(compressed)
    Using(new ZstdDirectBufferDecompressingStream(compressedDirect.nioBuffer())) {
      _.read(uncompressedNio)
    }
    uncompressedNio.flip()
    val allContractsStr = StandardCharsets.UTF_8.newDecoder().decode(uncompressedNio).toString
    val allContracts = allContractsStr.split("\n")
    compressedDirect.release()
    uncompressedDirect.release()
    allContracts.map(decoder(_).value)
  }

}

case object CompactJsonScanHttpEncodingsWithFieldLabels
    extends CompactJsonScanHttpEncodingsWithOrWithoutFieldLabels(true)
