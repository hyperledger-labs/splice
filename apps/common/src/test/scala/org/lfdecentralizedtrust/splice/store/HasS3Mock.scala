package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.{BaseTest, FutureHelpers, HasExecutionContext}
import com.github.luben.zstd.ZstdInputStream
import io.grpc.netty.shaded.io.netty.buffer.{ByteBufInputStream, Unpooled}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, EitherValues, Suite}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{
  Delete,
  DeleteObjectsRequest,
  ListObjectsRequest,
  ObjectIdentifier,
  S3Object,
}
import com.adobe.testing.s3mock.testcontainers.S3MockContainer
import org.lfdecentralizedtrust.splice.config.S3Config
import org.testcontainers.containers.wait.strategy.Wait

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

trait HasS3Mock
    extends NamedLogging
    with FutureHelpers
    with EitherValues
    with BaseTest
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with HasExecutionContext { this: Suite =>

  val s3ConfigMock = S3Config(
    "http://127.0.0.1:9090",
    "bucket",
    Region.US_EAST_1.toString,
    "mock_id",
    "mock_key",
  )

  private var container: Option[S3MockContainer] = None
  override def beforeAll(): Unit = startContainer()
  override def afterAll(): Unit = container.foreach(_.stop)
  override def beforeEach(): Unit = clearBucket()

  private def startContainer() = {
    val c = new S3MockContainer("4.11.0")
      .withInitialBuckets("bucket")
      .withExposedPorts(9090)
      .withEnv(
        Map(
          "debug" -> "true"
        ).asJava
      )
      .waitingFor(
        Wait
          .forHttp("/")
          .forPort(9090)
          .forStatusCode(200)
          .withStartupTimeout(java.time.Duration.ofMinutes(5))
      )

    c.setPortBindings(Seq("9090:9090").asJava)
    c.start()

    c.followOutput { frame =>
      logger.debug(s"[s3Mock] ${frame.getUtf8String}")
    }
    container = Some(c)
    c
  }

  private def clearBucket() = {

    val client = S3BucketConnection(s3ConfigMock, loggerFactory).s3Client

    val listRequest = ListObjectsRequest.builder().bucket(s3ConfigMock.bucketName).build()
    (for {
      list <- client.listObjects(listRequest).asScala
      objs = list.contents().asScala.map { obj =>
        ObjectIdentifier.builder().key(obj.key()).build()
      }
      deleteObjRequest = DeleteObjectsRequest
        .builder()
        .bucket(s3ConfigMock.bucketName)
        .delete(
          Delete.builder().objects(objs.asJava).build()
        )
        .build()
      _ <-
        if (list.contents().asScala.isEmpty) { Future.successful(()) }
        else { client.deleteObjects(deleteObjRequest).asScala }
    } yield {
      ()
    }).futureValue
  }

  def readUncompressAndDecode[T](
      s3BucketConnection: S3BucketConnection,
      decoder: String => Either[io.circe.Error, T],
  )(s3obj: S3Object)(implicit tag: reflect.ClassTag[T]): Array[T] = {
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
