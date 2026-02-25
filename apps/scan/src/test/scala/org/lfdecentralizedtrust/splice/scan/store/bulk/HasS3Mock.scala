package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.{BaseTest, FutureHelpers}
import com.github.luben.zstd.ZstdInputStream
import io.grpc.netty.shaded.io.netty.buffer.{ByteBufInputStream, Unpooled}
import org.lfdecentralizedtrust.splice.scan.admin.http.CompactJsonScanHttpEncodings
import org.scalatest.EitherValues
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, S3Object}
import org.lfdecentralizedtrust.splice.scan.config.S3Config

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import org.gaul.s3proxy.S3Proxy

import java.util.Properties
import org.jclouds.ContextBuilder
import org.jclouds.blobstore.BlobStoreContext
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import scala.jdk.FutureConverters.*
import java.net.URI

trait HasS3Mock extends NamedLogging with FutureHelpers with EitherValues with BaseTest {

  val s3Config = S3Config(
    "http://localhost:9090",
    "bucket",
    Region.US_EAST_1.toString,
    "mock_id",
    "mock_key",
  )

  // Note: we used Adobe s3mock before. It worked well in a container, but that adds a docker dependency,
  // and it doesn't work well in-process (restarts are a pain).
  // We therefore transitioned for now to s3Proxy with a "transient" (i.e. in-memory) backend. It is unfortunately
  // significantly slower than s3mock though, so if in the future runtime of tests that use s3 mocks becomes an issue,
  // we should reconsider again.
  def withS3Mock[A](
      loggerFactory: NamedLoggerFactory
  )(test: S3BucketConnection => Future[A])(implicit ec: ExecutionContext): Future[A] = {

    val properties = new Properties
    properties.setProperty("jclouds.provider", "transient-nio2")
    properties.setProperty("transient-nio2.identity", "mock_id")
    properties.setProperty("transient-nio2.credential", "mock_key")
    val blobStoreContext = ContextBuilder
      .newBuilder("transient-nio2")
      .overrides(properties)
      .build(classOf[BlobStoreContext])
    val s3ProxyBuilder = S3Proxy.builder.blobStore(blobStoreContext.getBlobStore)
    s3ProxyBuilder.endpoint(URI.create(s3Config.endpoint))
    val s3Proxy = s3ProxyBuilder.build
    s3Proxy.start()

    val createBucketRequest = CreateBucketRequest.builder().bucket("bucket").build()
    val client = S3AsyncClient
      .builder()
      .endpointOverride(URI.create(s3Config.endpoint))
      .region(Region.US_EAST_1)
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)
        )
      )
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .build

    (for {
      _ <- client.createBucket(createBucketRequest).asScala
      res <- test(getS3BucketConnection(loggerFactory))
    } yield res).andThen({ case _ =>
      s3Proxy.stop()
    })
  }

  private def getS3BucketConnection(
      loggerFactory: NamedLoggerFactory
  ): S3BucketConnection = {
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
