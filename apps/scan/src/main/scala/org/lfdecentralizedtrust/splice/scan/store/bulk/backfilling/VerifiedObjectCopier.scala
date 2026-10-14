// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import cats.implicits.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.store.S3BucketConnection
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

trait ObjectCopier {
  def copy(objects: Seq[ObjectKeyAndChecksum], peers: Seq[Uri])(implicit
      tc: TraceContext
  ): Future[Unit]
}

class VerifiedObjectCopier(
    source: PeerObjectSource,
    anyPeer: Seq[Uri] => Uri,
    staging: S3BucketConnection,
    committed: S3BucketConnection,
    parallelism: Int,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends ObjectCopier
    with NamedLogging {

  import VerifiedObjectCopier.*

  override def copy(objects: Seq[ObjectKeyAndChecksum], peers: Seq[Uri])(implicit
      tc: TraceContext
  ): Future[Unit] =
    Source(objects.toList)
      .mapAsync(math.max(1, parallelism))(obj => copyOne(obj, peers))
      .runWith(Sink.ignore)
      .map(_ => ())

  private def copyOne(obj: ObjectKeyAndChecksum, peers: Seq[Uri])(implicit
      tc: TraceContext
  ): Future[Unit] =
    (storedChecksum(staging, obj.key), storedChecksum(committed, obj.key)).tupled.flatMap {
      case (_, Some(actual)) if actual != obj.checksum =>
        val error = new CommittedObjectDiffers(obj.key, expected = obj.checksum, actual = actual)
        logger.error(error.getMessage)
        Future.failed(error)
      case (inStaging, inCommitted) =>
        if (inStaging.contains(obj.checksum) || inCommitted.contains(obj.checksum)) {
          logger.debug(s"Object ${obj.key} is already present with the expected checksum, skipping")
          Future.unit
        } else {
          inStaging.foreach(actual =>
            logger.error(
              s"Staging holds object ${obj.key} with checksum $actual instead of the agreed ${obj.checksum}, overwriting it"
            )
          )
          copyFromAnyPeer(obj, peers)
        }
    }

  private def storedChecksum(bucket: S3BucketConnection, key: String)(implicit
      tc: TraceContext
  ): Future[Option[String]] =
    bucket
      .getChecksums(Seq(key))(ec, mat.system, tc)
      .map(_.find(_.key == key).map(_.checksum))

  private def copyFromAnyPeer(obj: ObjectKeyAndChecksum, peers: Seq[Uri])(implicit
      tc: TraceContext
  ): Future[Unit] = {
    def tryRemaining(remaining: Seq[Uri], failures: List[String]): Future[Unit] =
      if (remaining.isEmpty) Future.failed(new CopyFailed(obj.key, failures.reverse))
      else {
        val peer = anyPeer(remaining)
        def tryOthers(e: Throwable) =
          tryRemaining(remaining.filterNot(_ == peer), s"$peer: ${e.getMessage}" :: failures)
        copyFromPeer(obj, peer).recoverWith {
          case e: StagingWriteFailed =>
            Future.failed(e)
          case e: ChecksumMismatch =>
            logger.warn(e.getMessage)
            tryOthers(e)
          case e =>
            logger.info(
              s"Failed to obtain object ${obj.key} from peer $peer, trying the next: ${e.getMessage}"
            )
            tryOthers(e)
        }
      }
    tryRemaining(peers, Nil)
  }

  private def copyFromPeer(obj: ObjectKeyAndChecksum, peer: Uri)(implicit
      tc: TraceContext
  ): Future[Unit] =
    source.open(peer, obj.key).flatMap { download =>
      val writer = staging.newAppendWriteObject(obj.key)
      val digest = MessageDigest.getInstance("SHA-256")
      val lastUpload = new AtomicReference[Future[Unit]](Future.unit)
      download
        .groupedWeighted(uploadPartSize.toLong)(_.size.toLong)
        .map(chunks => chunks.foldLeft(ByteString.empty)(_ ++ _).asByteBuffer)
        .mapAsync(1) { part =>
          // Update the digests here, in part order; never inside the upload Future.
          digest.update(part.duplicate())
          val partNumber = writer.prepareUploadNext(part)
          val upload = writeToStaging(obj.key)(writer.upload(partNumber, part))
          lastUpload.set(upload)
          upload
        }
        .runWith(Sink.ignore)
        .flatMap { _ =>
          val actual = Base64.getEncoder.encodeToString(digest.digest())
          if (actual == obj.checksum) writeToStaging(obj.key)(writer.finish())
          else
            Future.failed(
              new ChecksumMismatch(obj.key, peer, expected = obj.checksum, actual = actual)
            )
        }
        .recoverWith { case e =>
          lastUpload
            .get()
            .transformWith(_ => discardFromStaging(writer))
            .transformWith(_ => Future.failed(e))
        }
    }

  private def writeToStaging[T](key: String)(write: => Future[T]): Future[T] =
    Future.delegate(write).recoverWith { case e =>
      Future.failed(new StagingWriteFailed(key, e))
    }

  private def discardFromStaging(writer: S3BucketConnection#AppendWriteObject): Future[Unit] =
    writer
      .abort()
      .transformWith(_ => staging.deleteObject(writer.key))
      .transformWith(_ => Future.unit)
}

object VerifiedObjectCopier {
  val uploadPartSize: Int = 8 * 1024 * 1024

  val randomPeer: Seq[Uri] => Uri = peers => peers(Random.nextInt(peers.size))

  final class ChecksumMismatch(key: String, peer: Uri, expected: String, actual: String)
      extends RuntimeException(
        s"Checksum mismatch for object $key from peer $peer: expected $expected, got $actual"
      )

  final class StagingWriteFailed(key: String, cause: Throwable)
      extends QuietNonRetryableException(
        s"Could not write object $key to staging: ${cause.getMessage}"
      ) {
    initCause(cause)
  }

  final class CommittedObjectDiffers(key: String, expected: String, actual: String)
      extends QuietNonRetryableException(
        s"Committed holds object $key with checksum $actual instead of the agreed $expected; only an operator can replace it"
      )

  final class CopyFailed(key: String, failures: Seq[String])
      extends QuietNonRetryableException(
        s"Could not copy object $key from any peer: ${failures.mkString("; ")}"
      )
}
