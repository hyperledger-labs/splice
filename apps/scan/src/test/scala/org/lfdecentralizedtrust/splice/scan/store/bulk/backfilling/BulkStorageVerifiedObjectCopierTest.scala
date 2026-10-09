// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk.backfilling

import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.lfdecentralizedtrust.splice.environment.RetryProvider.QuietNonRetryableException
import org.lfdecentralizedtrust.splice.scan.store.bulk.S3BucketConnectionForUnitTests
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.lfdecentralizedtrust.splice.store.{
  HasS3Mock,
  S3BucketConnection,
  S3BucketConnectionForTests,
  StoreTestBase,
}

import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*
import scala.util.Random

class BulkStorageVerifiedObjectCopierTest
    extends StoreTestBase
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock {

  override val initialBuckets: Seq[String] = Seq("peer1", "peer2", "staging", "committed")

  private def peerBucket(name: String) =
    new S3BucketConnectionForUnitTests(s3ConfigMock(name), loggerFactory)

  private def localBucket(name: String) =
    new S3BucketConnectionForTests(s3ConfigMock(name), loggerFactory)

  private def putOnPeers(key: String, content: ByteString): Future[String] = {
    def put(bucketName: String) = {
      val writer = peerBucket(bucketName).newAppendWriteObject(key)
      val part = content.asByteBuffer
      writer.prepareUploadNext(part)
      writer.upload(1, part).flatMap(_ => writer.finish())
    }
    for {
      _ <- put("peer1")
      _ <- put("peer2")
      checksums <- peerBucket("peer1").getChecksums(Seq(key))
    } yield checksums.head.checksum
  }

  private def putInLocal(bucketName: String, key: String, bytes: ByteString): Future[Unit] = {
    val writer = localBucket(bucketName).newAppendWriteObject(key)
    val part = bytes.asByteBuffer
    writer.prepareUploadNext(part)
    writer.upload(1, part).flatMap(_ => writer.finish())
  }

  private def storedBytes(bucketName: String, key: String): Future[ByteString] =
    localBucket(bucketName).readObject(key).flatMap(_.runWith(Sink.fold(ByteString.empty)(_ ++ _)))

  private def peerUri(bucketName: String) = Uri(s"http://$bucketName")

  private class BucketPeers(corrupt: Set[String], empty: Set[String] = Set.empty)
      extends PeerObjectSource {
    val opens = new AtomicInteger(0)
    override def open(peer: Uri, key: String)(implicit
        tc: TraceContext
    ): Future[Source[ByteString, Any]] = {
      opens.incrementAndGet()
      val bucketName = peer.authority.host.address
      if (empty.contains(bucketName)) Future.successful(Source.empty[ByteString])
      else
        localBucket(bucketName).readObject(key).map[Source[ByteString, Any]] { src =>
          if (corrupt.contains(bucketName)) src.map(bs => bs ++ ByteString("x")) else src
        }
    }
  }

  private class FailingStaging(failPartUpload: Boolean, failAfterCompleting: Boolean)
      extends S3BucketConnectionForTests(s3ConfigMock("staging"), loggerFactory) {
    override def newAppendWriteObject(key: String)(implicit
        ec: ExecutionContext
    ): AppendWriteObject =
      new AppendWriteObject(key) {
        override def upload(partNumber: Int, content: ByteBuffer): Future[Unit] =
          if (failPartUpload) Future.failed(new RuntimeException("part upload failed"))
          else super.upload(partNumber, content)
        override def finish(): Future[Unit] =
          if (failAfterCompleting)
            super
              .finish()
              .flatMap(_ => Future.failed(new RuntimeException("failed after completing")))
          else super.finish()
      }
  }

  private class UploadGate {
    val uploadStarted: Promise[Unit] = Promise()
    val releaseUpload: Promise[Unit] = Promise()
  }

  private class DownloadFailingWhileUploading(gate: UploadGate) extends PeerObjectSource {
    override def open(peer: Uri, key: String)(implicit
        tc: TraceContext
    ): Future[Source[ByteString, Any]] =
      Future.successful(
        Source
          .single(ByteString(Random.nextBytes(VerifiedObjectCopier.uploadPartSize)))
          .concat(Source.future(gate.uploadStarted.future.flatMap { _ =>
            gate.releaseUpload.trySuccess(()).discard
            Future.failed[ByteString](new RuntimeException("download broke"))
          }))
      )
  }

  private class GatedUploadStaging(gate: UploadGate, events: ConcurrentLinkedQueue[String])
      extends S3BucketConnectionForTests(s3ConfigMock("staging"), loggerFactory) {
    override def newAppendWriteObject(key: String)(implicit
        ec: ExecutionContext
    ): AppendWriteObject =
      new AppendWriteObject(key) {
        override def upload(partNumber: Int, content: ByteBuffer): Future[Unit] = {
          gate.uploadStarted.trySuccess(()).discard
          gate.releaseUpload.future
            .flatMap(_ => super.upload(partNumber, content))
            .transform { result =>
              events.add(s"part $partNumber upload finished").discard
              result
            }
        }
        override def abort(): Future[Unit] = {
          events.add("abort").discard
          super.abort()
        }
      }
  }

  private class CopierFromPeers(underlying: VerifiedObjectCopier, peers: Seq[Uri]) {
    def copy(objects: Seq[ObjectKeyAndChecksum]): Future[Unit] =
      underlying.copy(objects, peers)
  }

  private def copier(
      source: PeerObjectSource,
      staging: S3BucketConnection = localBucket("staging"),
      peers: Seq[Uri] = Seq(peerUri("peer1"), peerUri("peer2")),
  ) =
    new CopierFromPeers(
      new VerifiedObjectCopier(
        source,
        _.head,
        staging,
        localBucket("committed"),
        parallelism = 1,
        loggerFactory,
      ),
      peers,
    )

  private val content = ByteString(Random.nextBytes(1000))
  private val objectKey = "2026-01-01T00:00:00Z~2026-01-02T00:00:00Z/updates_compact_json_0.zstd"

  "VerifiedObjectCopier" should {
    "copy an object whose bytes match the agreed checksum into staging, byte for byte" in {
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- copier(new BucketPeers(Set.empty)).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
        onPeer <- storedBytes("peer1", objectKey)
        staged <- storedBytes("staging", objectKey)
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        staged shouldBe onPeer
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "fall back to the next peer when the first one returns different bytes" in {
      val peers = new BucketPeers(corrupt = Set("peer1"))
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- loggerFactory.assertLogs(
          copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest))),
          _.warningMessage should include("Checksum mismatch for object"),
        )
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        checksums.map(_.checksum) shouldBe Seq(digest)
        peers.opens.get() shouldBe 2
      }
    }

    "fall back to the next peer when the first one serves zero bytes" in {
      val peers = new BucketPeers(corrupt = Set.empty, empty = Set("peer1"))
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- loggerFactory.assertLogs(
          copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest))),
          _.warningMessage should include("Checksum mismatch for object"),
        )
        onPeer <- storedBytes("peer2", objectKey)
        staged <- storedBytes("staging", objectKey)
      } yield {
        staged shouldBe onPeer
        peers.opens.get() shouldBe 2
      }
    }

    "fail when no peer serves the agreed bytes" in {
      for {
        digest <- putOnPeers(objectKey, content)
        result <- loggerFactory.assertLogs(
          copier(new BucketPeers(corrupt = Set("peer1", "peer2")))
            .copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
            .transform(t => scala.util.Success(t)),
          _.warningMessage should include("from peer http://peer1"),
          _.warningMessage should include("from peer http://peer2"),
        )
        exists <- localBucket("staging").doesObjectExist(objectKey)
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.CopyFailed]
        result.failed.get shouldBe a[QuietNonRetryableException]
        exists shouldBe false
      }
    }

    "abort the upload and stage nothing when a multi-part download does not match" in {
      val big = ByteString(Random.nextBytes(3 * VerifiedObjectCopier.uploadPartSize + 12345))
      val bigKey = "2026-01-03T00:00:00Z~2026-01-04T00:00:00Z/updates_compact_json_0.zstd"
      for {
        digest <- putOnPeers(bigKey, big)
        result <- loggerFactory.assertLogs(
          copier(new BucketPeers(corrupt = Set("peer1", "peer2")))
            .copy(Seq(ObjectKeyAndChecksum(bigKey, digest)))
            .transform(t => scala.util.Success(t)),
          _.warningMessage should include("from peer http://peer1"),
          _.warningMessage should include("from peer http://peer2"),
        )
        exists <- localBucket("staging").doesObjectExist(bigKey)
        pendingUploads <- localBucket("staging").s3Client
          .listMultipartUploads(ListMultipartUploadsRequest.builder().bucket("staging").build())
          .asScala
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.CopyFailed]
        exists shouldBe false
        pendingUploads.uploads().asScala shouldBe empty
      }
    }

    "fail without trying the next peer when writing to staging fails" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        result <- copier(
          peers,
          new FailingStaging(failPartUpload = true, failAfterCompleting = false),
        )
          .copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
          .transform(t => scala.util.Success(t))
        exists <- localBucket("staging").doesObjectExist(objectKey)
        pendingUploads <- localBucket("staging").s3Client
          .listMultipartUploads(ListMultipartUploadsRequest.builder().bucket("staging").build())
          .asScala
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.StagingWriteFailed]
        result.failed.get shouldBe a[QuietNonRetryableException]
        result.failed.get.getCause.getMessage shouldBe "part upload failed"
        peers.opens.get() shouldBe 1
        exists shouldBe false
        pendingUploads.uploads().asScala shouldBe empty
      }
    }

    "abort the upload only after the part still uploading has finished when the download fails" in {
      val events = new ConcurrentLinkedQueue[String]()
      val gate = new UploadGate
      for {
        result <- copier(
          new DownloadFailingWhileUploading(gate),
          new GatedUploadStaging(gate, events),
          peers = Seq(peerUri("peer1")),
        )
          .copy(Seq(ObjectKeyAndChecksum(objectKey, "unused")))
          .transform(t => scala.util.Success(t))
        pendingUploads <- localBucket("staging").s3Client
          .listMultipartUploads(ListMultipartUploadsRequest.builder().bucket("staging").build())
          .asScala
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.CopyFailed]
        events.asScala.toSeq shouldBe Seq("part 1 upload finished", "abort")
        pendingUploads.uploads().asScala shouldBe empty
      }
    }

    "delete an object completed in staging when the copy then fails, so the next copy starts clean" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        result <- copier(
          peers,
          new FailingStaging(failPartUpload = false, failAfterCompleting = true),
        )
          .copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
          .transform(t => scala.util.Success(t))
        existsAfterFailure <- localBucket("staging").doesObjectExist(objectKey)
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.StagingWriteFailed]
        existsAfterFailure shouldBe false
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "overwrite an object that staging holds with a different checksum, logging an error" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- putInLocal("staging", objectKey, ByteString("stale"))
        _ <- loggerFactory.assertLogs(
          copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest))),
          _.errorMessage should include(s"Staging holds object $objectKey with checksum"),
        )
        checksums <- localBucket("staging").getChecksums(Seq(objectKey))
      } yield {
        peers.opens.get() shouldBe 1
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "skip an object that staging already holds with the same checksum" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
        opensAfterFirst = peers.opens.get()
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
      } yield {
        opensAfterFirst shouldBe 1
        peers.opens.get() shouldBe 1
      }
    }

    "skip an object that committed already holds with the same checksum" in {
      val peers = new BucketPeers(Set.empty)
      val writer = localBucket("committed").newAppendWriteObject(objectKey)
      val part = content.asByteBuffer
      writer.prepareUploadNext(part)
      for {
        _ <- writer.upload(1, part)
        _ <- writer.finish()
        committed <- localBucket("committed").getChecksums(Seq(objectKey))
        _ <- copier(peers).copy(Seq(ObjectKeyAndChecksum(objectKey, committed.head.checksum)))
        exists <- localBucket("staging").doesObjectExist(objectKey)
      } yield {
        peers.opens.get() shouldBe 0
        exists shouldBe false
      }
    }

    "fail without copying when committed holds the object with a different checksum" in {
      val peers = new BucketPeers(Set.empty)
      for {
        digest <- putOnPeers(objectKey, content)
        _ <- putInLocal("committed", objectKey, ByteString("stale"))
        result <- loggerFactory.assertLogs(
          copier(peers)
            .copy(Seq(ObjectKeyAndChecksum(objectKey, digest)))
            .transform(t => scala.util.Success(t)),
          _.errorMessage should include(s"Committed holds object $objectKey with checksum"),
        )
        exists <- localBucket("staging").doesObjectExist(objectKey)
      } yield {
        result.failed.get shouldBe a[VerifiedObjectCopier.CommittedObjectDiffers]
        result.failed.get shouldBe a[QuietNonRetryableException]
        peers.opens.get() shouldBe 0
        exists shouldBe false
      }
    }

    "upload objects larger than one part" in {
      val big = ByteString(Random.nextBytes(VerifiedObjectCopier.uploadPartSize + 12345))
      val bigKey = "2026-01-02T00:00:00Z~2026-01-03T00:00:00Z/updates_compact_json_0.zstd"
      for {
        digest <- putOnPeers(bigKey, big)
        _ <- copier(new BucketPeers(Set.empty)).copy(Seq(ObjectKeyAndChecksum(bigKey, digest)))
        onPeer <- storedBytes("peer1", bigKey)
        staged <- storedBytes("staging", bigKey)
        checksums <- localBucket("staging").getChecksums(Seq(bigKey))
      } yield {
        staged.size shouldBe onPeer.size
        staged shouldBe onPeer
        checksums.map(_.checksum) shouldBe Seq(digest)
      }
    }

    "pick a random peer among the remaining ones" in {
      val peers = (1 to 20).map(i => peerUri(s"peer$i"))
      val picks = (1 to 20).map(_ => VerifiedObjectCopier.randomPeer(peers))
      forAll(picks)(peers should contain(_))
      picks.distinct.size should be > 1
    }
  }
}
