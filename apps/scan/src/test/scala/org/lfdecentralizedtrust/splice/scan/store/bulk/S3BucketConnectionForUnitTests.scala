package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.logging.NamedLoggerFactory
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.config.S3Config
import org.lfdecentralizedtrust.splice.store.S3BucketConnectionForTests
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, GetObjectResponse}

import java.io.DataInputStream
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*
import scala.util.{Try, Using}

class S3BucketConnectionForUnitTests(
    s3Config: S3Config,
    override val loggerFactory: NamedLoggerFactory,
) extends S3BucketConnectionForTests(s3Config, loggerFactory) {

  override def readFullObject(key: String)(implicit ec: ExecutionContext): Future[ByteString] = {
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
          ByteString.fromByteBuffer(concatenate(allBytes))
        }
    }
  }

  override def newAppendWriteObject(key: String)(implicit ec: ExecutionContext): AppendWriteObject =
    new AppendWriteObjectForUnitTests(key)

  class AppendWriteObjectForUnitTests protected[S3BucketConnectionForUnitTests] (
      override val key: String
  )(implicit
      ec: ExecutionContext
  ) extends AppendWriteObject(key) {

    // We pad all parts with 5MB of zeros, to guarantee we're above the 5MB
    // minimum per part (technically, the minimum is "except the last" part,
    // but we don't really know that a part is last in this API)
    val paddingSize = 5242880

    override def prepareUploadNext(content: ByteBuffer): Int =
      super.prepareUploadNext(PaddedData(paddingSize, content).toByteBuffer())

    override def upload(partNumber: Int, content: ByteBuffer): Future[Unit] =
      super.upload(partNumber, PaddedData(paddingSize, content).toByteBuffer())
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
