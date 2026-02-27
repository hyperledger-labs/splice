package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.config.S3Config
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Configuration}

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.net.URI
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*
import scala.util.{Try, Using}

class S3BucketConnectionForUnitTests(
    s3Client: S3AsyncClient,
    bucketName: String,
    override val loggerFactory: NamedLoggerFactory,
) extends S3BucketConnection(s3Client, bucketName, loggerFactory) {

  override def readFullObject(key: String)(implicit ec: ExecutionContext): Future[ByteBuffer] = {
    val request = GetObjectRequest.builder.bucket(bucketName).key(key).build
    s3Client.getObject(request, AsyncResponseTransformer.toBytes[GetObjectResponse]).asScala.map {
      s3Stream =>
        Using.resource(new DataInputStream(s3Stream.asInputStream())) { dataInput =>
          def readAll(): Vector[Array[Byte]] =
            Vector.unfold(()) { _ =>
              Try {
                PaddedData.readNext(dataInput)
              }.toOption
                .map { d => (d.data.array(), ()) }
            }

          def concatenate(chunks: Vector[Array[Byte]]): ByteBuffer = {
            val totalSize = chunks.map(_.length).sum
            val buffer = ByteBuffer.allocate(totalSize)
            chunks.foreach(buffer.put)
            buffer.flip()
            buffer
          }

          val allBytes = readAll()
          concatenate(allBytes)
        }
    }
  }

  override def writeFullObject(key: String, content: ByteBuffer)(implicit
      tc: TraceContext,
      ec: ExecutionContext,
      // We don't strictly need any padding here, but easier to use 1 than deal with the special case of zero padding everywhere else
  ): Future[Unit] = super.writeFullObject(key, PaddedData(1, content).toByteBuffer())

  override def newAppendWriteObject(key: String)(implicit ec: ExecutionContext): AppendWriteObject =
    new AppendWriteObjectForUnitTests(key)

  class AppendWriteObjectForUnitTests protected[S3BucketConnectionForUnitTests] (
      override val key: String
  )(implicit
      ec: ExecutionContext,
  ) extends AppendWriteObject(key) {
    override def upload(partNumber: Int, content: ByteBuffer): Future[Unit] = {
      // We pad all parts with 5MB of zeros, to guarantee we're above the 5MB
      // minimum per part (technically, the minimum is "except the last" part,
      // but we don't really know that a part is last in this API)
      val paddingSize = 5242880
      super.upload(partNumber, PaddedData(paddingSize, content).toByteBuffer())
    }
  }
}
object S3BucketConnectionForUnitTests {
  def apply(
      s3Config: S3Config,
      loggerFactory: NamedLoggerFactory,
  ): S3BucketConnection = {
    new S3BucketConnectionForUnitTests(
      S3AsyncClient
        .builder()
        .endpointOverride(URI.create(s3Config.endpoint))
        .region(Region.of(s3Config.region))
        .credentialsProvider(
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(s3Config.accessKeyId, s3Config.secretAccessKey)
          )
        )
        // TODO(#3429): mockS3 and GCS support only path style access. Do we need to make this configurable?
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build(),
      s3Config.bucketName,
      loggerFactory,
    )
  }
}

case class PaddedData(paddingSize: Int, data: ByteBuffer) {
  def toByteBuffer(): ByteBuffer = {
    val intSize = java.lang.Integer.BYTES
    val totalSize = (intSize * 2) + data.remaining() + paddingSize
    val combined = ByteBuffer.allocate(totalSize)
    combined.putInt(data.remaining())
    combined.putInt(paddingSize)
    combined.put(data.duplicate())
    combined.position(combined.position() + paddingSize)
    combined.flip()
  }
}
object PaddedData {
  def readNext(source: DataInputStream): PaddedData = {
    val dataSize = source.readInt()
    val paddingSize = source.readInt()
    val data = new Array[Byte](dataSize)
    source.readFully(data)
    source.skipBytes(paddingSize)
    PaddedData(paddingSize, ByteBuffer.wrap(data))
  }
}
