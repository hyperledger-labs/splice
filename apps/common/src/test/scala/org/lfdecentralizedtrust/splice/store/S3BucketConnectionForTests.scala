package org.lfdecentralizedtrust.splice.store

import com.digitalasset.canton.logging.NamedLoggerFactory
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.config.S3Config

import java.io.{ByteArrayInputStream, IOException}
import java.nio.ByteBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process.*

class S3BucketConnectionForTests(
    s3Config: S3Config,
    override val loggerFactory: NamedLoggerFactory,
) extends S3BucketConnection(s3Config, loggerFactory) {
  // Reads the full content of an s3 object into a ByteBuffer. Also verifies its checksum stored in the splice-checksum tag, and throws an assertion if it's missing or incorrect.
  // Use only for testing, when the object size is known to be small
  def readFullObject(key: String)(implicit ec: ExecutionContext, as: ActorSystem): Future[ByteBuffer] = {
    for {
      data <- readObject(key)
        .map(ByteString.fromByteBuffer)
        .runWith(Sink.fold(ByteString.empty)(_ ++ _))
        .map(_.asByteBuffer)
      checksum <- readChecksum(key)
    } yield {
      val bytes = new Array[Byte](data.remaining)
      data.duplicate.get(bytes)
      val bis = new ByteArrayInputStream(bytes)
      // We compare the computed & stored checksum to one we independently compute via the system's `sha256sum` executable for sanity
      val expectedChecksum =
        ("sha256sum" #| "awk '{print $1}'" #| "xxd -r -p" #| "base64" #< bis).!!.trim
      if (checksum != expectedChecksum) {
        throw new IOException(s"Checksum mismatch. Expected $expectedChecksum, got $checksum")
      }
      data
    }
  }
}
